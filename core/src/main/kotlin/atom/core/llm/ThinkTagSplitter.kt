package atom.core.llm

/**
 * Splits raw model text into answer and reasoning for engines that see the model's own tags (llama.cpp): what a
 * thinking model (Qwen3, DeepSeek-R1 style) writes between `<think>` and `</think>` becomes [LlmEvent.ReasoningDelta],
 * the rest [LlmEvent.TextDelta]. Chat-format control markers that slip into the text (`<|im_end|>`, `<|endoftext|>`,
 * `<|eot_id|>`...) are dropped. A tag split across chunks is held back until it is complete, so neither a partial
 * `<thi` nor a partial `<|im_` is ever shown. Tool-call tags (`<tool_call>`) pass through for [PromptedToolEngine].
 */
class ThinkTagSplitter {
    private val buffer = StringBuilder()
    private var thinking = false
    private var answered = false

    fun feed(chunk: String): List<LlmEvent> {
        buffer.append(chunk)
        val out = mutableListOf<LlmEvent>()
        while (buffer.isNotEmpty()) {
            val tag = if (thinking) CLOSE else OPEN
            val at = buffer.indexOf(tag)
            val marker = MARKER.find(buffer)?.takeIf { at < 0 || it.range.first < at }
            when {
                marker != null -> {
                    emit(buffer.substring(0, marker.range.first), out)
                    buffer.delete(0, marker.range.last + 1)
                }
                at >= 0 -> {
                    emit(buffer.substring(0, at), out)
                    buffer.delete(0, at + tag.length)
                    thinking = !thinking
                    // "</think>\n\n" — the blank lines after reasoning are not part of the answer.
                    if (!thinking) skipLeadingBlank = true
                }
                else -> {
                    val keep = heldBack(tag)
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

    /** Length of a suffix that may be the start of [tag] or of a control marker, which must wait for more text. */
    private fun heldBack(tag: String): Int {
        val markerStart = buffer.lastIndexOf("<|")
        if (markerStart >= 0 && buffer.indexOf("|>", markerStart) < 0 && buffer.length - markerStart <= MAX_MARKER) {
            return buffer.length - markerStart
        }
        for (len in minOf(tag.length - 1, buffer.length) downTo 1) {
            if (tag.startsWith(buffer.substring(buffer.length - len))) return len
        }
        return 0
    }

    private companion object {
        const val OPEN = "<think>"
        const val CLOSE = "</think>"
        const val MAX_MARKER = 32
        val MARKER = Regex("""<\|[A-Za-z0-9_.:\-]{1,28}\|>""")
    }
}
