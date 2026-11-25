package atom.llm.cloud

import atom.core.llm.ChatMessage
import atom.core.llm.ContentPart
import atom.core.llm.EngineCapabilities
import atom.core.llm.FinishReason
import atom.core.llm.LlmEngine
import atom.core.llm.LlmEvent
import atom.core.llm.LlmRequest
import atom.core.llm.ReasoningEffort
import atom.core.llm.Role
import atom.core.llm.ToolCall
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal class GeminiEngine(
    private val provider: Provider,
    private val apiKey: String,
    private val model: String,
    baseUrl: String,
    private val http: OkHttpClient,
    private val options: EngineOptions = EngineOptions(),
) : LlmEngine {

    private val root = baseUrl.trimEnd('/')
    private val vertex = provider.wire == Wire.Vertex
    private val errors = ErrorMapper(provider.displayName, apiKey)

    override val id: String = "${provider.id}:$model"

    override val capabilities = EngineCapabilities(
        nativeToolCalls = true,
        vision = options.vision ?: true,
        contextWindow = options.contextWindow ?: 1_000_000,
        onDevice = false,
    )

    override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
        val decoder = GeminiStreamDecoder(errors)
        sseEvents(http, httpRequest(request), errors).collect { event ->
            decoder.onData(event.data).forEach { emit(it) }
        }
        decoder.finish().forEach { emit(it) }
    }

    private fun httpRequest(request: LlmRequest): Request {
        val modelId = request.model.ifBlank { model }.removePrefix("models/")
        val base = if (vertex) vertexModelUrl(root, options.vertexProject, options.vertexLocation, modelId) else "$root/models/$modelId"
        val url = "$base:streamGenerateContent".toHttpUrl().newBuilder()
            .addQueryParameter("alt", "sse")
            .build()
        return Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .header("Accept", "text/event-stream")
            .apply { RequestCustomizer.applyHeaders(this, options) }
            .post(RequestCustomizer.mergeBody(buildBody(request), options.extraBody).toRequestBody())
            .build()
    }

    internal fun buildBody(request: LlmRequest): JsonObject = buildJsonObject {
        val modelId = request.model.ifBlank { model }
        request.messages.filter { it.role == Role.System }
            .joinToString("\n\n") { it.text }
            .takeIf { it.isNotBlank() }
            ?.let { system ->
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") { addJsonObject { put("text", system) } }
                }
            }
        put("contents", contents(request.messages, needsSignature = !LEGACY_MODEL.containsMatchIn(modelId)))
        if (request.tools.isNotEmpty() || options.googleSearch) {
            putJsonArray("tools") {
                if (request.tools.isNotEmpty()) addJsonObject {
                    putJsonArray("functionDeclarations") {
                        for (tool in request.tools) addJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            GeminiSchema.convert(tool.parameters)?.let { put("parameters", it) }
                        }
                    }
                }
                if (options.googleSearch) addJsonObject { putJsonObject("googleSearch") {} }
            }
        }
        putJsonObject("generationConfig") {
            put("maxOutputTokens", request.maxOutputTokens)
            request.temperature?.let { put("temperature", it) }
            val effort = request.reasoning
            val thinks = options.reasoning?.supported ?: !LEGACY_MODEL.containsMatchIn(modelId)
            when {
                effort == null -> if (thinks) putJsonObject("thinkingConfig") { put("includeThoughts", true) }
                BUDGET_MODEL.containsMatchIn(modelId) -> putJsonObject("thinkingConfig") {
                    put("includeThoughts", effort != ReasoningEffort.Off)
                    put("thinkingBudget", thinkingBudget(effort))
                }
                else -> putJsonObject("thinkingConfig") {
                    put("includeThoughts", effort != ReasoningEffort.Off)
                    put("thinkingLevel", thinkingLevel(effort))
                }
            }
        }
    }

    private fun contents(history: List<ChatMessage>, needsSignature: Boolean): JsonArray {
        val turns = mutableListOf<Pair<String, MutableList<JsonObject>>>()
        fun append(role: String, parts: List<JsonObject>) {
            if (parts.isEmpty()) return
            val last = turns.lastOrNull()
            if (last != null && last.first == role) last.second += parts else turns += role to parts.toMutableList()
        }
        for (message in history) {
            when (message.role) {
                Role.System -> Unit
                Role.User -> append("user", parts(message.parts))
                Role.Assistant -> append("model", modelParts(message, needsSignature))
                Role.Tool -> {
                    val response = buildJsonObject {
                        putJsonObject("functionResponse") {
                            put("name", message.toolName.orEmpty())
                            putJsonObject("response") { put("output", message.text) }
                        }
                    }
                    append("user", listOf(response) + parts(message.parts.filterIsInstance<ContentPart.Image>()))
                }
            }
        }
        return buildJsonArray {
            for ((role, parts) in turns) addJsonObject {
                put("role", role)
                put("parts", JsonArray(parts))
            }
        }
    }

    private fun modelParts(message: ChatMessage, needsSignature: Boolean): List<JsonObject> {
        val out = parts(message.parts.filterIsInstance<ContentPart.Text>()).toMutableList()
        val signatures = message.toolCalls.map { GeminiSignatures.get(it.id) }
        message.toolCalls.forEachIndexed { index, call ->
            val signature = signatures[index]
                ?: if (index == 0 && needsSignature && signatures.all { it == null }) SKIP_SIGNATURE else null
            out += buildJsonObject {
                putJsonObject("functionCall") {
                    put("name", call.name)
                    put("args", argumentsObject(call.argumentsJson))
                }
                signature?.let { put("thoughtSignature", it) }
            }
        }
        return out
    }

    private fun parts(parts: List<ContentPart>): List<JsonObject> = parts.mapNotNull { part ->
        when (part) {
            is ContentPart.Text -> part.text.takeIf { it.isNotEmpty() }?.let { buildJsonObject { put("text", it) } }
            is ContentPart.Image -> buildJsonObject {
                putJsonObject("inlineData") {
                    put("mimeType", part.mimeType)
                    put("data", part.base64)
                }
            }
        }
    }

    internal companion object {
        /**
         * Vertex AI model resource URL. Without a project this is express mode (API key only); "global" and
         * the "us"/"eu" multi-regions have their own hosts. A non-Google base URL is treated as an HTTPS proxy.
         */
        fun vertexModelUrl(baseUrl: String, project: String?, location: String?, model: String): String {
            val loc = location?.trim()?.ifEmpty { null } ?: "global"
            val proxy = baseUrl.trim().trimEnd('/').takeIf { it.isNotEmpty() && !it.contains("googleapis.com") }
            if (project.isNullOrBlank()) {
                return "${proxy ?: "https://aiplatform.googleapis.com"}/v1/publishers/google/models/$model"
            }
            val host = proxy ?: when (loc) {
                "global" -> "https://aiplatform.googleapis.com"
                "us", "eu" -> "https://aiplatform.$loc.rep.googleapis.com"
                else -> "https://$loc-aiplatform.googleapis.com"
            }
            return "$host/v1/projects/${project.trim()}/locations/$loc/publishers/google/models/$model"
        }

        /** Documented placeholder for function calls whose real signature is no longer available. */
        const val SKIP_SIGNATURE = "skip_thought_signature_validator"
        val LEGACY_MODEL = Regex("gemini-(1|2)\\.")
        val BUDGET_MODEL = Regex("gemini-2\\.5")

        fun thinkingLevel(effort: ReasoningEffort): String = when (effort) {
            ReasoningEffort.Off -> "minimal"
            else -> effort.wireName
        }

        fun thinkingBudget(effort: ReasoningEffort): Int = when (effort) {
            ReasoningEffort.Low -> 1024
            ReasoningEffort.Medium -> 8192
            ReasoningEffort.High -> 24_576
            ReasoningEffort.XHigh, ReasoningEffort.Max -> 32_768
            ReasoningEffort.Off -> 0
        }
    }
}

/**
 * Gemini 3 rejects replayed function calls without the thought signature it issued. [ToolCall] has no
 * slot for it, so signatures are remembered here by call id for the life of the process.
 */
internal object GeminiSignatures {
    private const val CAPACITY = 512
    private val map = object : LinkedHashMap<String, String>(CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > CAPACITY
    }

    fun put(callId: String, signature: String) = synchronized(map) { map[callId] = signature }

    fun get(callId: String): String? = synchronized(map) { map[callId] }
}

internal class GeminiStreamDecoder(private val errors: ErrorMapper) {

    private var finish: FinishReason? = null
    private var calls = 0
    private var usage: LlmEvent.Usage? = null
    private val sources = LinkedHashMap<String, String>()

    fun onData(data: String): List<LlmEvent> {
        val chunk = parseJsonOrNull(data) as? JsonObject
            ?: throw errors.stream("malformed stream chunk", retryable = false)
        chunk["error"]?.let { throw errors.stream(messageIn(it) ?: "stream error", retryable = false) }
        chunk.obj("usageMetadata")?.let { u ->
            usage = LlmEvent.Usage(
                inputTokens = u.int("promptTokenCount") ?: 0,
                outputTokens = (u.int("candidatesTokenCount") ?: 0) + (u.int("thoughtsTokenCount") ?: 0),
                cacheReadTokens = u.int("cachedContentTokenCount") ?: 0,
            )
        }
        val candidates = chunk.arr("candidates")
        chunk.obj("promptFeedback")?.str("blockReason")?.let { reason ->
            if (candidates.isEmpty()) throw errors.stream("prompt blocked ($reason)", retryable = false)
        }
        val out = mutableListOf<LlmEvent>()
        val candidate = candidates.firstOrNull()?.asObject() ?: return out
        for (partElement in candidate.obj("content")?.arr("parts").orEmpty()) {
            val part = partElement.asObject() ?: continue
            val call = part.obj("functionCall")
            when {
                call != null -> out += toolCall(call, part.str("thoughtSignature"))
                part.bool("thought") == true -> part.str("text")?.takeIf { it.isNotEmpty() }?.let { out += LlmEvent.ReasoningDelta(it) }
                else -> part.str("text")?.takeIf { it.isNotEmpty() }?.let { out += LlmEvent.TextDelta(it) }
            }
        }
        candidate.obj("groundingMetadata")?.arr("groundingChunks")?.forEach { chunk ->
            val web = chunk.asObject()?.obj("web") ?: return@forEach
            val uri = web.str("uri") ?: return@forEach
            sources.putIfAbsent(uri, web.str("title")?.takeIf { it.isNotBlank() } ?: uri)
        }
        candidate.str("finishReason")?.let { finish = mapFinish(it) }
        return out
    }

    fun finish(): List<LlmEvent> {
        val reason = finish ?: throw errors.stream("stream ended before the response completed", retryable = true)
        val out = mutableListOf<LlmEvent>()
        if (sources.isNotEmpty()) out += LlmEvent.TextDelta(formatSources(sources))
        usage?.let { out += it }
        out += LlmEvent.Finished(if (calls > 0 && reason == FinishReason.Stop) FinishReason.ToolCalls else reason)
        return out
    }

    private fun toolCall(call: JsonObject, signature: String?): LlmEvent.ToolCallReady {
        calls++
        val id = call.str("id")?.takeIf { it.isNotEmpty() }
            ?: ("call_" + UUID.randomUUID().toString().replace("-", "").take(24))
        signature?.let { GeminiSignatures.put(id, it) }
        val args = call["args"] as? JsonObject ?: JsonObject(emptyMap())
        return LlmEvent.ToolCallReady(ToolCall(id, call.str("name").orEmpty(), args.toString()))
    }

    private fun mapFinish(reason: String): FinishReason = when (reason) {
        "MAX_TOKENS" -> FinishReason.Length
        else -> FinishReason.Stop
    }
}

/** Reduces a JSON Schema to the OpenAPI subset accepted by Gemini function declarations. */
internal object GeminiSchema {
    private val KEEP = setOf(
        "description", "nullable", "enum", "items", "properties", "required", "minItems", "maxItems",
        "minimum", "maximum", "minLength", "maxLength", "pattern", "anyOf", "title", "format", "propertyOrdering",
    )
    private val FORMATS = mapOf(
        "string" to setOf("enum", "date-time"),
        "integer" to setOf("int32", "int64"),
        "number" to setOf("float", "double"),
    )

    /** Returns null for schemas with no parameters, which Gemini wants omitted entirely. */
    fun convert(schema: JsonObject): JsonObject? {
        val converted = node(schema)
        val properties = converted.obj("properties")
        return if (converted.str("type") == "object" && properties.isNullOrEmpty()) null else converted
    }

    private fun node(schema: JsonObject): JsonObject = buildJsonObject {
        val (declared, nullable) = typeOf(schema)
        val type = if (schema["enum"] != null || schema["const"] != null) "string" else declared
        type?.let { put("type", it) }
        if (nullable) put("nullable", true)
        schema["const"]?.let { put("enum", JsonArray(listOf(asString(it)))) }
        for ((key, value) in schema) {
            if (key !in KEEP) continue
            when (key) {
                "properties" -> (value as? JsonObject)?.let { props ->
                    putJsonObject("properties") {
                        for ((name, child) in props) (child as? JsonObject)?.let { put(name, node(it)) }
                    }
                }
                "items" -> (value as? JsonObject)?.let { put("items", node(it)) }
                "anyOf" -> (value as? JsonArray)?.let { options ->
                    val nonNull = options.mapNotNull { it as? JsonObject }.filter { it.str("type") != "null" }
                    if (nonNull.size < options.size) put("nullable", true)
                    put("anyOf", JsonArray(nonNull.map(::node)))
                }
                "enum" -> (value as? JsonArray)?.let { values ->
                    if (schema["const"] == null) put("enum", JsonArray(values.filter { it !is JsonNull }.map(::asString)))
                }
                "required" -> (value as? JsonArray)?.let { required ->
                    val names = schema.obj("properties")?.keys.orEmpty()
                    put("required", JsonArray(required.filter { (it as? JsonPrimitive)?.content in names }))
                }
                "format" -> if (FORMATS[type]?.contains(schema.str("format")) == true) put("format", value)
                "nullable" -> if (!nullable) put("nullable", value)
                else -> put(key, value)
            }
        }
    }

    private fun typeOf(schema: JsonObject): Pair<String?, Boolean> {
        return when (val raw = schema["type"]) {
            is JsonPrimitive -> raw.content to false
            is JsonArray -> {
                val names = raw.mapNotNull { (it as? JsonPrimitive)?.content }
                names.firstOrNull { it != "null" } to ("null" in names)
            }
            else -> when {
                schema["properties"] != null -> "object" to false
                schema["items"] != null -> "array" to false
                schema["enum"] != null || schema["const"] != null -> "string" to false
                else -> null to false
            }
        }
    }

    private fun asString(value: JsonElement): JsonPrimitive =
        if (value is JsonPrimitive && value.isString) value else JsonPrimitive(value.toString().trim('"'))
}
