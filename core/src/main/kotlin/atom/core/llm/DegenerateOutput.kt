package atom.core.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Spots a model that has stopped producing language while it streams: one character over and over ("1111111..."),
 * text made of only two or three symbols ("#11#22#2#1#111"), or one short piece repeated many times ("ok ok ok ...",
 * the same sentence again and again). Small on-device models fall into such loops and would otherwise fill the
 * answer until the output limit.
 *
 * It looks only at the last [WINDOW] characters, with runs of whitespace counted as one space, so its cost per
 * chunk does not grow with the answer. Ordinary prose, code, lists and Markdown tables do not trip it.
 */
class DegenerateOutputDetector {
    private val tail = StringBuilder()
    private var lastWasSpace = false
    private var alnumRun = 0
    private var otherRun = 0
    private var runChar = '\u0000'

    /** True once the text fed so far ends in a runaway loop. */
    var tripped = false
        private set

    /** Adds [chunk]; returns [tripped]. */
    fun feed(chunk: String): Boolean {
        if (tripped) return true
        for (c in chunk) {
            val ch = if (c.isWhitespace()) ' ' else c
            if (ch == ' ') {
                if (lastWasSpace) continue
                lastWasSpace = true
            } else {
                lastWasSpace = false
            }
            tail.append(ch)
            if (ch == runChar) {
                if (ch.isLetterOrDigit()) alnumRun++ else if (ch != ' ') otherRun++
            } else {
                runChar = ch
                alnumRun = if (ch.isLetterOrDigit()) 1 else 0
                otherRun = if (!ch.isLetterOrDigit() && ch != ' ') 1 else 0
            }
            if (alnumRun >= MAX_ALNUM_RUN || otherRun >= MAX_SYMBOL_RUN) {
                tripped = true
                return true
            }
        }
        if (tail.length > WINDOW) tail.delete(0, tail.length - WINDOW)
        tripped = fewSymbols() || repeatingUnit()
        return tripped
    }

    /** The last [FEW_SYMBOLS_SPAN] characters use at most three distinct non-space characters, mostly letters or digits. */
    private fun fewSymbols(): Boolean {
        if (tail.length < FEW_SYMBOLS_SPAN) return false
        val seen = HashSet<Char>()
        for (i in tail.length - FEW_SYMBOLS_SPAN until tail.length) {
            val c = tail[i]
            if (c == ' ') continue
            seen += c
            if (seen.size > 3) return false
        }
        return mostlyLettersOrDigits(tail.length - FEW_SYMBOLS_SPAN)
    }

    /** The tail is one unit of 1..[MAX_UNIT] characters repeated back to back over a span long enough to be a loop. */
    private fun repeatingUnit(): Boolean {
        val n = tail.length
        for (unit in 1..MAX_UNIT) {
            val span = maxOf(MIN_LOOP_SPAN, unit * MIN_REPEATS)
            if (span > n) break
            var ok = true
            for (i in n - span + unit until n) {
                if (tail[i] != tail[i - unit]) {
                    ok = false
                    break
                }
            }
            if (ok && mostlyLettersOrDigits(n - span)) return true
        }
        return false
    }

    /**
     * At least a quarter of the non-space characters from [from] to the end are letters or digits, so rules, table
     * borders and the like ("-----", "|---|---|", a word followed by "=====") never count as a loop.
     */
    private fun mostlyLettersOrDigits(from: Int): Boolean {
        var alnum = 0
        var other = 0
        for (i in from until tail.length) {
            val c = tail[i]
            if (c == ' ') continue
            if (c.isLetterOrDigit()) alnum++ else other++
        }
        return alnum > 0 && alnum * 4 >= alnum + other
    }

    companion object {
        const val WINDOW = 512
        const val MAX_UNIT = 80
        const val MIN_REPEATS = 5
        const val MIN_LOOP_SPAN = 60
        const val FEW_SYMBOLS_SPAN = 48
        const val MAX_ALNUM_RUN = 24
        const val MAX_SYMBOL_RUN = 120
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
