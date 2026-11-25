package atom.voice.speech

/**
 * Turns a Markdown answer into text worth hearing. Skips fenced code, tables, images, HTML, math blocks and bare
 * URLs; keeps link text; speaks list numbers and task state ("Done:" / "To do:"); ends every block with a
 * sentence break so the voice pauses between items. Pure and allocation-light; unit tested.
 */
object MarkdownSpeech {
    private val fence = Regex("^\\s*(```|~~~)")
    private val tableSeparator = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")
    private val heading = Regex("^\\s{0,3}#{1,6}\\s+")
    private val setextUnderline = Regex("^\\s*(=+|-+)\\s*$")
    private val rule = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val task = Regex("^\\s*[-*+]\\s+\\[([ xX])]\\s+")
    private val bullet = Regex("^\\s*[-*+•]\\s+")
    private val ordered = Regex("^\\s*(\\d{1,4})[.)]\\s+")
    private val quote = Regex("^\\s*(>\\s?)+")
    private val image = Regex("!\\[[^\\]]*]\\([^)]*\\)")
    private val refImage = Regex("!\\[[^\\]]*]\\[[^\\]]*]")
    private val link = Regex("\\[([^\\]]+)]\\((?:[^)(]|\\([^)]*\\))*\\)")
    private val refLink = Regex("\\[([^\\]]+)]\\[[^\\]]*]")
    private val linkDefinition = Regex("^\\s{0,3}\\[[^\\]]+]:\\s*\\S+.*$")
    private val autolink = Regex("<(https?://|mailto:)[^>]+>")
    private val bareUrl = Regex("\\b(?:https?://|www\\.)[^\\s<>()\\[\\]]+", RegexOption.IGNORE_CASE)
    private val htmlBlockOpen = Regex("^\\s*<(script|style|pre|table|svg|iframe)\\b", RegexOption.IGNORE_CASE)
    private val htmlComment = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val htmlTag = Regex("</?[a-zA-Z][a-zA-Z0-9-]*(\\s[^<>]*)?/?>")
    private val inlineCode = Regex("`+([^`]*)`+")
    private val inlineMath = Regex("(?<![\\\\$])\\$(?!\\s)([^$\\n]+?)(?<!\\s)\\$")
    private val emphasis = Regex("(\\*\\*\\*|\\*\\*|\\*|~~)(?=\\S)(.+?)(?<=\\S)\\1")
    private val underscoreEmphasis = Regex("(?<![A-Za-z0-9])(__|_)(?=\\S)(.+?)(?<=\\S)\\1(?![A-Za-z0-9])")
    private val footnoteRef = Regex("\\[\\^[^\\]]+]")
    private val htmlEntity = Regex("&(nbsp|amp|lt|gt|quot|#39);")
    private val spaces = Regex("[ \\t\\u00A0]+")
    private val terminal = charArrayOf('.', '!', '?', '。', '！', '？', ':', ';', '：', '；', '…')

    /** [filter] false keeps the text as written (only whitespace is tidied). */
    fun toSpeech(markdown: String, filter: Boolean = true): String {
        if (!filter) return markdown.replace(spaces, " ").lines().joinToString(" ") { it.trim() }.trim()
        val out = StringBuilder(markdown.length)
        var inFence: String? = null
        var inMath = false
        var htmlBlock: String? = null
        var previousBlank = true
        var inIndentedCode = false
        val lines = htmlComment.replace(markdown, " ").lines()
        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trimEnd()
            i++
            // Fenced code: skip until the matching fence.
            val fenceMatch = fence.find(line)
            if (inFence != null) {
                if (fenceMatch != null && line.trim().startsWith(inFence)) inFence = null
                continue
            }
            if (fenceMatch != null) {
                inFence = fenceMatch.groupValues[1]
                continue
            }
            // Display math $$ ... $$ (also \[ ... \]).
            val t = line.trim()
            if (inMath) {
                if (t.endsWith("$$") || t.endsWith("\\]")) inMath = false
                continue
            }
            if (t.startsWith("$$") || t.startsWith("\\[")) {
                val closesSameLine = (t.length > 2 && (t.endsWith("$$") && t.length >= 4)) || (t.startsWith("\\[") && t.endsWith("\\]"))
                if (!closesSameLine) inMath = true
                continue
            }
            // Raw HTML blocks whose contents are not prose.
            if (htmlBlock != null) {
                if (line.contains("</$htmlBlock", ignoreCase = true)) htmlBlock = null
                continue
            }
            val htmlOpen = htmlBlockOpen.find(line)
            if (htmlOpen != null) {
                val tag = htmlOpen.groupValues[1]
                if (!line.contains("</$tag", ignoreCase = true)) htmlBlock = tag
                continue
            }
            if (t.isEmpty()) {
                previousBlank = true
                continue
            }
            // Indented code (4 spaces / tab after a blank line) that is not a list item.
            val indented = raw.startsWith("    ") || raw.startsWith("\t")
            if (indented && (previousBlank || inIndentedCode) && !bullet.containsMatchIn(raw) && !ordered.containsMatchIn(raw)) {
                inIndentedCode = true
                continue
            }
            inIndentedCode = false
            previousBlank = false
            // Tables: a row followed by a separator row, then every following pipe row.
            if (t.contains('|') && i < lines.size && tableSeparator.matches(lines[i])) {
                i++
                while (i < lines.size && lines[i].trim().let { it.isNotEmpty() && it.contains('|') }) i++
                continue
            }
            if (tableSeparator.matches(t) && t.contains('|')) continue
            if (t.startsWith("|") && t.endsWith("|")) continue
            if (rule.matches(t) || linkDefinition.matches(t)) continue
            if (setextUnderline.matches(t) && out.isNotEmpty()) continue

            var text = t
            var prefix = ""
            text = text.replace(quote, "")
            task.find(text)?.let { m ->
                prefix = if (m.groupValues[1].isBlank()) "To do: " else "Done: "
                text = text.substring(m.range.last + 1)
            } ?: ordered.find(text)?.let { m ->
                prefix = "${m.groupValues[1]}. "
                text = text.substring(m.range.last + 1)
            } ?: bullet.find(text)?.let { m ->
                text = text.substring(m.range.last + 1)
            }
            text = text.replace(heading, "")
            text = inline(text)
            if (text.isBlank()) continue
            appendSentence(out, prefix + text)
        }
        return out.toString().trim()
    }

    private fun inline(input: String): String {
        var s = input
        s = s.replace(image, " ").replace(refImage, " ")
        s = s.replace(autolink, " ")
        s = s.replace(link) { it.groupValues[1] }
        s = s.replace(refLink) { it.groupValues[1] }
        s = s.replace(footnoteRef, "")
        s = s.replace(inlineCode) { m -> m.groupValues[1].trim().let { if (it.length <= 40 && !it.contains("://")) it else " " } }
        s = s.replace(inlineMath) { m -> m.groupValues[1].let { if (it.length <= 24) it else " " } }
        s = s.replace(htmlTag, " ")
        s = s.replace(bareUrl, " ")
        repeat(2) {
            s = s.replace(emphasis) { it.groupValues[2] }
            s = s.replace(underscoreEmphasis) { it.groupValues[2] }
        }
        s = s.replace(htmlEntity) { m ->
            when (m.groupValues[1]) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "#39" -> "'"; else -> " "
            }
        }
        s = s.replace("\\", "")
        return s.replace(spaces, " ").trim().trim(',', ' ')
    }

    private fun isCjk(c: Char): Boolean =
        c in "。！？；：，、" || Character.UnicodeScript.of(c.code) in setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL)

    private fun appendSentence(out: StringBuilder, text: String) {
        if (out.isNotEmpty() && !(isCjk(out.last()) || isCjk(text.first()))) out.append(' ')
        out.append(text)
        if (text.last() !in terminal) out.append('.')
    }
}

/** Splits speech text into pieces a cloud service accepts, at sentence boundaries where possible. */
object SpeechChunker {
    private val sentenceEnd = Regex("(?<=[.!?。！？；;…])\\s+|(?<=[。！？；])")

    fun split(text: String, maxChars: Int = 400): List<String> {
        val clean = text.trim()
        if (clean.isEmpty()) return emptyList()
        if (clean.length <= maxChars) return listOf(clean)
        val sentences = clean.split(sentenceEnd).map { it.trim() }.filter { it.isNotEmpty() }
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) chunks += current.toString().trim()
            current.clear()
        }
        for (sentence in sentences) {
            if (sentence.length > maxChars) {
                flush()
                chunks += hardSplit(sentence, maxChars)
                continue
            }
            if (current.length + sentence.length + 1 > maxChars) flush()
            if (current.isNotEmpty()) current.append(' ')
            current.append(sentence)
        }
        flush()
        return chunks
    }

    private fun hardSplit(sentence: String, maxChars: Int): List<String> {
        val parts = mutableListOf<String>()
        var rest = sentence
        while (rest.length > maxChars) {
            val window = rest.substring(0, maxChars)
            val cut = maxOf(window.lastIndexOf(", "), window.lastIndexOf("，"), window.lastIndexOf(' '))
                .takeIf { it > maxChars / 3 } ?: maxChars
            parts += rest.substring(0, cut + if (cut < maxChars) 1 else 0).trim()
            rest = rest.substring(cut + if (cut < maxChars) 1 else 0).trim()
        }
        if (rest.isNotEmpty()) parts += rest
        return parts
    }
}
