package atom.core.llm

/**
 * Splits raw model text into answer and reasoning for engines that see the model's own tags (llama.cpp, and the text
 * LiteRT-LM streams): what a thinking model (Qwen3, DeepSeek-R1 style) writes between `<think>` and `</think>` becomes
 * [LlmEvent.ReasoningDelta], the rest [LlmEvent.TextDelta]. Chat-format control markers that slip into the text
 * (`<|im_end|>`, `<|endoftext|>`, `<|eot_id|>`...) are dropped. A tag split across chunks is held back until it is
 * complete, so neither a partial `<thi` nor a partial `<|im_` is ever shown. Tool-call tags (`<tool_call>`) pass
 * through for [PromptedToolEngine].
 *
 * Stray tags are dropped too: a `</think>` with no reasoning open, and a second `<think>` inside reasoning. Qwen3 with
 * thinking off gets an empty `<think>\n\n</think>` from its chat template, and a small model then often writes
 * `</think>` again, at the start of its answer or later. When the template itself opens the block (the prompt ends
 * in `<think>`, see [endsInsideThinking]), pass [startsInThinking] so the text up to the first `</think>` is reasoning.
 */
class ThinkTagSplitter(startsInThinking: Boolean = false) {
    private val buffer = StringBuilder()
    private var thinking = startsInThinking
    private var answered = false

    fun feed(chunk: String): List<LlmEvent> {
        buffer.append(chunk)
        val out = mutableListOf<LlmEvent>()
        while (buffer.isNotEmpty()) {
            val open = buffer.indexOf(OPEN)
            val close = buffer.indexOf(CLOSE)
            val at = if (open < 0 || (close in 0 until open)) close else open
            val marker = MARKER.find(buffer)?.takeIf { at < 0 || it.range.first < at }
            when {
                marker != null -> {
                    emit(buffer.substring(0, marker.range.first), out)
                    buffer.delete(0, marker.range.last + 1)
                }
                at >= 0 -> {
                    val opening = at == open
                    emit(buffer.substring(0, at), out)
                    buffer.delete(0, at + if (opening) OPEN.length else CLOSE.length)
                    if (opening && !thinking) {
                        thinking = true
                    } else if (!opening && thinking) {
                        thinking = false
                        // "</think>\n\n" — the blank lines after reasoning are not part of the answer.
                        skipLeadingBlank = true
                    }
                    // Otherwise a stray tag (a close with nothing open, an open inside reasoning): dropped.
                }
                else -> {
                    val keep = heldBack()
                    emit(buffer.substring(0, buffer.length - keep), out)
                    buffer.delete(0, buffer.length - keep)
                    return out
                }
            }
        }
        return out
    }

    /** Whatever is still held back (an unfinished tag at the very end is shown as it is). */
    fun finish(): List<LlmEvent> {
        val out = mutableListOf<LlmEvent>()
        emit(buffer.toString(), out)
        buffer.clear()
        return out
    }

    private var skipLeadingBlank = false

    private fun emit(text: String, out: MutableList<LlmEvent>) {
        var t = text
        if (t.isEmpty()) return
        if (thinking) {
            out += LlmEvent.ReasoningDelta(t)
            return
        }
        if (skipLeadingBlank || !answered) {
            t = t.trimStart('\n', '\r')
            if (t.isEmpty()) return
            skipLeadingBlank = false
        }
        answered = true
        out += LlmEvent.TextDelta(t)
    }

    /** Length of a suffix that may be the start of a think tag or of a control marker, which must wait for more text. */
    private fun heldBack(): Int {
        val markerStart = buffer.lastIndexOf("<|")
        if (markerStart >= 0 && buffer.indexOf("|>", markerStart) < 0 && buffer.length - markerStart <= MAX_MARKER) {
            return buffer.length - markerStart
        }
        for (len in minOf(CLOSE.length - 1, buffer.length) downTo 1) {
            val tail = buffer.substring(buffer.length - len)
            if (OPEN.startsWith(tail) || CLOSE.startsWith(tail)) return len
        }
        return 0
    }

    companion object {
        private const val OPEN = "<think>"
        private const val CLOSE = "</think>"
        private const val MAX_MARKER = 32
        private val MARKER = Regex("""<\|[A-Za-z0-9_.:\-]{1,28}\|>""")

        /**
         * True when a formatted [prompt] ends inside an open reasoning block: its chat template wrote `<think>` (and
         * maybe a newline) as the start of the answer, so the model's output begins inside the reasoning.
         */
        fun endsInsideThinking(prompt: String): Boolean {
            val open = prompt.lastIndexOf(OPEN)
            return open >= 0 && prompt.substring(open + OPEN.length).isBlank()
        }
    }
}
