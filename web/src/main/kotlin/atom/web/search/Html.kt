package atom.web.search

/** A forgiving HTML parser: good enough to find a page's main text, never throws on bad markup. */
internal class Node(val tag: String, val attrs: Map<String, String> = emptyMap(), val parent: Node? = null) {
    val children = ArrayList<Any>()

    fun attr(name: String): String? = attrs[name]

    fun elements(): Sequence<Node> = children.asSequence().filterIsInstance<Node>()

    fun walk(): Sequence<Node> = sequence {
        yield(this@Node)
        for (c in children) if (c is Node) yieldAll(c.walk())
    }

    fun text(): String = buildString { appendText(this) }

    private fun appendText(sb: StringBuilder) {
        for (c in children) if (c is String) sb.append(c) else (c as Node).appendText(sb)
    }
}

internal object Html {
    private val voidTags = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr",
    )
    private val rawText = setOf("script", "style", "textarea", "title", "noscript", "template", "svg", "xmp")
    private val autoClose = mapOf(
        "p" to setOf("p", "div", "ul", "ol", "table", "h1", "h2", "h3", "h4", "h5", "h6", "pre", "blockquote", "section", "article", "header", "footer", "form", "figure"),
        "li" to setOf("li"),
        "dt" to setOf("dt", "dd"),
        "dd" to setOf("dt", "dd"),
        "tr" to setOf("tr"),
        "td" to setOf("td", "th", "tr"),
        "th" to setOf("td", "th", "tr"),
        "option" to setOf("option"),
    )
    private val attrRegex = Regex("""([^\s=/>"']+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?""")

    fun parse(html: String): Node {
        val root = Node("#root")
        var cur = root
        var i = 0
        val n = html.length
        val text = StringBuilder()
        fun flushText() {
            if (text.isNotEmpty()) {
                cur.children += Entities.decode(text.toString())
                text.clear()
            }
        }
        while (i < n) {
            val c = html[i]
            if (c != '<') { text.append(c); i++; continue }
            if (html.startsWith("<!--", i)) {
                val end = html.indexOf("-->", i + 4)
                i = if (end < 0) n else end + 3
                continue
            }
            if (i + 1 < n && (html[i + 1] == '!' || html[i + 1] == '?')) {
                val end = html.indexOf('>', i)
                i = if (end < 0) n else end + 1
                continue
            }
            val close = i + 1 < n && html[i + 1] == '/'
            val nameStart = if (close) i + 2 else i + 1
            var j = nameStart
            while (j < n && (html[j].isLetterOrDigit() || html[j] == '-' || html[j] == ':')) j++
            if (j == nameStart) { text.append(c); i++; continue }
            val tag = html.substring(nameStart, j).lowercase()
            val end = findTagEnd(html, j)
            val rawAttrs = html.substring(j, end).trimEnd('/', ' ')
            i = if (end < n) end + 1 else n
            flushText()
            if (close) {
                var node: Node? = cur
                while (node != null && node.tag != tag) node = node.parent
                if (node != null && node !== root) cur = node.parent ?: root
                continue
            }
            autoClose[cur.tag]?.let { closers -> if (tag in closers) cur = cur.parent ?: root }
            val el = Node(tag, parseAttrs(rawAttrs), cur)
            cur.children += el
            if (tag in rawText) {
                val closeIdx = html.indexOf("</$tag", i, ignoreCase = true)
                val content = if (closeIdx < 0) html.substring(i) else html.substring(i, closeIdx)
                if (tag == "title" || tag == "textarea") el.children += Entities.decode(content)
                i = if (closeIdx < 0) n else (html.indexOf('>', closeIdx).takeIf { it >= 0 }?.plus(1) ?: n)
                continue
            }
            if (tag !in voidTags && !html.startsWith("/>", maxOf(end - 1, 0))) cur = el
        }
        flushText()
        return root
    }

    private fun findTagEnd(html: String, from: Int): Int {
        var quote: Char? = null
        var k = from
        while (k < html.length) {
            val ch = html[k]
            if (quote != null) { if (ch == quote) quote = null } else if (ch == '"' || ch == '\'') quote = ch else if (ch == '>') return k
            k++
        }
        return html.length
    }

    private fun parseAttrs(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val map = HashMap<String, String>()
        for (m in attrRegex.findAll(raw)) {
            val key = m.groupValues[1].lowercase()
            val value = m.groups[2]?.value ?: m.groups[3]?.value ?: m.groups[4]?.value ?: ""
            map.putIfAbsent(key, Entities.decode(value))
        }
        return map
    }
}

internal object Entities {
    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "ndash" to "–",
        "mdash" to "—", "hellip" to "…", "copy" to "©", "reg" to "®", "trade" to "™", "laquo" to "«", "raquo" to "»",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "bull" to "•", "middot" to "·", "times" to "×",
        "divide" to "÷", "deg" to "°", "euro" to "€", "pound" to "£", "yen" to "¥", "cent" to "¢", "sect" to "§",
        "para" to "¶", "shy" to "", "zwj" to "", "zwnj" to "", "thinsp" to " ", "ensp" to " ", "emsp" to " ",
    )
    private val regex = Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);?")

    fun decode(s: String): String {
        if ('&' !in s) return s
        return regex.replace(s) { m ->
            val body = m.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") -> body.substring(2).toIntOrNull(16)?.let(::cp) ?: m.value
                body.startsWith("#") -> body.substring(1).toIntOrNull()?.let(::cp) ?: m.value
                else -> named[body] ?: named[body.lowercase()] ?: m.value
            }
        }
    }

    private fun cp(code: Int): String =
        if (code in 1..0x10FFFF && code !in 0xD800..0xDFFF) String(Character.toChars(code)) else ""
}
