package atom.core.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Gives tool use to engines without native function calling (most on-device models): tools are described
 * in the system prompt and calls are parsed out of `<tool_call>{...}</tool_call>` blocks while streaming.
 */
class PromptedToolEngine(private val inner: LlmEngine) : LlmEngine {
    override val id: String get() = inner.id
    override val capabilities: EngineCapabilities get() = inner.capabilities.copy(nativeToolCalls = true)

    override fun stream(request: LlmRequest): Flow<LlmEvent> {
        if (request.tools.isEmpty()) return inner.stream(request)
        val rewritten = request.copy(messages = rewrite(request.messages, request.tools), tools = emptyList())
        return flow {
            val scanner = ToolCallScanner()
            var finish = FinishReason.Stop
            // Small models often go on to write the tool's result themselves after a call; generation stops there,
            // so the invented result is neither shown nor kept.
            inner.stream(rewritten).transformWhile { event ->
                when (event) {
                    is LlmEvent.TextDelta -> scanner.feed(event.text).forEach { emit(it) }
                    is LlmEvent.Finished -> finish = event.reason
                    else -> emit(event)
                }
                !scanner.inventingResult
            }.collect { emit(it) }
            scanner.finish().forEach { emit(it) }
            emit(LlmEvent.Finished(if (scanner.callCount > 0) FinishReason.ToolCalls else finish))
        }
    }

    private fun rewrite(messages: List<ChatMessage>, tools: List<ToolSpec>): List<ChatMessage> {
        val instructions = toolInstructions(tools)
        val out = mutableListOf<ChatMessage>()
        var hasSystem = false
        for (msg in messages) {
            out += when (msg.role) {
                Role.System -> {
                    hasSystem = true
                    ChatMessage.system(msg.text + "\n\n" + instructions)
                }
                Role.Assistant -> ChatMessage.assistant(
                    msg.text + msg.toolCalls.joinToString("") { "\n<tool_call>" + encodeCall(it) + "</tool_call>" },
                )
                Role.Tool -> ChatMessage(
                    Role.User,
                    listOf(ContentPart.Text("<tool_result name=\"${msg.toolName}\">\n${msg.text}\n</tool_result>")) +
                        msg.parts.filterIsInstance<ContentPart.Image>(),
                )
                Role.User -> msg
            }
        }
        if (!hasSystem) out.add(0, ChatMessage.system(instructions))
        return out
    }

    private fun encodeCall(call: ToolCall): String {
        val args = runCatching { Json.parseToJsonElement(call.argumentsJson) }.getOrElse { JsonObject(emptyMap()) }
        return JsonObject(mapOf("name" to JsonPrimitive(call.name), "arguments" to args)).toString()
    }

    private fun toolInstructions(tools: List<ToolSpec>): String =
        PromptedTools.instructions(tools, PromptedTools.compact(inner.capabilities.contextWindow))
}

/** How [PromptedToolEngine] writes the tools into the prompt; the agent measures the same text for its budget. */
object PromptedTools {
    /** Windows up to this size get the compact tool list. */
    const val COMPACT_WINDOW = 4_096

    /**
     * A model with a small window (Qwen3 0.6B: 4096 tokens) gets each tool's arguments as one short line instead of
     * the full JSON schema with every argument's description: the core tools plus the browser group otherwise take
     * about 3000 of its 4096 tokens before the task, the history or a page are in the prompt.
     */
    fun compact(contextWindow: Int): Boolean = contextWindow in 1..COMPACT_WINDOW

    fun instructions(tools: List<ToolSpec>, compact: Boolean): String = buildString {
        appendLine("# Tools")
        appendLine("To use a tool, reply with one or more blocks exactly like:")
        appendLine("<tool_call>{\"name\": \"tool_name\", \"arguments\": {\"arg\": \"value\"}}</tool_call>")
        appendLine("Then stop and wait; the result arrives in a <tool_result> message. Never invent results.")
        appendLine("When the task is done, answer normally without any <tool_call>.")
        appendLine()
        for (tool in tools) {
            appendLine("## ${tool.name}")
            appendLine(tool.description)
            if (compact) appendLine("Arguments: ${compactArguments(tool.parameters)}") else appendLine("Arguments schema: ${tool.parameters}")
            appendLine()
        }
    }

    /** `query (string, required), count (integer), mode (one of: a, b)`; "none" for a tool without arguments. */
    fun compactArguments(schema: JsonObject): String {
        val props = schema["properties"] as? JsonObject ?: return "none"
        if (props.isEmpty()) return "none"
        val required = (schema["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty().toSet()
        return props.entries.joinToString(", ") { (name, value) ->
            val prop = value as? JsonObject
            val type = (prop?.get("type") as? JsonPrimitive)?.contentOrNull ?: "any"
            val choices = (prop?.get("enum") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            val notes = listOfNotNull(
                choices?.takeIf { it.isNotEmpty() }?.let { "one of: " + it.joinToString(", ") } ?: type,
                "required".takeIf { name in required },
            )
            "$name (${notes.joinToString(", ")})"
        }
    }
}

/** Incremental parser that never shows a partial `<tool_call>` tag to the user. */
internal class ToolCallScanner {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }
    private val buffer = StringBuilder()
    private var inCall = false
    var callCount = 0
        private set

    /** True once the model, after a call, started writing a `<tool_result>` itself; everything from there is dropped. */
    var inventingResult = false
        private set

    fun feed(chunk: String): List<LlmEvent> {
        if (inventingResult) return emptyList()
        buffer.append(chunk)
        val out = mutableListOf<LlmEvent>()
        while (true) {
            if (!inCall) {
                val invented = if (callCount > 0) buffer.indexOf(RESULT) else -1
                val start = buffer.indexOf(OPEN)
                if (invented >= 0 && (start < 0 || invented < start)) {
                    if (invented > 0) out += LlmEvent.TextDelta(buffer.substring(0, invented))
                    buffer.clear()
                    inventingResult = true
                    return out
                }
                if (start >= 0) {
                    if (start > 0) out += LlmEvent.TextDelta(buffer.substring(0, start))
                    buffer.delete(0, start + OPEN.length)
                    inCall = true
                    continue
                }
                val safe = buffer.length - partialTagSuffix()
                if (safe > 0) {
                    out += LlmEvent.TextDelta(buffer.substring(0, safe))
                    buffer.delete(0, safe)
                }
                return out
            }
            val end = buffer.indexOf(CLOSE)
            if (end < 0) return out
            parse(buffer.substring(0, end))?.let { out += it }
            buffer.delete(0, end + CLOSE.length)
            inCall = false
        }
    }

    fun finish(): List<LlmEvent> {
        val rest = buffer.toString()
        buffer.clear()
        if (inCall) {
            inCall = false
            // Small models often forget the closing tag; accept a complete JSON object anyway.
            return listOfNotNull(parse(rest))
        }
        return if (rest.isEmpty()) emptyList() else listOf(LlmEvent.TextDelta(rest))
    }

    private fun partialTagSuffix(): Int {
        val tags = if (callCount > 0) listOf(OPEN, RESULT) else listOf(OPEN)
        for (len in minOf(maxOf(OPEN.length, RESULT.length) - 1, buffer.length) downTo 1) {
            val tail = buffer.substring(buffer.length - len)
            if (tags.any { it.length > len && it.startsWith(tail) }) return len
        }
        return 0
    }

    private fun parse(body: String): LlmEvent.ToolCallReady? {
        val obj = runCatching { json.parseToJsonElement(body.trim()).jsonObject }.getOrNull() ?: return null
        val name = (obj["name"] as? JsonPrimitive)?.content ?: return null
        val args = obj["arguments"] ?: obj["parameters"] ?: JsonObject(emptyMap())
        callCount++
        return LlmEvent.ToolCallReady(ToolCall(id = "call_$callCount", name = name, argumentsJson = args.toString()))
    }

    private companion object {
        const val OPEN = "<tool_call>"
        const val CLOSE = "</tool_call>"
        const val RESULT = "<tool_result"
    }
}
