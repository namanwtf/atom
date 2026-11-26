package atom.core.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Spots a model that has stopped producing language while it streams, such as the "#11#22#2#1#111#11111" stream a
 * tiny on-device model produced: text made of only a handful of symbols, or one piece repeated until hardly anything
 * new is said. Small on-device models fall into such loops and would otherwise fill the answer to the output limit.
 *
 * Legitimate repetitive output is left alone: code fences, `<tool_call>` blocks and Markdown table rows are not
 * judged at all; data words (a binary string, base64 such as "AAAA…", hex) and number lists ("{0, 0, 0, …}") are
 * skipped unless absurdly long. The rest is judged over long windows only: [WINDOW] characters with at most four
 * distinct symbols, or [LONG] characters whose share of new six-character pieces is below [MIN_NOVELTY]. Runs of
 * whitespace count as one space, and only the last [MAX_TAIL] characters are kept, so the cost per chunk is bounded.
 */
class DegenerateOutputDetector {
    private val tail = StringBuilder()
    private val pending = StringBuilder()
    private var inFence = false
    private var inCall = false
    private var inTable = false
    private var lineStart = true
    private var lastWasSpace = false

    /** True once the text fed so far ends in a runaway loop. */
    var tripped = false
        private set

    /** Adds [chunk]; returns [tripped]. */
    fun feed(chunk: String): Boolean {
        if (tripped) return true
        for (c in chunk) raw(c)
        tripped = judge()
        return tripped
    }

    /** Recognises fence and tool-call markers, which may arrive split across chunks. */
    private fun raw(c: Char) {
        pending.append(c)
        while (pending.isNotEmpty()) {
            val p = pending.toString()
            val markers = when {
                inCall -> CALL_MARKERS_INSIDE
                inFence -> FENCE_MARKERS
                else -> OUTSIDE_MARKERS
            }
            val done = markers.firstOrNull { it == p }
            if (done != null) {
                when (done) {
                    FENCE -> inFence = !inFence
                    CALL_OPEN -> inCall = true
                    CALL_CLOSE -> inCall = false
                }
                pending.clear()
                lineStart = false
                return
            }
            if (markers.any { it.startsWith(p) }) return
            val first = pending[0]
            pending.deleteCharAt(0)
            text(first)
        }
    }

    private fun text(c: Char) {
        if (inFence || inCall) return
        if (c == '\n') {
            lineStart = true
            inTable = false
            add(' ')
            return
        }
        if (lineStart) {
            if (c == ' ' || c == '\t') return
            lineStart = false
            if (c == '|') inTable = true
        }
        if (inTable) return
        add(if (c.isWhitespace()) ' ' else c)
    }

    private fun add(ch: Char) {
        if (ch == ' ') {
            if (lastWasSpace) return
            lastWasSpace = true
        } else {
            lastWasSpace = false
        }
        tail.append(ch)
        if (tail.length > MAX_TAIL) tail.delete(0, tail.length - MAX_TAIL)
    }

    private fun judge(): Boolean {
        // Words split at spaces and list punctuation, so "base64,AAAA…" and "{0, 0, 0}" yield their data parts.
        val judged = tail.split(' ', ',', ';', ':').filter { it.isNotEmpty() && !exempt(it) }.joinToString(" ")
        if (judged.length >= WINDOW) {
            val w = judged.takeLast(WINDOW)
            if (mostlyLettersOrDigits(w) && w.filter { it != ' ' }.toSet().size <= MAX_SYMBOLS) return true
        }
        if (judged.length >= LONG) {
            val w = judged.takeLast(LONG)
            val pieces = (0..w.length - PIECE).map { w.substring(it, it + PIECE) }.toSet().size
            if (mostlyLettersOrDigits(w) && pieces.toDouble() / (w.length - PIECE + 1) < MIN_NOVELTY) return true
        }
        return false
    }

    /** A data word or a number list: skipped unless longer than [MAX_DATA_WORD]. */
    private fun exempt(word: String): Boolean =
        word.length <= MAX_DATA_WORD && ((word.length >= MIN_DATA_WORD && DATA_WORD.matches(word)) || NUMBERS.matches(word))

    /**
     * At least a quarter of the non-space characters are letters or digits, so rules, borders and emoji ("-----",
     * "=====", a row of 😂) never count as a loop.
     */
    private fun mostlyLettersOrDigits(w: String): Boolean {
        var alnum = 0
        var other = 0
        for (c in w) {
            if (c == ' ') continue
            if (c.isLetterOrDigit()) alnum++ else other++
        }
        return alnum > 0 && alnum * 4 >= alnum + other
    }

    companion object {
        const val WINDOW = 96
        const val LONG = 240
        const val MAX_SYMBOLS = 4
        const val PIECE = 6
        const val MIN_NOVELTY = 0.25
        const val MAX_TAIL = 1_024
        const val MIN_DATA_WORD = 16
        const val MAX_DATA_WORD = 512

        private const val FENCE = "```"
        private const val CALL_OPEN = "<tool_call>"
        private const val CALL_CLOSE = "</tool_call>"
        private val OUTSIDE_MARKERS = listOf(FENCE, CALL_OPEN)
        private val FENCE_MARKERS = listOf(FENCE)
        private val CALL_MARKERS_INSIDE = listOf(CALL_CLOSE)
        private val DATA_WORD = Regex("[A-Za-z0-9+/=_-]+")
        private val NUMBERS = Regex("[0-9.,;:+\\-(){}\\[\\]]+")
    }
}

/**
 * Stops a generation that has turned into a loop ([DegenerateOutputDetector]) and fails it with
 * [LlmFailure.Unreadable], so the user sees a clear message instead of a screen of repeated symbols. Collecting
 * stops at once, which cancels the engine's native decode. Text and reasoning are watched separately.
 */
class DegenerateOutputGuard(private val inner: LlmEngine) : LlmEngine {
    override val id: String get() = inner.id
    override val capabilities: EngineCapabilities get() = inner.capabilities

    override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
        val text = DegenerateOutputDetector()
        val reasoning = DegenerateOutputDetector()
        inner.stream(request).collect { event ->
            val looping = when (event) {
                is LlmEvent.TextDelta -> text.feed(event.text)
                is LlmEvent.ReasoningDelta -> reasoning.feed(event.text)
                else -> false
            }
            if (looping) throw LlmException(UNREADABLE_MESSAGE, retryable = false)
            emit(event)
        }
    }

    companion object {
        const val UNREADABLE_MESSAGE = "This model produced unreadable output. Try again or pick a larger model."
    }
}
