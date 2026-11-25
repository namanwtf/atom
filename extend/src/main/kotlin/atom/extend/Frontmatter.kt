package atom.extend

/** Minimal YAML frontmatter (`---` key: value `---`) as used by SKILL.md and agents/<name>.md. */
internal object Frontmatter {
    data class Doc(val fields: Map<String, String>, val lists: Map<String, List<String>>, val body: String)

    private val KEY = Regex("^([A-Za-z][A-Za-z0-9_-]*)\\s*:\\s*(.*)$")

    fun parse(text: String): Doc? {
        val lines = text.removePrefix("﻿").replace("\r\n", "\n").lines()
        if (lines.firstOrNull()?.trim() != "---") return null
        val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" } ?: return null
        val fields = linkedMapOf<String, String>()
        val lists = linkedMapOf<String, List<String>>()
        var i = 1
        while (i < end) {
            val m = KEY.matchEntire(lines[i])
            if (m == null) {
                i++
                continue
            }
            val key = m.groupValues[1].lowercase()
            val raw = m.groupValues[2].trim()
            val block = mutableListOf<String>()
            var j = i + 1
            while (j < end && (lines[j].isBlank() || lines[j].startsWith(" ") || lines[j].startsWith("\t"))) {
                block += lines[j]
                j++
            }
            when {
                raw.startsWith("|") || raw.startsWith(">") -> {
                    val content = block.map { it.trim() }
                    fields[key] = if (raw.startsWith("|")) content.joinToString("\n").trim() else foldLines(content)
                }
                raw.isEmpty() && block.any { it.trimStart().startsWith("- ") } -> {
                    lists[key] = block.map { it.trim() }.filter { it.startsWith("- ") }.map { unquote(it.removePrefix("- ").trim()) }
                }
                raw.startsWith("[") && raw.endsWith("]") -> {
                    lists[key] = raw.removeSurrounding("[", "]").split(',').map { unquote(it.trim()) }.filter { it.isNotEmpty() }
                }
                else -> {
                    val joined = (listOf(raw) + block.map { it.trim() }).filter { it.isNotEmpty() }.joinToString(" ")
                    fields[key] = unquote(joined)
                }
            }
            i = j
        }
        val body = lines.drop(end + 1).joinToString("\n").trim('\n')
        return Doc(fields, lists, body)
    }

    private fun foldLines(lines: List<String>): String =
        lines.joinToString("\n").trim().split(Regex("\n\\s*\n")).joinToString("\n") { it.replace('\n', ' ') }

    private fun unquote(v: String): String = when {
        v.length >= 2 && v.startsWith('"') && v.endsWith('"') -> v.substring(1, v.length - 1).replace("\\\"", "\"").replace("\\n", "\n")
        v.length >= 2 && v.startsWith('\'') && v.endsWith('\'') -> v.substring(1, v.length - 1).replace("''", "'")
        else -> v
    }

    fun quote(v: String): String =
        if (v.isEmpty() || v.any { it in ":#'\"\n[]{}," } || v.first().isWhitespace() || v.last().isWhitespace()) {
            "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        } else {
            v
        }

    val KEBAB = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
}
