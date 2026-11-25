package atom.core.markdown

/** Chat-flavoured Markdown: GitHub-style blocks, callouts, tables, task lists and TeX math. */
sealed interface MdBlock {
    data class Heading(val level: Int, val inlines: List<MdInline>) : MdBlock
    data class Paragraph(val inlines: List<MdInline>) : MdBlock
    data class Code(val language: String?, val code: String, val closed: Boolean = true) : MdBlock
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data class Callout(val kind: CalloutKind, val title: String?, val blocks: List<MdBlock>) : MdBlock
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<ListItem>) : MdBlock
    data class Table(val header: List<List<MdInline>>, val align: List<Align>, val rows: List<List<List<MdInline>>>) : MdBlock
    data class Math(val tex: String) : MdBlock
    data object Rule : MdBlock
}

data class ListItem(val blocks: List<MdBlock>, val task: Boolean = false, val checked: Boolean = false)

enum class CalloutKind { Note, Tip, Important, Warning, Caution }

enum class Align { Start, Center, End }

sealed interface MdInline {
    data class Text(val text: String) : MdInline
    data class Bold(val children: List<MdInline>) : MdInline
    data class Italic(val children: List<MdInline>) : MdInline
    data class Strike(val children: List<MdInline>) : MdInline
    data class Code(val code: String) : MdInline
    data class Link(val children: List<MdInline>, val url: String) : MdInline
    data class Math(val tex: String) : MdInline
    data object LineBreak : MdInline
}

object Markdown {
    /** Never throws: anything it cannot make sense of comes back as plain paragraphs. */
    fun parse(text: String): List<MdBlock> = try {
        BlockParser(text.replace("\r\n", "\n").replace('\t', ' ').split('\n')).parse()
    } catch (e: Exception) {
        fallback(text)
    } catch (e: StackOverflowError) {
        fallback(text)
    }

    fun parseInline(text: String): List<MdInline> = try {
        InlineParser(text).parse()
    } catch (e: Exception) {
        listOf(MdInline.Text(text))
    } catch (e: StackOverflowError) {
        listOf(MdInline.Text(text))
    }

    /** Plain text of inline content, for copying, accessibility and read-aloud. */
    fun plain(inlines: List<MdInline>): String = buildString { inlines.forEach { appendPlain(it) } }

    private fun StringBuilder.appendPlain(node: MdInline) {
        when (node) {
            is MdInline.Text -> append(node.text)
            is MdInline.Bold -> node.children.forEach { appendPlain(it) }
            is MdInline.Italic -> node.children.forEach { appendPlain(it) }
            is MdInline.Strike -> node.children.forEach { appendPlain(it) }
            is MdInline.Code -> append(node.code)
            is MdInline.Link -> node.children.forEach { appendPlain(it) }
            is MdInline.Math -> append(node.tex)
            MdInline.LineBreak -> append('\n')
        }
    }

    private fun fallback(text: String): List<MdBlock> =
        text.split(Regex("\n{2,}")).filter { it.isNotBlank() }.map { MdBlock.Paragraph(listOf(MdInline.Text(it))) }
}

private class BlockParser(private val lines: List<String>, private val depth: Int = 0) {
    private var i = 0
    private val out = ArrayList<MdBlock>()

    fun parse(): List<MdBlock> {
        if (depth > MAX_DEPTH) return listOf(MdBlock.Paragraph(listOf(MdInline.Text(lines.joinToString("\n")))))
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.isBlank() -> i++
                FENCE.matches(line) -> fenced()
                isMathOpen(line) -> mathBlock()
                HEADING.matches(line) -> heading()
                RULE.matches(line) -> {
                    out += MdBlock.Rule
                    i++
                }
                QUOTE.matches(line) -> quote()
                isTableStart(i) -> table()
                LIST_ITEM.matches(line) -> list()
                else -> paragraph()
            }
        }
        return out
    }

    private fun fenced() {
        val m = FENCE.matchEntire(lines[i])!!
        val indent = m.groupValues[1].length
        val marker = m.groupValues[2]
        val info = m.groupValues[3].trim().split(' ').firstOrNull()?.takeIf { it.isNotEmpty() }
        i++
        val body = ArrayList<String>()
        var closed = false
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            if (trimmed.startsWith(marker.substring(0, 3)) && trimmed.trimEnd().all { it == marker[0] } &&
                trimmed.trimEnd().length >= marker.length
            ) {
                closed = true
                i++
                break
            }
            body += if (indent > 0) line.removePrefix(" ".repeat(minOf(indent, line.length - line.trimStart().length))) else line
            i++
        }
        val code = body.joinToString("\n")
        out += if (info.equals("math", ignoreCase = true)) MdBlock.Math(code.trim()) else MdBlock.Code(info, code, closed)
    }

    private fun isMathOpen(line: String): Boolean {
        val t = line.trim()
        return t.startsWith("$$") || t.startsWith("\\[") || t.startsWith("\\begin{equation") || t.startsWith("\\begin{align")
    }

    private fun mathBlock() {
        val first = lines[i].trim()
        val (open, close) = when {
            first.startsWith("$$") -> "$$" to "$$"
            first.startsWith("\\[") -> "\\[" to "\\]"
            else -> "" to (Regex("\\\\begin\\{([a-z*]+)}").find(first)?.groupValues?.get(1)?.let { "\\end{$it}" } ?: "\\end")
        }
        val rest = first.removePrefix(open)
        if (rest.length >= close.length && rest.trimEnd().endsWith(close) && (open.isEmpty() || rest.trim().length > close.length - 1)) {
            val tex = rest.trimEnd().removeSuffix(close)
            if (open.isNotEmpty() || tex.isNotBlank()) {
                out += MdBlock.Math((if (open.isEmpty()) first else tex).trim())
                i++
                return
            }
        }
        val body = StringBuilder(if (open.isEmpty()) first else rest)
        i++
        while (i < lines.size) {
            val line = lines[i]
            i++
            val idx = line.indexOf(close)
            if (idx >= 0) {
                body.append('\n').append(if (open.isEmpty()) line.substring(0, idx + close.length) else line.substring(0, idx))
                break
            }
            body.append('\n').append(line)
        }
        out += MdBlock.Math(body.toString().trim())
    }

    private fun heading() {
        val m = HEADING.matchEntire(lines[i])!!
        val text = m.groupValues[2].replace(Regex("\\s+#+\\s*$"), "").trim()
        out += MdBlock.Heading(m.groupValues[1].length, Markdown.parseInline(text))
        i++
    }

    private fun quote() {
        val inner = ArrayList<String>()
        while (i < lines.size) {
            val line = lines[i]
            if (QUOTE.matches(line)) {
                inner += line.trimStart().removePrefix(">").let { if (it.startsWith(" ")) it.substring(1) else it }
                i++
            } else if (line.isNotBlank() && inner.isNotEmpty() && inner.last().isNotBlank() && !startsBlock(line)) {
                inner += line
                i++
            } else {
                break
            }
        }
        val callout = inner.firstOrNull()?.let { CALLOUT.matchEntire(it.trim()) }
        if (callout != null) {
            val kind = when (callout.groupValues[1].uppercase()) {
                "TIP" -> CalloutKind.Tip
                "IMPORTANT" -> CalloutKind.Important
                "WARNING" -> CalloutKind.Warning
                "CAUTION", "DANGER" -> CalloutKind.Caution
                else -> CalloutKind.Note
            }
            val title = callout.groupValues[2].trim().ifEmpty { null }
            out += MdBlock.Callout(kind, title, BlockParser(inner.drop(1), depth + 1).parse())
        } else {
            out += MdBlock.Quote(BlockParser(inner, depth + 1).parse())
        }
    }

    private fun isTableStart(at: Int): Boolean {
        if (at + 1 >= lines.size) return false
        val head = lines[at]
        val delim = lines[at + 1]
        return head.contains('|') && TABLE_DELIM.matches(delim) && (delim.contains('|') || head.trimStart().startsWith("|"))
    }

    private fun table() {
        val header = splitRow(lines[i])
        val align = splitRow(lines[i + 1]).map { cell ->
            val c = cell.trim()
            when {
                c.startsWith(":") && c.endsWith(":") -> Align.Center
                c.endsWith(":") -> Align.End
                else -> Align.Start
            }
        }
        i += 2
        val rows = ArrayList<List<List<MdInline>>>()
        while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
            val cells = splitRow(lines[i])
            rows += List(header.size) { c -> Markdown.parseInline(cells.getOrElse(c) { "" }.trim()) }
            i++
        }
        out += MdBlock.Table(
            header = header.map { Markdown.parseInline(it.trim()) },
            align = List(header.size) { align.getOrElse(it) { Align.Start } },
            rows = rows,
        )
    }

    private fun splitRow(line: String): List<String> {
        var t = line.trim()
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|") && !t.endsWith("\\|")) t = t.dropLast(1)
        val cells = ArrayList<String>()
        val cell = StringBuilder()
        var inCode = false
        var k = 0
        while (k < t.length) {
            val ch = t[k]
            when {
                ch == '\\' && k + 1 < t.length && t[k + 1] == '|' -> {
                    cell.append('|')
                    k++
                }
                ch == '`' -> {
                    inCode = !inCode
                    cell.append(ch)
                }
                ch == '|' && !inCode -> {
                    cells += cell.toString()
                    cell.clear()
                }
                else -> cell.append(ch)
            }
            k++
        }
        cells += cell.toString()
        return cells
    }

    private fun list() {
        val first = LIST_ITEM.matchEntire(lines[i])!!
        val baseIndent = first.groupValues[1].length
        val ordered = first.groupValues[2].first().isDigit()
        val start = if (ordered) first.groupValues[2].dropLast(1).toIntOrNull() ?: 1 else 1
        val items = ArrayList<ListItem>()
        while (i < lines.size) {
            val m = LIST_ITEM.matchEntire(lines[i]) ?: break
            val indent = m.groupValues[1].length
            if (indent < baseIndent || indent > baseIndent + 3) break
            val isOrdered = m.groupValues[2].first().isDigit()
            if (isOrdered != ordered) break
            val contentIndent = indent + m.groupValues[2].length + 1
            val body = ArrayList<String>()
            body += m.groupValues[3]
            i++
            var sawBlank = false
            while (i < lines.size) {
                val line = lines[i]
                if (line.isBlank()) {
                    sawBlank = true
                    body += ""
                    i++
                    continue
                }
                val lead = line.length - line.trimStart().length
                if (lead >= contentIndent || (lead > baseIndent && LIST_ITEM.matches(line))) {
                    body += line.substring(minOf(lead, contentIndent))
                    sawBlank = false
                    i++
                } else if (!sawBlank && !startsBlock(line) && !LIST_ITEM.matches(line)) {
                    body += line.trimStart()
                    i++
                } else {
                    break
                }
            }
            while (body.isNotEmpty() && body.last().isBlank()) body.removeAt(body.lastIndex)
            val task = TASK.find(body.firstOrNull().orEmpty())
            if (task != null) body[0] = body[0].substring(task.range.last + 1)
            items += ListItem(
                blocks = BlockParser(body, depth + 1).parse(),
                task = task != null,
                checked = task?.groupValues?.get(1)?.isNotBlank() == true,
            )
            if (i < lines.size && lines[i].isBlank()) {
                val next = lines.drop(i).firstOrNull { it.isNotBlank() }
                if (next == null || !LIST_ITEM.matches(next)) break
                while (i < lines.size && lines[i].isBlank()) i++
            }
        }
        out += MdBlock.ListBlock(ordered, start, items)
    }

    private fun paragraph() {
        val buf = ArrayList<String>()
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) break
            if (buf.isNotEmpty() && SETEXT.matches(line)) {
                val level = if (line.trim().startsWith("=")) 1 else 2
                out += MdBlock.Heading(level, Markdown.parseInline(buf.joinToString(" ") { it.trim() }))
                i++
                return
            }
            if (buf.isNotEmpty() && (startsBlock(line) || isTableStart(i))) break
            buf += line
            i++
        }
        if (buf.isEmpty()) {
            buf += lines[i]
            i++
        }
        out += MdBlock.Paragraph(Markdown.parseInline(buf.joinToString("\n") { it.trimStart() }))
    }

    private fun startsBlock(line: String): Boolean =
        FENCE.matches(line) || HEADING.matches(line) || QUOTE.matches(line) || RULE.matches(line) ||
            LIST_ITEM.matches(line) || isMathOpen(line)

    private companion object {
        const val MAX_DEPTH = 12
        val FENCE = Regex("^( {0,3})(`{3,}|~{3,})(.*)$")
        val HEADING = Regex("^ {0,3}(#{1,6})\\s+(.*)$")
        val RULE = Regex("^ {0,3}([-*_])( *\\1){2,} *$")
        val QUOTE = Regex("^ {0,3}>.*$")
        val CALLOUT = Regex("^\\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION|DANGER)]\\s*(.*)$", RegexOption.IGNORE_CASE)
        val LIST_ITEM = Regex("^( *)([-*+]|\\d{1,9}[.)])(?: +(.*)|$)")
        val TASK = Regex("^\\[([ xX])]\\s+")
        val TABLE_DELIM = Regex("^\\s*\\|?\\s*:?-{1,}:?\\s*(\\|\\s*:?-{1,}:?\\s*)*\\|?\\s*$")
        val SETEXT = Regex("^ {0,3}(=+|-{2,})\\s*$")
    }
}

private class InlineParser(private val s: String, private val depth: Int = 0) {
    private val out = ArrayList<MdInline>()
    private val text = StringBuilder()
    private var i = 0

    fun parse(): List<MdInline> {
        if (depth > MAX_DEPTH) return listOf(MdInline.Text(s))
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length && (s[i + 1] == '(' || s[i + 1] == '[') && tryBackslashMath() -> Unit
                c == '\\' && i + 1 < s.length && s[i + 1] in ESCAPABLE -> {
                    text.append(s[i + 1])
                    i += 2
                }
                c == '\\' && i + 1 < s.length && s[i + 1] == '\n' -> {
                    flush()
                    out += MdInline.LineBreak
                    i += 2
                }
                c == '\n' -> {
                    trimTrailingSpaces()
                    flush()
                    out += MdInline.LineBreak
                    i++
                }
                c == '`' && tryCode() -> Unit
                c == '$' && tryDollarMath() -> Unit
                c == '!' && i + 1 < s.length && s[i + 1] == '[' && tryLink(image = true) -> Unit
                c == '[' && tryLink(image = false) -> Unit
                c == '<' && tryAutolink() -> Unit
                (c == 'h' || c == 'w') && atWordStart() && tryBareUrl() -> Unit
                c == '~' && s.startsWith("~~", i) && tryDelimited("~~") { MdInline.Strike(it) } -> Unit
                c == '*' && s.startsWith("**", i) && tryDelimited("**") { MdInline.Bold(it) } -> Unit
                c == '_' && s.startsWith("__", i) && atWordStart() && tryDelimited("__") { MdInline.Bold(it) } -> Unit
                c == '*' && tryDelimited("*") { MdInline.Italic(it) } -> Unit
                c == '_' && atWordStart() && tryDelimited("_") { MdInline.Italic(it) } -> Unit
                else -> {
                    text.append(c)
                    i++
                }
            }
        }
        flush()
        return merge(out)
    }

    private fun flush() {
        if (text.isNotEmpty()) {
            out += MdInline.Text(text.toString())
            text.clear()
        }
    }

    private fun trimTrailingSpaces() {
        while (text.isNotEmpty() && text.last() == ' ') text.setLength(text.length - 1)
    }

    private fun atWordStart(): Boolean = i == 0 || !s[i - 1].isLetterOrDigit()

    private fun tryCode(): Boolean {
        var n = 0
        while (i + n < s.length && s[i + n] == '`') n++
        val fence = "`".repeat(n)
        var search = i + n
        while (true) {
            val end = s.indexOf(fence, search)
            if (end < 0) return false
            if (end + n < s.length && s[end + n] == '`') {
                search = end + n + 1
                continue
            }
            var code = s.substring(i + n, end).replace('\n', ' ')
            if (code.length >= 2 && code.startsWith(" ") && code.endsWith(" ") && code.isNotBlank()) code = code.substring(1, code.length - 1)
            flush()
            out += MdInline.Code(code)
            i = end + n
            return true
        }
    }

    private fun tryDollarMath(): Boolean {
        if (s.startsWith("$$", i)) {
            val end = s.indexOf("$$", i + 2)
            if (end <= i + 2) return false
            flush()
            out += MdInline.Math(s.substring(i + 2, end).trim())
            i = end + 2
            return true
        }
        if (i > 0 && s[i - 1].isLetterOrDigit()) return false
        val open = i + 1
        if (open >= s.length || s[open].isWhitespace() || s[open] == '$') return false
        var k = open
        while (k < s.length) {
            val ch = s[k]
            if (ch == '\n') return false
            if (ch == '\\') {
                k += 2
                continue
            }
            if (ch == '$') {
                val prevSpace = s[k - 1].isWhitespace()
                val nextDigit = k + 1 < s.length && s[k + 1].isDigit()
                if (!prevSpace && !nextDigit) {
                    val tex = s.substring(open, k)
                    if (tex.isBlank()) return false
                    flush()
                    out += MdInline.Math(tex)
                    i = k + 1
                    return true
                }
                return false
            }
            k++
        }
        return false
    }

    private fun tryBackslashMath(): Boolean {
        val close = if (s[i + 1] == '(') "\\)" else "\\]"
        val end = s.indexOf(close, i + 2)
        if (end < 0) return false
        val tex = s.substring(i + 2, end).trim()
        if (tex.isEmpty()) return false
        flush()
        out += MdInline.Math(tex)
        i = end + 2
        return true
    }

    private fun tryLink(image: Boolean): Boolean {
        val open = if (image) i + 1 else i
        var level = 0
        var k = open
        var close = -1
        while (k < s.length) {
            when (s[k]) {
                '\\' -> k++
                '[' -> level++
                ']' -> {
                    level--
                    if (level == 0) {
                        close = k
                        break
                    }
                }
                '\n' -> if (k + 1 < s.length && s[k + 1] == '\n') return false
            }
            k++
        }
        if (close < 0 || close + 1 >= s.length || s[close + 1] != '(') return false
        val urlEnd = s.indexOf(')', close + 2)
        if (urlEnd < 0) return false
        val target = s.substring(close + 2, urlEnd).trim().split(' ').first().removeSurrounding("<", ">")
        if (target.isEmpty() || target.contains('\n')) return false
        val label = s.substring(open + 1, close)
        flush()
        val children = if (label.isEmpty()) listOf(MdInline.Text(target)) else InlineParser(label, depth + 1).parse()
        out += MdInline.Link(if (image) listOf(MdInline.Text(label.ifEmpty { "image" })) else children, target)
        i = urlEnd + 1
        return true
    }

    private fun tryAutolink(): Boolean {
        val end = s.indexOf('>', i + 1)
        if (end < 0) return false
        val inner = s.substring(i + 1, end)
        if (!(inner.startsWith("http://") || inner.startsWith("https://") || inner.startsWith("mailto:")) || inner.any { it.isWhitespace() }) {
            return false
        }
        flush()
        out += MdInline.Link(listOf(MdInline.Text(inner.removePrefix("mailto:"))), inner)
        i = end + 1
        return true
    }

    private fun tryBareUrl(): Boolean {
        val m = BARE_URL.find(s, i)?.takeIf { it.range.first == i } ?: return false
        var url = m.value
        while (url.isNotEmpty() && url.last() in TRAILING_PUNCT) url = url.dropLast(1)
        if (url.endsWith(")") && url.count { it == '(' } < url.count { it == ')' }) url = url.dropLast(1)
        if (url.length < 5) return false
        flush()
        out += MdInline.Link(listOf(MdInline.Text(url)), if (url.startsWith("www.")) "https://$url" else url)
        i += url.length
        return true
    }

    private inline fun tryDelimited(delim: String, make: (List<MdInline>) -> MdInline): Boolean {
        val start = i + delim.length
        if (start >= s.length || s[start].isWhitespace()) return false
        var k = start
        while (k < s.length) {
            var end = s.indexOf(delim, k)
            if (end < 0) return false
            if (delim == "*" && s.startsWith("**", end) && !s.startsWith("***", end)) {
                k = end + 2
                continue
            }
            if (delim.length == 2) {
                while (end + 2 < s.length && s[end + 2] == delim[0]) end++
            }
            val valid = end > start && !s[end - 1].isWhitespace() &&
                (delim[0] != '_' || end + delim.length >= s.length || !s[end + delim.length].isLetterOrDigit())
            if (valid) {
                val inner = s.substring(start, end)
                if (inner.contains("\n\n")) return false
                flush()
                out += make(InlineParser(inner, depth + 1).parse())
                i = end + delim.length
                return true
            }
            k = end + delim.length
        }
        return false
    }

    private fun merge(nodes: List<MdInline>): List<MdInline> {
        val merged = ArrayList<MdInline>(nodes.size)
        for (n in nodes) {
            val last = merged.lastOrNull()
            if (n is MdInline.Text && last is MdInline.Text) merged[merged.lastIndex] = MdInline.Text(last.text + n.text) else merged += n
        }
        return merged
    }

    private companion object {
        const val MAX_DEPTH = 8
        const val ESCAPABLE = "\\`*_{}[]()#+-.!|~<>$"
        const val TRAILING_PUNCT = ".,;:!?'\"*_"
        val BARE_URL = Regex("(https?://|www\\.)[^\\s<>\"`]+")
    }
}
