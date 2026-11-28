package atom.core.agent

import atom.core.llm.ChatMessage
import atom.core.llm.ContentPart
import atom.core.llm.FinishReason
import atom.core.llm.LlmEngine
import atom.core.llm.LlmEvent
import atom.core.llm.LlmException
import atom.core.llm.LlmRequest
import atom.core.llm.ReasoningEffort
import atom.core.llm.Role

/**
 * Context compaction: the oldest part of a conversation is replaced (in what the model sees) by a summary the
 * model writes itself. The stored conversation keeps everything, and `search_history` can still reach it.
 */
object Compactor {
    const val SUMMARY_HEADER = "[Summary of the earlier conversation. The full record can be searched with search_history.]"

    data class Result(val summary: String, val stats: ModelCallStats)

    /**
     * Index of the first message kept verbatim, or 0 when there is nothing worth compacting. The kept tail starts on
     * an exchange boundary (never between a tool call and its result) and holds roughly [keepRecentTokens].
     */
    fun cutIndex(convo: List<ChatMessage>, keepRecentTokens: Int): Int {
        val starts = groupStarts(convo)
        if (starts.size < 2) return 0
        var kept = 0
        var cut = convo.size
        for (g in starts.indices.reversed()) {
            val start = starts[g]
            val end = if (g + 1 < starts.size) starts[g + 1] else convo.size
            val size = (start until end).sumOf { ContextPacker.estimate(convo[it]) }
            if (cut != convo.size && kept + size > keepRecentTokens) break
            kept += size
            cut = start
        }
        return if (cut <= 1) 0 else cut
    }

    fun isSummary(message: ChatMessage): Boolean = message.role == Role.User && message.text.startsWith(SUMMARY_HEADER)

    fun summaryMessage(summary: String): ChatMessage = ChatMessage.user(SUMMARY_HEADER + "\n" + summary.trim())

    /** The model call that writes the summary. History goes in as plain text so any engine accepts it. */
    fun request(
        model: String,
        prefix: List<ChatMessage>,
        sensitive: (Int) -> Boolean = { false },
        maxOutputTokens: Int = 1_200,
        transcriptBudgetChars: Int = 120_000,
        reasoning: ReasoningEffort? = null,
        toolResultChars: Int = TOOL_RESULT_CHARS,
        argumentChars: Int = ARGUMENT_CHARS,
    ): LlmRequest = LlmRequest(
        model = model,
        messages = listOf(
            ChatMessage.system(INSTRUCTIONS),
            ChatMessage.user(transcript(prefix, sensitive, transcriptBudgetChars, toolResultChars, argumentChars) + "\n\nWrite the summary now."),
        ),
        maxOutputTokens = maxOutputTokens,
        reasoning = reasoning,
    )

    /**
     * Writes the summary. A reply with no text (a thinking model that spent the room reasoning, or a provider hiccup)
     * gets one more try, with thinking off and twice the room, before it counts as a failure.
     */
    suspend fun summarize(engine: LlmEngine, request: LlmRequest): Result = try {
        summarizeOnce(engine, request)
    } catch (e: EmptySummaryException) {
        summarizeOnce(engine, request.copy(reasoning = ReasoningEffort.Off, maxOutputTokens = request.maxOutputTokens * 2))
    }

    private class EmptySummaryException : LlmException("The model returned an empty summary", retryable = false)

    private suspend fun summarizeOnce(engine: LlmEngine, request: LlmRequest): Result {
        val started = System.nanoTime()
        var firstToken: Long? = null
        val text = StringBuilder()
        var input = 0
        var output = 0
        var finish = FinishReason.Stop
        engine.stream(request).collect { event ->
            when (event) {
                is LlmEvent.TextDelta -> {
                    if (firstToken == null) firstToken = System.nanoTime()
                    text.append(event.text)
                }
                is LlmEvent.Usage -> {
                    input = event.inputTokens
                    output = event.outputTokens
                }
                is LlmEvent.Finished -> finish = event.reason
                else -> Unit
            }
        }
        val summary = stripThinking(text.toString()).trim()
        if (summary.isEmpty()) throw EmptySummaryException()
        if (finish == FinishReason.Cancelled) throw LlmException("Summary was cancelled", retryable = false)
        val now = System.nanoTime()
        return Result(
            summary,
            ModelCallStats(
                ttftMs = firstToken?.let { (it - started) / 1_000_000 },
                durationMs = (now - started) / 1_000_000,
                inputTokens = input.takeIf { it > 0 } ?: request.messages.sumOf { ContextPacker.estimate(it) },
                outputTokens = output.takeIf { it > 0 } ?: ContextPacker.estimate(summary),
            ),
        )
    }

    fun transcript(
        messages: List<ChatMessage>,
        sensitive: (Int) -> Boolean,
        budgetChars: Int,
        toolResultChars: Int = TOOL_RESULT_CHARS,
        argumentChars: Int = ARGUMENT_CHARS,
    ): String {
        val lines = messages.mapIndexed { i, msg ->
            when (msg.role) {
                Role.System -> ""
                Role.User -> if (isSummary(msg)) "Earlier summary:\n" + msg.text.removePrefix(SUMMARY_HEADER).trim() else "User: " + msg.text
                Role.Assistant -> buildString {
                    append("Atom: ").append(msg.text)
                    msg.toolCalls.forEach { append("\n  -> called ").append(it.name).append(' ').append(it.argumentsJson.take(argumentChars)) }
                }
                Role.Tool -> {
                    val body = if (sensitive(i) || Scrubber.isScrubbed(msg)) "[sensitive result omitted]" else msg.text.take(toolResultChars)
                    val images = msg.parts.count { it is ContentPart.Image }
                    "  <- ${msg.toolName ?: "tool"} result: $body" + if (images > 0) " [$images image(s)]" else ""
                }
            }
        }.filter { it.isNotEmpty() }
        // Keep the start (the original request) and as much of the most recent part as fits.
        val total = lines.sumOf { it.length + 1 }
        if (total <= budgetChars) return lines.joinToString("\n")
        val head = lines.first()
        val tail = ArrayDeque<String>()
        var used = head.length
        for (line in lines.drop(1).asReversed()) {
            if (used + line.length + 1 > budgetChars) break
            tail.addFirst(line)
            used += line.length + 1
        }
        return head + "\n[... older part of the transcript omitted ...]\n" + tail.joinToString("\n")
    }

    internal fun stripThinking(text: String): String = text.replace(THINK, "")

    private fun groupStarts(convo: List<ChatMessage>): List<Int> =
        convo.indices.filter { i -> convo[i].role != Role.Tool || i == 0 }

    private val THINK = Regex("(?s)<think>.*?</think>")
    /** What one tool result and one call's arguments keep in the transcript; big cloud windows keep more. */
    const val TOOL_RESULT_CHARS = 1_500
    const val ARGUMENT_CHARS = 400

    private val INSTRUCTIONS = """
        You compact a conversation between a user and Atom, an assistant that operates the user's Android phone, so the work can continue with less context.
        Write a concise, factual summary that preserves:
        - the user's goals and requests, with exact requirements, names, numbers and wording that matter;
        - decisions made and facts learned;
        - what has been done so far and the outcome of each action (done, failed, or unconfirmed);
        - the current state of the task and of the phone;
        - open questions and the next steps.
        Never include passwords, one-time codes or other secrets. Write in the language the user writes in. Reply with the summary only.
    """.trimIndent()
}
