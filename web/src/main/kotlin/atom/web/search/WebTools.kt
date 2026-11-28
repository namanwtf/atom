package atom.web.search

import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolContext
import atom.core.tool.ToolResult
import atom.core.tool.optBoolean
import atom.core.tool.optInt
import atom.core.tool.schema
import atom.core.tool.string
import atom.web.BaseTool
import kotlinx.serialization.json.JsonObject

internal object WebTools {
    /** Results one web_search may return. */
    const val MAX_RESULTS = 20

    fun create(services: WebServices, gate: () -> String?): List<Tool> = listOf(Search(services, gate), ReadPage(services, gate))

    private class Search(private val services: WebServices, gate: () -> String?) : BaseTool(
        "web_search",
        "Search the web. Returns titles, links and snippets. Use for facts, news, prices, docs; then read_page on the best links.",
        schema {
            string("query", "Search terms", required = true)
            integer("count", "Number of results, 1-20 (default 8)")
        },
        Risk.Read,
        gate,
    ) {
        // The built-in search may try several sources and then the agent browser; each step has its own timeout.
        override val timeoutMs: Long get() = 75_000

        override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
            val query = args.string("query").trim()
            if (query.isEmpty()) return ToolResult.error("Empty query")
            val out = services.search(query, (args.optInt("count") ?: 8).coerceIn(1, MAX_RESULTS))
            if (out.results.isEmpty()) return ToolResult.ok("No results for \"$query\" (${out.source}).")
            val text = buildString {
                append("Results for \"").append(query).append("\" (").append(out.source).append("):\n")
                out.results.forEachIndexed { i, r ->
                    append('\n').append(i + 1).append(". ").append(r.title.ifBlank { r.url }).append('\n').append("   ").append(r.url)
                    if (r.snippet.isNotBlank()) append('\n').append("   ").append(r.snippet.take(300))
                }
                out.note?.let { append("\n\nNote: ").append(it) }
            }
            return ToolResult.ok(text)
        }
    }

    private class ReadPage(private val services: WebServices, gate: () -> String?) : BaseTool(
        "read_page",
        "Fetch a web page and return its readable main text (no scripts, menus or ads), in pages. Fast and light; " +
            "for pages that need JavaScript, login or interaction, use the browser_* tools instead.",
        schema {
            string("url", "http(s) URL", required = true)
            integer("offset", "Character offset to continue from (from the previous page's footer)")
            integer("max_chars", "Characters per page, 1000-40000 (default 12000)")
            boolean("include_links", "Append the links found in the main text")
        },
        Risk.Read,
        gate,
    ) {
        override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
            var url = args.string("url").trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
            val page = services.read(url)
            val p = Paging.page(page.text, args.optInt("offset") ?: 0, (args.optInt("max_chars") ?: 12_000).coerceIn(1000, 40_000))
            val text = buildString {
                if (page.title.isNotBlank()) append("Title: ").append(page.title).append('\n')
                append("URL: ").append(page.url).append('\n')
                append("Source: ").append(page.source).append('\n')
                page.note?.let { append("Note: ").append(it).append('\n') }
                append('\n')
                if (page.text.isBlank()) append("(The page has no readable text. It may need JavaScript; try browser_open.)") else append(p.text)
                append(p.footer())
                if (args.optBoolean("include_links") == true) {
                    if (page.links.isEmpty()) {
                        append("\n\n(No links available from this reader; browser_extract with links=true lists them.)")
                    } else {
                        append("\n\nLinks:")
                        page.links.take(60).forEach { append("\n- ").append(it.text).append(" — ").append(it.url) }
                    }
                }
            }
            return ToolResult.ok(text)
        }
    }
}
