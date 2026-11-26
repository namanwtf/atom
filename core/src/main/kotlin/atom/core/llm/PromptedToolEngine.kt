package atom.core.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Gives tool use to engines without native function calling (most on-device models): tools are described
 * in the system prompt and calls are parsed out of `<tool_call>{...}</tool_call>` blocks while streaming.
 */
class PromptedToolEngine(private val inner: LlmEngine) : LlmEngine {
    override val id: String get() = inner.id
    override val capabilities: EngineCapabilities get() = inner.capabilities.copy(nativeToolCalls = true)

    override fun stream(request: LlmRequest): Flow<LlmEvent> {
        if (request.tools.isEmpty()) {
            // A request without tools (the step-limit wrap-up) can still carry earlier calls the inner engine
            // cannot read natively.
            return inner.stream(if (hasToolTurns(request.messages)) request.copy(messages = asPlainText(request.messages)) else request)
        }
        val rewritten = request.copy(messages = rewrite(request.messages, request.tools), tools = emptyList())
        return flow {
            val scanner = ToolCallScanner()
            var finish = FinishReason.Stop
            inner.stream(rewritten).collect { event ->
                when (event) {
                    is LlmEvent.TextDelta -> scanner.feed(event.text).forEach { emit(it) }
                    is LlmEvent.Finished -> finish = event.reason
                    else -> emit(event)
                }
            }
            scanner.finish().forEach { emit(it) }
            emit(LlmEvent.Finished(if (scanner.callCount > 0) FinishReason.ToolCalls else finish))
        }
    }

    private fun rewrite(messages: List<ChatMessage>, tools: List<ToolSpec>): List<ChatMessage> {
        val instructions = toolInstructions(tools)
        val out = mutableListOf<ChatMessage>()
        var hasSystem = false
        for (msg in asPlainText(messages)) {
            out += if (msg.role == Role.System) {
                hasSystem = true
                ChatMessage.system(msg.text + "\n\n" + instructions)
            } else {
                msg
            }
        }
        if (!hasSystem) out.add(0, ChatMessage.system(instructions))
        return out
    }

    companion object {
        fun hasToolTurns(messages: List<ChatMessage>): Boolean = messages.any { it.role == Role.Tool || it.toolCalls.isNotEmpty() }

        /**
         * The conversation with tool calls and results written out as `<tool_call>` / `<tool_result>` text, for a
         * request that declares no tools. Other messages are unchanged.
         */
        fun asPlainText(messages: List<ChatMessage>): List<ChatMessage> = messages.map { msg ->
            when (msg.role) {
                Role.Assistant -> if (msg.toolCalls.isEmpty()) msg else ChatMessage.assistant(
                    msg.text + msg.toolCalls.joinToString("") { "\n<tool_call>" + encodeCall(it) + "</tool_call>" },
                )
                Role.Tool -> ChatMessage(
                    Role.User,
                    listOf(ContentPart.Text("<tool_result name=\"${msg.toolName}\">\n${msg.text}\n</tool_result>")) +
                        msg.parts.filterIsInstance<ContentPart.Image>(),
                )
                else -> msg
            }
        }

        private fun encodeCall(call: ToolCall): String {
            val args = runCatching { Json.parseToJsonElement(call.argumentsJson) }.getOrElse { JsonObject(emptyMap()) }
            return JsonObject(mapOf("name" to JsonPrimitive(call.name), "arguments" to args)).toString()
        }
    }

    private fun toolInstructions(tools: List<ToolSpec>): String = buildString {
        appendLine("# Tools")
        appendLine("To use a tool, reply with one or more blocks exactly like:")
        appendLine("<tool_call>{\"name\": \"tool_name\", \"arguments\": {\"arg\": \"value\"}}</tool_call>")
        appendLine("Then stop and wait; the result arrives in a <tool_result> message. Never invent results.")
        appendLine("When the task is done, answer normally without any <tool_call>.")
        appendLine()
        for (tool in tools) {
            appendLine("## ${tool.name}")
            appendLine(tool.description)
            appendLine("Arguments schema: ${tool.parameters}")
            appendLine()
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

    fun feed(chunk: String): List<LlmEvent> {
        buffer.append(chunk)
        val out = mutableListOf<LlmEvent>()
        while (true) {
            if (!inCall) {
                val start = buffer.indexOf(OPEN)
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
        for (len in minOf(OPEN.length - 1, buffer.length) downTo 1) {
            if (OPEN.startsWith(buffer.substring(buffer.length - len))) return len
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
    }
}
