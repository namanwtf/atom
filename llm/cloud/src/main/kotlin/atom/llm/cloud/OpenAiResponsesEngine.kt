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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * OpenAI Responses API (`POST /responses`, streamed). Requests are stateless (`store: false`); encrypted
 * reasoning items are replayed from memory so reasoning models keep their chain of thought across a tool loop.
 */
internal class OpenAiResponsesEngine(
    private val provider: Provider,
    private val apiKey: String,
    private val model: String,
    baseUrl: String,
    private val http: OkHttpClient,
    private val options: EngineOptions = EngineOptions(),
) : LlmEngine {

    private val endpoint = baseUrl.trimEnd('/') + "/responses"
    private val errors = ErrorMapper(provider.displayName, apiKey)
    private val family = provider.family

    override val id: String = "${provider.id}:$model"

    override val capabilities = EngineCapabilities(
        nativeToolCalls = true,
        vision = options.vision ?: true,
        contextWindow = options.contextWindow ?: 400_000,
        onDevice = false,
    )

    override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
        val decoder = ResponsesStreamDecoder(errors)
        sseEvents(http, httpRequest(request), errors).collect { event ->
            decoder.onEvent(event.type, event.data).forEach { emit(it) }
        }
        decoder.finish().forEach { emit(it) }
        decoder.reasoningReplay()?.let { (callId, items) -> ReplayCaches.responsesReasoning.put(callId, items) }
    }

    private fun httpRequest(request: LlmRequest): Request =
        Request.Builder()
            .url(endpoint)
            .header("Accept", "text/event-stream")
            .apply {
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
                RequestCustomizer.applyHeaders(this, options)
            }
            .post(RequestCustomizer.mergeBody(buildBody(request), options.extraBody).toRequestBody())
            .build()

    private fun reasoningCapable(modelId: String): Boolean =
        options.reasoning?.supported ?: (CapabilityDetector.byFamily(modelId).reasoning?.supported == true)

    internal fun buildBody(request: LlmRequest): JsonObject = buildJsonObject {
        val modelId = request.model.ifBlank { model }
        val reasoning = reasoningCapable(modelId)
        put("model", modelId)
        put("stream", true)
        put("store", false)
        val instructions = request.messages.filter { it.role == Role.System }.joinToString("\n\n") { it.text }
        if (instructions.isNotBlank() || family == Family.CHATGPT) put("instructions", instructions)
        put("input", input(request.messages, replayReasoning = reasoning))
        val webSearch = options.hostedWebSearch
        if (request.tools.isNotEmpty() || webSearch) {
            putJsonArray("tools") {
                for (tool in request.tools) addJsonObject {
                    put("type", "function")
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", tool.parameters)
                    put("strict", false)
                }
                if (webSearch) addJsonObject { put("type", "web_search") }
            }
            put("tool_choice", "auto")
            put("parallel_tool_calls", true)
        }
        if (family != Family.CHATGPT) put("max_output_tokens", request.maxOutputTokens)
        if (reasoning) {
            putJsonObject("reasoning") {
                request.reasoning?.let { effort ->
                    val name = if (family == Family.XAI && effort == ReasoningEffort.Max) "xhigh" else effort.wireName
                    put("effort", name)
                }
                if (request.reasoning != ReasoningEffort.Off) put("summary", "auto")
            }
            putJsonArray("include") { add("reasoning.encrypted_content") }
        } else {
            request.temperature?.let { put("temperature", it) }
        }
    }

    private fun input(history: List<ChatMessage>, replayReasoning: Boolean): JsonArray = buildJsonArray {
        val pendingImages = mutableListOf<ContentPart.Image>()
        fun flushImages() {
            if (pendingImages.isEmpty()) return
            addJsonObject {
                put("type", "message")
                put("role", "user")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "input_text")
                        put("text", "Images returned by the tool calls above:")
                    }
                    pendingImages.forEach { add(inputImage(it)) }
                }
            }
            pendingImages.clear()
        }
        for (message in history) {
            if (message.role != Role.Tool) flushImages()
            when (message.role) {
                Role.System -> Unit
                Role.User -> addJsonObject {
                    put("type", "message")
                    put("role", "user")
                    putJsonArray("content") {
                        for (part in message.parts) when (part) {
                            is ContentPart.Text -> if (part.text.isNotEmpty()) addJsonObject {
                                put("type", "input_text")
                                put("text", part.text)
                            }
                            is ContentPart.Image -> add(inputImage(part))
                        }
                    }
                }
                Role.Assistant -> {
                    if (replayReasoning && message.toolCalls.isNotEmpty()) {
                        ReplayCaches.responsesReasoning.get(message.toolCalls.first().id)?.forEach { add(it) }
                    }
                    if (message.text.isNotEmpty()) addJsonObject {
                        put("type", "message")
                        put("role", "assistant")
                        putJsonArray("content") {
                            addJsonObject {
                                put("type", "output_text")
                                put("text", message.text)
                            }
                        }
                    }
                    for (call in message.toolCalls) addJsonObject {
                        put("type", "function_call")
                        put("call_id", call.id)
                        put("name", call.name)
                        put("arguments", normalizeArguments(call.argumentsJson))
                    }
                }
                Role.Tool -> {
                    addJsonObject {
                        put("type", "function_call_output")
                        put("call_id", message.toolCallId.orEmpty())
                        put("output", message.text.ifEmpty { "(no output)" })
                    }
                    pendingImages += message.parts.filterIsInstance<ContentPart.Image>()
                }
            }
        }
        flushImages()
    }

    private fun inputImage(image: ContentPart.Image): JsonObject = buildJsonObject {
        put("type", "input_image")
        put("image_url", "data:${image.mimeType};base64,${image.base64}")
    }
}

/** Turns Responses API stream events into [LlmEvent]s. */
internal class ResponsesStreamDecoder(private val errors: ErrorMapper) {

    private class PendingCall(val callId: String, var name: String, val arguments: StringBuilder = StringBuilder())

    private val pending = LinkedHashMap<String, PendingCall>()
    private val reasoningItems = mutableListOf<JsonElement>()
    private val citations = LinkedHashMap<String, String>()
    private var firstCallId: String? = null
    private var calls = 0
    private var finish: FinishReason? = null
    private var usage: LlmEvent.Usage? = null
    private var summaryParts = 0

    fun onEvent(type: String?, data: String): List<LlmEvent> {
        if (data.trim() == "[DONE]") return emptyList()
        val event = parseJsonOrNull(data) as? JsonObject
            ?: throw errors.stream("malformed stream event", retryable = false)
        val out = mutableListOf<LlmEvent>()
        when (event.str("type") ?: type) {
            "response.output_text.delta" -> event.str("delta")?.takeIf { it.isNotEmpty() }?.let { out += LlmEvent.TextDelta(it) }
            "response.reasoning_summary_part.added" -> {
                if (summaryParts++ > 0) out += LlmEvent.ReasoningDelta("\n\n")
            }
            "response.reasoning_summary_text.delta", "response.reasoning_text.delta" ->
                event.str("delta")?.takeIf { it.isNotEmpty() }?.let { out += LlmEvent.ReasoningDelta(it) }
            "response.output_item.added" -> event.obj("item")?.let { item ->
                if (item.str("type") == "function_call") {
                    val key = item.str("id") ?: item.str("call_id") ?: "idx${event.int("output_index")}"
                    pending[key] = PendingCall(item.str("call_id") ?: key, item.str("name").orEmpty()).also { call ->
                        item.str("arguments")?.let { call.arguments.append(it) }
                    }
                }
            }
            "response.function_call_arguments.delta" -> pendingFor(event)?.arguments?.append(event.str("delta").orEmpty())
            "response.function_call_arguments.done" -> pendingFor(event)?.let { call ->
                event.str("arguments")?.let {
                    call.arguments.setLength(0)
                    call.arguments.append(it)
                }
            }
            "response.output_text.annotation.added" -> event.obj("annotation")?.let(::citation)
            "response.output_item.done" -> event.obj("item")?.let { item -> out += itemDone(item) }
            "response.completed" -> complete(event.obj("response"), FinishReason.Stop)
            "response.incomplete" -> {
                val reason = event.obj("response")?.obj("incomplete_details")?.str("reason")
                complete(event.obj("response"), if (reason == "max_output_tokens") FinishReason.Length else FinishReason.Stop)
            }
            "response.failed" -> {
                val error = event.obj("response")?.obj("error")
                throw errors.stream(error?.str("message") ?: "response failed", retryable = error?.str("code") in RETRYABLE)
            }
            "error" -> {
                val error = event.obj("error") ?: event
                throw errors.stream(error.str("message") ?: "stream error", retryable = error.str("code") in RETRYABLE)
            }
        }
        return out
    }

    fun finish(): List<LlmEvent> {
        val reason = finish ?: throw errors.stream("stream ended before the response completed", retryable = true)
        val out = mutableListOf<LlmEvent>()
        for (call in pending.values.toList()) out += ready(call)
        pending.clear()
        if (citations.isNotEmpty()) out += LlmEvent.TextDelta(formatSources(citations))
        usage?.let { out += it }
        out += LlmEvent.Finished(if (calls > 0 && reason == FinishReason.Stop) FinishReason.ToolCalls else reason)
        return out
    }

    /** Reasoning items of a turn that ended in tool calls, to replay with those calls next request. */
    fun reasoningReplay(): Pair<String, JsonArray>? {
        val id = firstCallId ?: return null
        return if (reasoningItems.isEmpty()) null else id to JsonArray(reasoningItems.toList())
    }

    private fun pendingFor(event: JsonObject): PendingCall? {
        val key = event.str("item_id") ?: return pending.values.lastOrNull()
        return pending[key] ?: pending.values.firstOrNull { it.callId == key } ?: pending.values.lastOrNull()
    }

    private fun itemDone(item: JsonObject): List<LlmEvent> {
        when (item.str("type")) {
            "function_call" -> {
                val key = item.str("id") ?: item.str("call_id")
                val call = pending.remove(key)
                    ?: pending.entries.firstOrNull { it.value.callId == item.str("call_id") }?.let { pending.remove(it.key) }
                    ?: PendingCall(item.str("call_id") ?: key ?: newId(), item.str("name").orEmpty())
                if (call.name.isEmpty()) call.name = item.str("name").orEmpty()
                item.str("arguments")?.let {
                    call.arguments.setLength(0)
                    call.arguments.append(it)
                }
                return listOf(ready(call))
            }
            "reasoning" -> if (item.str("encrypted_content") != null) {
                reasoningItems += JsonObject(item.filterKeys { it != "id" && it != "status" })
            }
            "message" -> item.arr("content").forEach { part ->
                part.asObject()?.arr("annotations")?.forEach { it.asObject()?.let(::citation) }
            }
        }
        return emptyList()
    }

    private fun ready(call: PendingCall): LlmEvent.ToolCallReady {
        calls++
        if (firstCallId == null) firstCallId = call.callId
        return LlmEvent.ToolCallReady(ToolCall(call.callId, call.name, normalizeArguments(call.arguments.toString())))
    }

    private fun citation(annotation: JsonObject) {
        if (annotation.str("type") != "url_citation") return
        val url = annotation.str("url") ?: return
        citations.putIfAbsent(url, annotation.str("title")?.takeIf { it.isNotBlank() } ?: url)
    }

    private fun complete(response: JsonObject?, reason: FinishReason) {
        finish = reason
        response?.obj("usage")?.let { u ->
            usage = LlmEvent.Usage(
                inputTokens = u.int("input_tokens") ?: 0,
                outputTokens = u.int("output_tokens") ?: 0,
                cacheReadTokens = u.obj("input_tokens_details")?.int("cached_tokens") ?: 0,
            )
        }
    }

    private fun newId() = "call_" + UUID.randomUUID().toString().replace("-", "").take(24)

    private companion object {
        val RETRYABLE = setOf("server_error", "rate_limit_exceeded", "overloaded", "timeout")
    }
}

/** Markdown footer listing provider-hosted search sources, so they render as tappable links. */
internal fun formatSources(sources: Map<String, String>): String = buildString {
    append("\n\n**Sources**\n")
    sources.entries.forEachIndexed { i, (url, title) ->
        append(i + 1).append(". [").append(title.replace("]", "\\]")).append("](").append(url).append(")\n")
    }
}.trimEnd('\n')
