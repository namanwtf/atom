package atom.web.search

import java.net.URLEncoder

/** Fills `{name}` placeholders in a custom endpoint's URL or JSON body. Unknown placeholders are left alone. */
object Template {
    private val placeholder = Regex("\\{([a-zA-Z_]+)\\}")

    fun url(template: String, values: Map<String, String>): String =
        placeholder.replace(template) { m ->
            values[m.groupValues[1]]?.let { URLEncoder.encode(it, "UTF-8").replace("+", "%20") } ?: m.value
        }

    /**
     * Inside a JSON string (`"q": "{query}"`) the value is JSON-escaped in place. A bare placeholder
     * (`"n": {count}`) becomes a number when the value is numeric and a quoted string otherwise.
     */
    fun json(template: String, values: Map<String, String>): String {
        val out = StringBuilder()
        var inString = false
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c == '\\' && inString && i + 1 < template.length) {
                out.append(c).append(template[i + 1])
                i += 2
                continue
            }
            if (c == '"') inString = !inString
            if (c == '{') {
                val m = placeholder.matchAt(template, i)
                val value = m?.let { values[it.groupValues[1]] }
                if (m != null && value != null) {
                    out.append(
                        when {
                            inString -> escape(value)
                            value.toDoubleOrNull() != null -> value
                            value == "true" || value == "false" -> value
                            else -> "\"" + escape(value) + "\""
                        },
                    )
                    i += m.value.length
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
            }
        }
        return sb.toString()
    }
}
