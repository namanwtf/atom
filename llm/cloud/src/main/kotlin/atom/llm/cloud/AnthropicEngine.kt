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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

internal class AnthropicEngine(
    private val provider: Provider,
    private val apiKey: String,
    private val model: String,
    baseUrl: String,
    private val http: OkHttpClient,
) : LlmEngine {

    private val root = baseUrl.trimEnd('/')
    private val official = root == provider.baseUrl.trimEnd('/')
    private val errors = ErrorMapper(provider.displayName, apiKey)

    override val id: String = "${provider.id}:$model"

    override val capabilities = EngineCapabilities(
        nativeToolCalls = true,
        vision = true,
        contextWindow = if (isLegacyModel(model)) 200_000 else 1_000_000,
        onDevice = false,
    )

    override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
        val decoder = AnthropicStreamDecoder(errors)
        sseEvents(http, httpRequest(request), errors).collect { event ->
            decoder.onEvent(event.type, event.data).forEach { emit(it) }
        }
        decoder.finish().forEach { emit(it) }
    }

    private fun httpRequest(request: LlmRequest): Request =
        Request.Builder()
            .url("$root/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("Accept", "text/event-stream")
            .post(buildBody(request).toRequestBody())
            .build()

    internal fun buildBody(request: LlmRequest): JsonObject = buildJsonObject {
        val modelId = request.model.ifBlank { model }
        val legacy = isLegacyModel(modelId)
        val effort = request.reasoning?.takeIf { it != ReasoningEffort.Off }
        val legacyBudget = effort?.takeIf { legacy && !midToolLoop(request.messages) }?.let(::thinkingBudget)

        put("model", modelId)
        put("max_tokens", request.maxOutputTokens + (legacyBudget ?: 0))
        put("stream", true)
        request.messages.filter { it.role == Role.System }
            .joinToString("\n\n") { it.text }
            .takeIf { it.isNotBlank() }
            ?.let { put("system", it) }
        put("messages", messages(request.messages))
        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                for (tool in request.tools) addJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("input_schema", tool.parameters)
                    if (official) put("eager_input_streaming", true)
                }
            }
        }
        when {
            legacyBudget != null -> putJsonObject("thinking") {
                put("type", "enabled")
                put("budget_tokens", legacyBudget)
            }
            effort != null && !legacy -> {
                putJsonObject("thinking") {
                    put("type", "adaptive")
                    put("display", "summarized")
                }
                putJsonObject("output_config") { put("effort", effort.wireName) }
            }
        }
        val temperature = request.temperature
        if (temperature != null && legacy && legacyBudget == null) put("temperature", temperature)
    }

    private fun messages(history: List<ChatMessage>): JsonArray {
        val turns = mutableListOf<Pair<String, MutableList<JsonObject>>>()
        fun append(role: String, blocks: List<JsonObject>) {
            if (blocks.isEmpty()) return
            val last = turns.lastOrNull()
            if (last != null && last.first == role) last.second += blocks else turns += role to blocks.toMutableList()
        }
        for (message in history) {
            when (message.role) {
                Role.System -> Unit
                Role.User -> append("user", contentBlocks(message.parts))
                Role.Assistant -> append(
                    "assistant",
                    textBlocks(message.text) + message.toolCalls.map { call ->
                        buildJsonObject {
                            put("type", "tool_use")
                            put("id", call.id)
                            put("name", call.name)
                            put("input", argumentsObject(call.argumentsJson))
                        }
                    },
                )
                Role.Tool -> append(
                    "user",
                    listOf(
                        buildJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", message.toolCallId.orEmpty())
                            val content = contentBlocks(message.parts)
                            if (content.isNotEmpty()) put("content", JsonArray(content))
                        },
                    ),
                )
            }
        }
        return buildJsonArray {
            for ((role, blocks) in turns) addJsonObject {
                put("role", role)
                put("content", JsonArray(blocks))
            }
        }
    }

    private fun textBlocks(text: String): List<JsonObject> =
        if (text.isBlank()) emptyList() else listOf(buildJsonObject {
            put("type", "text")
            put("text", text)
        })

    private fun contentBlocks(parts: List<ContentPart>): List<JsonObject> = parts.flatMap { part ->
        when (part) {
            is ContentPart.Text -> textBlocks(part.text)
            is ContentPart.Image -> listOf(buildJsonObject {
                put("type", "image")
                putJsonObject("source") {
                    put("type", "base64")
                    put("media_type", part.mimeType)
                    put("data", part.base64)
                }
            })
        }
    }

    internal companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"

        private val LEGACY = Regex("^claude-(3|(opus|sonnet|haiku)-4(-[0-5])?(-\\d{8})?$)")

        /** Models before the 4.6 generation take a fixed thinking budget instead of adaptive thinking. */
        fun isLegacyModel(model: String): Boolean = LEGACY.containsMatchIn(model)

        fun thinkingBudget(effort: ReasoningEffort): Int = when (effort) {
            ReasoningEffort.Low -> 1024
            ReasoningEffort.Medium -> 4096
            ReasoningEffort.High -> 16_384
            ReasoningEffort.XHigh -> 32_000
            ReasoningEffort.Max -> 63_999
            ReasoningEffort.Off -> 0
        }

        /**
         * Budgeted thinking requires the last assistant tool-use turn to start with its thinking block,
         * which the shared history does not keep, so thinking pauses until the tool loop ends.
         */
        fun midToolLoop(history: List<ChatMessage>): Boolean =
            history.lastOrNull { it.role == Role.Assistant }?.toolCalls?.isNotEmpty() == true
    }
}

internal class AnthropicStreamDecoder(private val errors: ErrorMapper) {

    private class Block(val type: String, val id: String?, val name: String?) {
        val input = StringBuilder()
    }

    private val blocks = HashMap<Int, Block>()
    private var inputTokens = 0
    private var outputTokens = 0
    private var sawUsage = false
    private var stopReason: String? = null
    private var stopped = false
    private var toolCalls = 0

    fun onEvent(type: String?, data: String): List<LlmEvent> {
        val event = parseJsonOrNull(data) as? JsonObject
            ?: throw errors.stream("malformed stream event", retryable = false)
        val out = mutableListOf<LlmEvent>()
        when (event.str("type") ?: type) {
            "message_start" -> event.obj("message")?.obj("usage")?.let(::readUsage)
            "content_block_start" -> {
                val index = event.int("index") ?: 0
                val block = event.obj("content_block") ?: return out
                val blockType = block.str("type").orEmpty()
                blocks[index] = Block(blockType, block.str("id"), block.str("name"))
                when (blockType) {
                    "text" -> block.str("text")?.takeIf { it.isNotEmpty() }?.let { out += LlmEvent.TextDelta(it) }
                    "thinking" -> block.str("thinking")?.takeIf { it.isNotEmpty() }?.let { out += LlmEvent.ReasoningDelta(it) }
                }
            }
            "content_block_delta" -> {
                val delta = event.obj("delta") ?: return out
                when (delta.str("type")) {
                    "text_delta" -> delta.str("text")?.takeIf { it.isNotEmpty() }?.let { out += LlmEvent.TextDelta(it) }
                    "thinking_delta" -> delta.str("thinking")?.takeIf { it.isNotEmpty() }?.let { out += LlmEvent.ReasoningDelta(it) }
                    "input_json_delta" -> blocks[event.int("index") ?: 0]?.input?.append(delta.str("partial_json").orEmpty())
                }
            }
            "content_block_stop" -> {
                val block = blocks.remove(event.int("index") ?: 0)
                if (block != null && block.type == "tool_use") {
                    toolCalls++
                    out += LlmEvent.ToolCallReady(
                        ToolCall(block.id.orEmpty(), block.name.orEmpty(), normalizeArguments(block.input.toString())),
                    )
                }
            }
            "message_delta" -> {
                event.obj("delta")?.str("stop_reason")?.let { stopReason = it }
                event.obj("usage")?.let(::readUsage)
            }
            "message_stop" -> stopped = true
            "error" -> {
                val error = event.obj("error")
                val retryable = error?.str("type") in RETRYABLE_ERRORS
                throw errors.stream(error?.str("message") ?: "stream error", retryable)
            }
        }
        return out
    }

    fun finish(): List<LlmEvent> {
        if (!stopped) throw errors.stream("stream ended before the response completed", retryable = true)
        val out = mutableListOf<LlmEvent>()
        if (sawUsage) out += LlmEvent.Usage(inputTokens, outputTokens)
        val reason = when (stopReason) {
            "tool_use" -> FinishReason.ToolCalls
            "max_tokens", "model_context_window_exceeded" -> FinishReason.Length
            else -> if (toolCalls > 0) FinishReason.ToolCalls else FinishReason.Stop
        }
        out += LlmEvent.Finished(reason)
        return out
    }

    private fun readUsage(usage: JsonObject) {
        val keys = listOf("input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens")
        if (keys.any { usage.containsKey(it) }) {
            inputTokens = maxOf(inputTokens, keys.sumOf { usage.int(it) ?: 0 })
            sawUsage = true
        }
        usage.int("output_tokens")?.let {
            outputTokens = maxOf(outputTokens, it)
            sawUsage = true
        }
    }

    private companion object {
        val RETRYABLE_ERRORS = setOf("overloaded_error", "api_error", "rate_limit_error", "timeout_error")
    }
}
