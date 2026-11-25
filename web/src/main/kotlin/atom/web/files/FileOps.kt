package atom.web.files

/** Glob patterns: `*` (within a segment), `**` (any depth), `?`, `[abc]`, `{a,b}`. */
object Glob {
    fun toRegex(pattern: String): Regex {
        val p = pattern.trim().removePrefix("./")
        val sb = StringBuilder("^")
        var i = 0
        var inGroup = false
        while (i < p.length) {
            val c = p[i]
            when {
                c == '*' && i + 1 < p.length && p[i + 1] == '*' -> {
                    val slashAfter = i + 2 < p.length && p[i + 2] == '/'
                    sb.append(if (slashAfter) "(?:.*/)?" else ".*")
                    i += if (slashAfter) 3 else 2
                    continue
                }
                c == '*' -> sb.append("[^/]*")
                c == '?' -> sb.append("[^/]")
                c == '[' -> {
                    val end = p.indexOf(']', i + 1)
                    if (end < 0) sb.append("\\[") else {
                        var body = p.substring(i + 1, end)
                        if (body.startsWith("!")) body = "^" + body.substring(1)
                        sb.append('[').append(body.replace("\\", "\\\\")).append(']')
                        i = end + 1
                        continue
                    }
                }
                c == '{' -> { inGroup = true; sb.append("(?:") }
                c == '}' && inGroup -> { inGroup = false; sb.append(')') }
                c == ',' && inGroup -> sb.append('|')
                c == '\\' && i + 1 < p.length -> { sb.append(Regex.escape(p[i + 1].toString())); i += 2; continue }
                else -> sb.append(Regex.escape(c.toString()))
            }
            i++
        }
        sb.append('$')
        return Regex(sb.toString(), if (pattern.any { it.isUpperCase() }) setOf() else setOf(RegexOption.IGNORE_CASE))
    }

    /** A pattern without a slash matches the file name at any depth; one with a slash matches the relative path. */
    fun matcher(pattern: String): (relativePath: String) -> Boolean {
        val regex = toRegex(pattern)
        return if ('/' in pattern.trim().removePrefix("./")) {
            { rel -> regex.matches(rel) }
        } else {
            { rel -> regex.matches(rel.substringAfterLast('/')) }
        }
    }
}

sealed interface EditOutcome {
    data class Applied(val content: String, val replacements: Int, val firstLine: Int) : EditOutcome
    data class Failed(val reason: String) : EditOutcome
}

object TextEdit {
    /** Replaces [old] with [new]; unless [all], [old] must occur exactly once. */
    fun apply(content: String, old: String, new: String, all: Boolean = false): EditOutcome {
        if (old.isEmpty()) return EditOutcome.Failed("old_text is empty. To create or replace a whole file, use file_write.")
        if (old == new) return EditOutcome.Failed("old_text and new_text are identical; nothing to change.")
        val hits = occurrences(content, old)
        if (hits.isEmpty()) {
            val normalized = content.replace("\r\n", "\n")
            val hint = if (normalized != content && occurrences(normalized, old.replace("\r\n", "\n")).isNotEmpty()) {
                " The file uses CRLF line endings; copy the text exactly."
            } else if (occurrences(content, old.trim()).isNotEmpty()) {
                " It does occur without the surrounding whitespace; check indentation and blank lines."
            } else {
                " Read the file again and copy the text exactly, including whitespace."
            }
            return EditOutcome.Failed("old_text was not found.$hint")
        }
        if (hits.size > 1 && !all) {
            val lines = hits.take(10).map { lineOf(content, it) }
            return EditOutcome.Failed(
                "old_text occurs ${hits.size} times (lines ${lines.joinToString()}). Add surrounding lines so it is unique, or set replace_all=true.",
            )
        }
        val out = if (all) content.replace(old, new) else content.replaceRange(hits[0], hits[0] + old.length, new)
        return EditOutcome.Applied(out, if (all) hits.size else 1, lineOf(content, hits[0]))
    }

    fun occurrences(text: String, needle: String): List<Int> {
        if (needle.isEmpty()) return emptyList()
        val out = ArrayList<Int>()
        var i = text.indexOf(needle)
        while (i >= 0) {
            out += i
            i = text.indexOf(needle, i + needle.length)
        }
        return out
    }

    fun lineOf(text: String, index: Int): Int {
        var line = 1
        for (k in 0 until minOf(index, text.length)) if (text[k] == '\n') line++
        return line
    }
}

object LinePaging {
    data class Page(val text: String, val firstLine: Int, val lastLine: Int, val totalLines: Int) {
        val hasMore: Boolean get() = lastLine < totalLines
    }

    /** Numbered lines starting at 1-based [offset]; stops at [limit] lines or [maxChars]. Long lines are cut. */
    fun page(content: String, offset: Int, limit: Int, maxChars: Int = 40_000, maxLineChars: Int = 2000): Page {
        val lines = content.split('\n').let { if (content.endsWith('\n')) it.dropLast(1) else it }
        val total = lines.size
        val start = offset.coerceAtLeast(1)
        if (start > total) return Page("", start, start - 1, total)
        val sb = StringBuilder()
        var last = start - 1
        val width = (minOf(total, start + limit)).toString().length
        for (n in start..minOf(total, start + limit - 1)) {
            var line = lines[n - 1].removeSuffix("\r")
            if (line.length > maxLineChars) line = line.take(maxLineChars) + " …[line cut, ${line.length} chars]"
            if (sb.length + line.length > maxChars && n > start) break
            sb.append(n.toString().padStart(width)).append('\t').append(line).append('\n')
            last = n
        }
        return Page(sb.toString(), start, last, total)
    }
}

object Sizes {
    fun human(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }

    fun looksBinary(bytes: ByteArray): Boolean {
        val n = minOf(bytes.size, 8000)
        for (i in 0 until n) if (bytes[i] == 0.toByte()) return true
        return false
    }
}
