package atom.core.agent

import atom.core.llm.Role
import atom.core.llm.ToolSpec
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolContext
import atom.core.tool.ToolResult
import atom.core.tool.optInt
import atom.core.tool.optString
import atom.core.tool.schema
import kotlinx.serialization.json.JsonObject

/** One searchable message of the full conversation record (already scrubbed of sensitive data). */
data class HistoryEntry(val index: Int, val role: Role, val text: String, val toolName: String? = null)

/** Plain keyword search over the whole conversation, including what compaction summarized away. */
object HistorySearch {
    data class Hit(val entry: HistoryEntry, val score: Int, val snippet: String)

    fun search(entries: List<HistoryEntry>, query: String, limit: Int = 8, snippetChars: Int = 280): List<Hit> {
        val phrase = query.trim().lowercase()
        if (phrase.isEmpty()) return emptyList()
        val terms = phrase.split(WHITESPACE).filter { it.length >= 2 }.ifEmpty { listOf(phrase) }.distinct()
        return entries.asSequence()
            .mapNotNull { entry ->
                val hay = entry.text.lowercase()
                val matched = terms.count { it in hay }
                if (matched == 0) return@mapNotNull null
                val occurrences = terms.sumOf { occurrences(hay, it).coerceAtMost(5) }
                val score = matched * 10 + occurrences + if (terms.size > 1 && phrase in hay) 25 else 0
                val anchor = (if (phrase in hay) hay.indexOf(phrase) else terms.map { hay.indexOf(it) }.filter { it >= 0 }.min())
                Hit(entry, score, snippet(entry.text, anchor, snippetChars))
            }
            .sortedWith(compareByDescending<Hit> { it.score }.thenByDescending { it.entry.index })
            .take(limit)
            .toList()
    }

    fun format(hits: List<Hit>, total: Int): String {
        if (hits.isEmpty()) return "No matches in the $total messages of this conversation."
        return buildString {
            append("${hits.size} match(es) in $total messages (newest index = latest). Use index to read one in full.\n")
            for (hit in hits) {
                append("\n#").append(hit.entry.index).append(' ').append(label(hit.entry)).append(": ").append(hit.snippet)
            }
        }
    }

    /** The `search_history` tool over [source], read fresh on every call. */
    fun tool(source: () -> List<HistoryEntry>): Tool = object : Tool {
        override val spec = ToolSpec(
            name = NAME,
            description = "Search the full history of this conversation, including parts that were summarized to save " +
                "context. Use it to recover exact earlier details (names, numbers, results, what the user asked) " +
                "instead of guessing. Pass `query` to search, or `index` to read one message in full.",
            parameters = schema {
                string("query", "Words or a phrase to look for")
                integer("index", "Read the message with this index in full")
                integer("limit", "Maximum matches to return (default 8)")
            },
        )
        override val risk = Risk.Read

        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val entries = source()
            args.optInt("index")?.let { index ->
                val entry = entries.firstOrNull { it.index == index } ?: return ToolResult.error("No message #$index.")
                return ToolResult.ok("#$index ${label(entry)}:\n" + entry.text.take(ctx.limits.historyEntryChars))
            }
            val query = args.optString("query")?.takeIf { it.isNotBlank() }
                ?: return ToolResult.error("Pass a query to search, or an index to read.")
            val limit = (args.optInt("limit") ?: 8).coerceIn(1, 20)
            return ToolResult.ok(format(search(entries, query, limit), entries.size))
        }
    }

    const val NAME = "search_history"
    private val WHITESPACE = Regex("\\s+")

    private fun label(entry: HistoryEntry): String = when (entry.role) {
        Role.User -> "user"
        Role.Assistant -> "assistant"
        Role.Tool -> "tool ${entry.toolName ?: ""}".trim()
        Role.System -> "system"
    }

    private fun occurrences(hay: String, term: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val i = hay.indexOf(term, from)
            if (i < 0) return count
            count++
            from = i + term.length
        }
    }

    private fun snippet(text: String, anchor: Int, size: Int): String {
        val flat = text.replace('\n', ' ')
        if (flat.length <= size) return flat
        val start = (anchor - size / 3).coerceIn(0, (flat.length - size).coerceAtLeast(0))
        val end = (start + size).coerceAtMost(flat.length)
        return (if (start > 0) "…" else "") + flat.substring(start, end) + if (end < flat.length) "…" else ""
    }
}
