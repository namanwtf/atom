package atom.llm.cloud

import atom.core.llm.ChatMessage
import atom.core.llm.ContentPart
import atom.core.llm.EngineCapabilities
import atom.core.llm.FinishReason
import atom.core.llm.LlmEngine
import atom.core.llm.LlmEvent
import atom.core.llm.LlmRequest
import atom.core.llm.Role
import atom.core.llm.ToolCallIds
import atom.core.tool.ToolLoadout
import atom.core.llm.ToolCall
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

internal class OpenAiChatEngine(
    private val provider: Provider,
    private val apiKey: String,
    private val model: String,
    baseUrl: String,
    private val http: OkHttpClient,
    private val options: EngineOptions = EngineOptions(),
) : LlmEngine {

    private val endpoint = baseUrl.trimEnd('/') + "/chat/completions"
    private val errors = ErrorMapper(provider.displayName, apiKey)
    private val family = provider.family

    override val id: String = "${provider.id}:$model"

    override val capabilities = EngineCapabilities(
        nativeToolCalls = true,
        vision = options.vision ?: visionLikely(provider, model),
        contextWindow = options.contextWindow ?: 128_000,
        onDevice = false,
    )

    override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
        val decoder = OpenAiStreamDecoder(errors)
        sseEvents(http, httpRequest(request), errors).collect { event ->
            decoder.onData(event.data).forEach { emit(it) }
        }
        decoder.finish().forEach { emit(it) }
        decoder.interleavedReasoning()?.let { (callId, text) ->
            if (ChatReasoning.interleaved(family)) ReplayCaches.chatReasoning.put(callId, text)
        }
    }

    private fun httpRequest(request: LlmRequest): Request =
        Request.Builder()
            .url(endpoint)
            .header("Accept", "text/event-stream")
            .apply {
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
                if (family == Family.OPENROUTER) {
                    header("HTTP-Referer", OPENROUTER_REFERER)
                    header("X-Title", APP_NAME)
                }
                RequestCustomizer.applyHeaders(this, options)
            }
            .post(RequestCustomizer.mergeBody(buildBody(request), options.extraBody).toRequestBody())
            .build()

    internal fun buildBody(request: LlmRequest): JsonObject = buildJsonObject {
        val modelId = request.model.ifBlank { model }
        put("model", modelId)
        put("stream", true)
        if (family != Family.MISTRAL) putJsonObject("stream_options") { put("include_usage", true) }
        put("messages", messages(request.messages))
        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                for (tool in request.tools) addJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("parameters", tool.parameters)
                    }
                }
            }
        }
        val openAi = family == Family.OPENAI
        val reasoningModel = openAi && (OPENAI_REASONING_MODEL.containsMatchIn(modelId) || options.reasoning?.supported == true)
        if (openAi) put("max_completion_tokens", request.maxOutputTokens) else put("max_tokens", request.maxOutputTokens)
        if (!openAi || reasoningModel) ChatReasoning.apply(this, family, request.reasoning)
        val temperature = request.temperature
        if (temperature != null && !reasoningModel) put("temperature", temperature)
    }

    private fun messages(history: List<ChatMessage>): JsonArray = buildJsonArray {
        val pendingImages = mutableListOf<ContentPart.Image>()
        val replay = ChatReasoning.interleaved(family)
        fun flushImages() {
            if (pendingImages.isEmpty()) return
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", "Images returned by the tool calls above:")
                    }
                    pendingImages.forEach { add(imagePart(it)) }
                }
            }
            pendingImages.clear()
        }
        // Mistral takes only nine-letter-and-digit ids; ids made by other providers (or old builds) are mapped to
        // that form, deterministically so a call and its result still match.
        val wireId: (String) -> String = if (family == Family.MISTRAL) ToolCallIds::strict else ToolCallIds::safe
        // Thinking vendors that need each tool-call turn's reasoning back (DeepSeek, Kimi...) reject a call turn
        // without it. The run's own tool preload has none: it goes as a plain note instead of a tool-call turn.
        val asNote = if (!replay) emptySet() else history.flatMap { m ->
            val loaderOnly = m.toolCalls.isNotEmpty() && m.toolCalls.all { it.name == ToolLoadout.LOADER }
            if (loaderOnly && ReplayCaches.chatReasoning.get(m.toolCalls.first().id) == null) m.toolCalls.map { it.id } else emptyList()
        }.toSet()
        for (message in history) {
            if (message.role != Role.Tool) flushImages()
            if (message.role == Role.Assistant && message.toolCalls.isNotEmpty() && message.toolCalls.all { it.id in asNote }) {
                if (message.text.isNotBlank()) addJsonObject {
                    put("role", "assistant")
                    put("content", message.text)
                }
                continue
            }
            if (message.role == Role.Tool && message.toolCallId in asNote) {
                addJsonObject {
                    put("role", "user")
                    put("content", "[Tools loaded for this task]\n" + message.text)
                }
                continue
            }
            when (message.role) {
                Role.System -> addJsonObject {
                    put("role", "system")
                    put("content", message.text)
                }
                Role.User -> addJsonObject {
                    put("role", "user")
                    putUserContent(message.parts)
                }
                Role.Assistant -> {
                    if (message.text.isEmpty() && message.toolCalls.isEmpty()) continue
                    addJsonObject {
                        put("role", "assistant")
                        if (message.text.isEmpty()) put("content", JsonNull) else put("content", message.text)
                        if (message.toolCalls.isNotEmpty()) {
                            if (replay) {
                                ReplayCaches.chatReasoning.get(message.toolCalls.first().id)?.let { put("reasoning_content", it) }
                            }
                            putJsonArray("tool_calls") {
                                for (call in message.toolCalls) addJsonObject {
                                    put("id", wireId(call.id))
                                    put("type", "function")
                                    putJsonObject("function") {
                                        put("name", call.name)
                                        put("arguments", normalizeArguments(call.argumentsJson))
                                    }
                                }
                            }
                        }
                    }
                }
                Role.Tool -> {
                    addJsonObject {
                        put("role", "tool")
                        put("tool_call_id", wireId(message.toolCallId.orEmpty()))
                        put("content", message.text.ifEmpty { "(no output)" })
                    }
                    pendingImages += message.parts.filterIsInstance<ContentPart.Image>()
                }
            }
        }
        flushImages()
    }

    private fun JsonObjectBuilder.putUserContent(parts: List<ContentPart>) {
        if (parts.all { it is ContentPart.Text }) {
            put("content", parts.joinToString("") { (it as ContentPart.Text).text })
            return
        }
        putJsonArray("content") {
            for (part in parts) when (part) {
                is ContentPart.Text -> addJsonObject {
                    put("type", "text")
                    put("text", part.text)
                }
                is ContentPart.Image -> add(imagePart(part))
            }
        }
    }

    private fun imagePart(image: ContentPart.Image): JsonObject = buildJsonObject {
        put("type", "image_url")
        putJsonObject("image_url") { put("url", "data:${image.mimeType};base64,${image.base64}") }
    }

    private companion object {
        val OPENAI_REASONING_MODEL = Regex("^(o\\d|gpt-([5-9]|\\d{2}))")
    }
}

internal const val APP_NAME = "Atom"
internal const val OPENROUTER_REFERER = "https://atom.invalid"

/** Turns Chat Completions stream chunks into [LlmEvent]s, accumulating tool-call fragments by index. */
internal class OpenAiStreamDecoder(private val errors: ErrorMapper) {

    private class PendingCall(var id: String?, var name: String, val arguments: StringBuilder = StringBuilder())

    private val pending = sortedMapOf<Int, PendingCall>()
    private var finish: FinishReason? = null
    private var sawDone = false
    private var emittedCalls = false
    private var firstCallId: String? = null
    private val reasoning = StringBuilder()
    private var usage: LlmEvent.Usage? = null

    fun onData(data: String): List<LlmEvent> {
        if (data.trim() == "[DONE]") {
            sawDone = true
            return emptyList()
        }
        val chunk = parseJsonOrNull(data) as? JsonObject
            ?: throw errors.stream("malformed stream chunk", retryable = false)
        chunk["error"]?.let { throw errors.stream(messageIn(it) ?: "stream error", retryable = false) }
        chunk.obj("usage")?.let { u ->
            usage = LlmEvent.Usage(
                inputTokens = u.int("prompt_tokens") ?: 0,
                outputTokens = u.int("completion_tokens") ?: 0,
                cacheReadTokens = u.obj("prompt_tokens_details")?.int("cached_tokens") ?: u.int("prompt_cache_hit_tokens") ?: 0,
            )
        }
        val out = mutableListOf<LlmEvent>()
        for (choiceElement in chunk.arr("choices")) {
            val choice = choiceElement.asObject() ?: continue
            choice.obj("delta")?.let { delta ->
                (delta.str("reasoning_content") ?: delta.str("reasoning"))
                    ?.takeIf { it.isNotEmpty() }
                    ?.let {
                        reasoning.append(it)
                        out += LlmEvent.ReasoningDelta(it)
                    }
                delta.str("content")?.takeIf { it.isNotEmpty() }?.let { out += LlmEvent.TextDelta(it) }
                delta.arr("tool_calls").forEach { it.asObject()?.let(::accumulate) }
            }
            choice.str("finish_reason")?.let { reason ->
                finish = mapFinish(reason)
                out += flushCalls()
            }
        }
        return out
    }

    fun finish(): List<LlmEvent> {
        if (!sawDone && finish == null) throw errors.stream("stream ended before the response completed", retryable = true)
        val out = flushCalls().toMutableList()
        usage?.let { out += it }
        val reason = finish ?: FinishReason.Stop
        out += LlmEvent.Finished(if (emittedCalls && reason == FinishReason.Stop) FinishReason.ToolCalls else reason)
        return out
    }

    /** The reasoning text of a turn that ended in tool calls, keyed by its first call id. */
    fun interleavedReasoning(): Pair<String, String>? {
        val id = firstCallId ?: return null
        return if (reasoning.isEmpty()) null else id to reasoning.toString()
    }

    private fun accumulate(fragment: JsonObject) {
        val id = fragment.str("id")?.takeIf { it.isNotEmpty() }
        val index = fragment.int("index")
            ?: id?.let { wanted -> pending.entries.firstOrNull { it.value.id == wanted }?.key }
            ?: if (id != null || pending.isEmpty()) (pending.keys.maxOrNull()?.plus(1) ?: 0) else pending.lastKey()
        val call = pending.getOrPut(index) { PendingCall(id, "") }
        if (call.id == null) call.id = id
        val function = fragment.obj("function") ?: return
        function.str("name")?.takeIf { it.isNotEmpty() && call.name.isEmpty() }?.let { call.name = it }
        function.str("arguments")?.let { call.arguments.append(it) }
            ?: function.obj("arguments")?.let { call.arguments.append(it.toString()) }
    }

    private fun flushCalls(): List<LlmEvent> {
        if (pending.isEmpty()) return emptyList()
        val ready = pending.values.map { call ->
            LlmEvent.ToolCallReady(
                ToolCall(
                    id = call.id ?: "call_" + UUID.randomUUID().toString().replace("-", "").take(24),
                    name = call.name,
                    argumentsJson = normalizeArguments(call.arguments.toString()),
                ),
            )
        }
        pending.clear()
        if (firstCallId == null) firstCallId = ready.first().call.id
        emittedCalls = true
        return ready
    }

    private fun mapFinish(reason: String): FinishReason = when (reason) {
        "tool_calls", "function_call" -> FinishReason.ToolCalls
        "length", "max_tokens" -> FinishReason.Length
        else -> FinishReason.Stop
    }
}

internal fun visionLikely(provider: Provider, model: String): Boolean {
    if (provider.wire.protocol != Protocol.OpenAi || provider.family in setOf(Family.OPENAI, Family.OPENROUTER, Family.XAI, Family.CHATGPT)) {
        return true
    }
    return VISION_HINT.containsMatchIn(model.lowercase())
}

private val VISION_HINT = Regex("vl|vision|omni|4o|gpt-4\\.1|gpt-[5-9]|grok-4|pixtral|mistral-(medium|small)|llava|gemma-?3|kimi-k(2\\.[6-9]|3)|minimax-m3|qwen3\\.[5-9]|mimo|deepseek-(flash|v4\\.1)|step-[3-9]")
