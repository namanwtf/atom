package atom.extend.mcp

/** Agent-facing tool names: `mcp_<server>_<tool>`, lowercase [a-z0-9_], at most 64 characters, unique. */
object McpNaming {
    const val PREFIX = "mcp_"
    private const val MAX = 64
    private const val MAX_SERVER = 20

    fun sanitize(s: String, max: Int): String =
        s.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(max).trimEnd('_').ifEmpty { "x" }

    /** Names for (server, tool) pairs in order; later duplicates get a numeric suffix. */
    fun assign(pairs: List<Pair<String, String>>, taken: Set<String> = emptySet()): List<String> {
        val used = taken.toMutableSet()
        return pairs.map { (server, tool) ->
            val head = PREFIX + sanitize(server, MAX_SERVER) + "_"
            val base = head + sanitize(tool, MAX - head.length)
            var name = base
            var n = 2
            while (name in used) {
                val suffix = "_$n"
                name = base.take(MAX - suffix.length).trimEnd('_') + suffix
                n++
            }
            used += name
            name
        }
    }
}
