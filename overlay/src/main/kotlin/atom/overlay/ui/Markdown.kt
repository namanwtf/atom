package atom.overlay.ui

/** A small, streaming-tolerant Markdown reader for overlay replies. Unclosed markers stay literal; an unclosed fence runs to the end. */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class ListItem(val marker: String, val indent: Int, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data object Rule : MdBlock
}

data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
    val link: String? = null,
)

object Markdown {
    private val heading = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val rule = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val bullet = Regex("^(\\s*)([-*+])\\s+(.*)$")
    private val ordered = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")
    private val tableDivider = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")
    private const val ESCAPABLE = "\\`*_{}[]()#+-.!~|>"

    fun blocks(source: String): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        val paragraph = StringBuilder()
        val quote = StringBuilder()
        val table = StringBuilder()

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) out += MdBlock.Paragraph(paragraph.toString())
            paragraph.clear()
        }
        fun flushQuote() {
            if (quote.isNotEmpty()) out += MdBlock.Quote(quote.toString())
            quote.clear()
        }
        fun flushTable() {
            if (table.isNotEmpty()) out += MdBlock.Code("table", table.toString())
            table.clear()
        }
        fun flushAll() {
            flushParagraph()
            flushQuote()
            flushTable()
        }

        val lines = source.replace("\r\n", "\n").split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                flushAll()
                val fence = trimmed.take(3)
                val language = trimmed.drop(3).trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(fence)) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    i++
                }
                out += MdBlock.Code(language, code.toString())
                i++
                continue
            }
            when {
                line.isBlank() -> flushAll()
                trimmed.startsWith("|") -> {
                    flushParagraph()
                    flushQuote()
                    if (!tableDivider.matches(line)) {
                        if (table.isNotEmpty()) table.append('\n')
                        table.append(trimmed.trim())
                    }
                }
                heading.matches(line) -> {
                    flushAll()
                    val m = heading.find(line)!!
                    out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2])
                }
                rule.matches(line) -> {
                    flushAll()
                    out += MdBlock.Rule
                }
                trimmed.startsWith(">") -> {
                    flushParagraph()
                    flushTable()
                    if (quote.isNotEmpty()) quote.append('\n')
                    quote.append(trimmed.removePrefix(">").removePrefix(" "))
                }
                bullet.matches(line) -> {
                    flushAll()
                    val m = bullet.find(line)!!
                    out += MdBlock.ListItem("•", indentOf(m.groupValues[1]), m.groupValues[3])
                }
                ordered.matches(line) -> {
                    flushAll()
                    val m = ordered.find(line)!!
                    out += MdBlock.ListItem(m.groupValues[2] + ".", indentOf(m.groupValues[1]), m.groupValues[3])
                }
                else -> {
                    flushQuote()
                    flushTable()
                    if (paragraph.isNotEmpty()) paragraph.append('\n')
                    paragraph.append(line.trim())
                }
            }
            i++
        }
        flushAll()
        return out
    }

    fun inline(text: String): List<MdSpan> {
        val out = mutableListOf<MdSpan>()
        parse(text, MdSpan(""), out)
        return merge(out)
    }

    /** The text without markup, for previews and reading aloud. */
    fun plain(source: String): String = blocks(source).joinToString("\n") { block ->
        when (block) {
            is MdBlock.Heading -> spansText(block.text)
            is MdBlock.Paragraph -> spansText(block.text)
            is MdBlock.ListItem -> spansText(block.text)
            is MdBlock.Quote -> spansText(block.text)
            is MdBlock.Code -> block.code
            MdBlock.Rule -> ""
        }
    }.trim()

    private fun spansText(text: String) = inline(text).joinToString("") { it.text }

    private fun indentOf(lead: String): Int = lead.replace("\t", "    ").length / 2

    private fun parse(s: String, style: MdSpan, out: MutableList<MdSpan>) {
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotEmpty()) out += style.copy(text = buf.toString())
            buf.clear()
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length && s[i + 1] in ESCAPABLE -> {
                    buf.append(s[i + 1])
                    i += 2
                }
                c == '`' -> {
                    val end = s.indexOf('`', i + 1)
                    if (end > i + 1) {
                        flush()
                        out += style.copy(text = s.substring(i + 1, end), code = true)
                        i = end + 1
                    } else {
                        buf.append(c)
                        i++
                    }
                }
                s.startsWith("***", i) && s.indexOf("***", i + 3) > i + 3 -> {
                    flush()
                    val end = s.indexOf("***", i + 3)
                    parse(s.substring(i + 3, end), style.copy(bold = true, italic = true), out)
                    i = end + 3
                }
                s.startsWith("**", i) || s.startsWith("__", i) || s.startsWith("~~", i) -> {
                    val marker = s.substring(i, i + 2)
                    var end = s.indexOf(marker, i + 2)
                    // "**b *c***": the italic closes first, so the bold closes on the last two markers.
                    while (end > 0 && marker != "~~" && end + 2 < s.length && s[end + 2] == marker[0]) end++
                    if (end > i + 2) {
                        flush()
                        val inner = s.substring(i + 2, end)
                        parse(inner, if (marker == "~~") style.copy(strike = true) else style.copy(bold = true), out)
                        i = end + 2
                    } else {
                        buf.append(marker)
                        i += 2
                    }
                }
                c == '*' || c == '_' -> {
                    val opens = i + 1 < s.length && !s[i + 1].isWhitespace() && (c == '*' || i == 0 || !s[i - 1].isLetterOrDigit())
                    val end = if (opens) closingSingle(s, c, i + 1) else -1
                    if (end > i + 1) {
                        flush()
                        parse(s.substring(i + 1, end), style.copy(italic = true), out)
                        i = end + 1
                    } else {
                        buf.append(c)
                        i++
                    }
                }
                c == '[' -> {
                    val close = s.indexOf("](", i + 1)
                    val paren = if (close > i) s.indexOf(')', close + 2) else -1
                    if (close > i + 1 && paren > close + 2) {
                        flush()
                        parse(s.substring(i + 1, close), style.copy(link = s.substring(close + 2, paren).trim()), out)
                        i = paren + 1
                    } else {
                        buf.append(c)
                        i++
                    }
                }
                else -> {
                    buf.append(c)
                    i++
                }
            }
        }
        flush()
    }

    private fun closingSingle(s: String, marker: Char, from: Int): Int {
        var j = from
        while (j < s.length) {
            if (s[j] == marker && !s[j - 1].isWhitespace()) {
                val doubled = j + 1 < s.length && s[j + 1] == marker
                val wordAfter = marker == '_' && j + 1 < s.length && s[j + 1].isLetterOrDigit()
                if (!doubled && !wordAfter) return j
                if (doubled) j++
            }
            j++
        }
        return -1
    }

    private fun merge(spans: List<MdSpan>): List<MdSpan> {
        val out = mutableListOf<MdSpan>()
        for (span in spans) {
            val last = out.lastOrNull()
            if (last != null && last.copy(text = "") == span.copy(text = "")) {
                out[out.lastIndex] = last.copy(text = last.text + span.text)
            } else {
                out += span
            }
        }
        return out
    }
}
