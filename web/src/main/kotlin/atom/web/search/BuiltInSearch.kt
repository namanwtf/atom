package atom.web.search

import atom.web.net.HttpResult
import atom.web.net.HttpSpec
import kotlinx.coroutines.CancellationException
import java.net.URLEncoder
import java.util.Locale

/** Renders a page in the app's off-screen agent browser and returns its HTML, or null when it could not. */
fun interface PageRenderer {
    suspend fun html(url: String, timeoutMs: Long): String?
}

/** One keyless results page: how to ask for it, and a strict parser (null = not a results page, e.g. a bot check). */
internal class KeylessSource(
    val name: String,
    val request: (query: String, count: Int, lang: String) -> HttpSpec,
    val parse: (html: String) -> List<SearchResult>?,
)

internal object KeylessSources {
    private fun q(query: String) = URLEncoder.encode(query, "UTF-8")

    private fun headers(lang: String) = mapOf("Accept" to "text/html,application/xhtml+xml", "Accept-Language" to "$lang,en;q=0.8")

    val duckDuckGo = KeylessSource(
        "DuckDuckGo",
        { query, _, lang ->
            HttpSpec("POST", "https://html.duckduckgo.com/html/", headers(lang), "q=" + q(query) + "&kl=", contentType = "application/x-www-form-urlencoded")
        },
        { html -> if (SearchParsers.isDuckDuckGoChallenge(html)) null else SearchParsers.duckDuckGo(html) },
    )

    val duckDuckGoLite = KeylessSource(
        "DuckDuckGo Lite",
        { query, _, lang ->
            HttpSpec("POST", "https://lite.duckduckgo.com/lite/", headers(lang), "q=" + q(query) + "&kl=", contentType = "application/x-www-form-urlencoded")
        },
        SearchParsers::duckDuckGoLite,
    )

    val bing = KeylessSource(
        "Bing",
        { query, count, lang -> HttpSpec("GET", bingUrl(query, count), headers(lang)) },
        SearchParsers::bing,
    )

    val mojeek = KeylessSource(
        "Mojeek",
        { query, _, lang -> HttpSpec("GET", "https://www.mojeek.com/search?q=" + q(query), headers(lang)) },
        SearchParsers::mojeek,
    )

    /** Tried in this order, one request each; the first with results wins. */
    val http: List<KeylessSource> = listOf(duckDuckGo, duckDuckGoLite, bing, mojeek)

    fun bingUrl(query: String, count: Int) = "https://www.bing.com/search?q=" + q(query) + "&count=" + count.coerceIn(1, 20)

    /** Pages the agent browser loads, in order, when no direct request got results. */
    fun browserPages(query: String, count: Int): List<Pair<String, (String) -> List<SearchResult>?>> = listOf(
        bingUrl(query, count) to SearchParsers::bing,
        ("https://html.duckduckgo.com/html/?q=" + q(query)) to { html: String -> if (SearchParsers.isDuckDuckGoChallenge(html)) null else SearchParsers.duckDuckGo(html) },
    )

    fun wikipediaUrl(query: String, n: Int, lang: String) =
        "https://$lang.wikipedia.org/w/rest.php/v1/search/page?q=" + q(query) + "&limit=$n"
}

/**
 * The keyless built-in search. Public results pages are tried one after another with one request each (DuckDuckGo,
 * DuckDuckGo Lite, Bing, Mojeek); when none answers with results, the agent browser loads a results page off-screen
 * and the same parsers read it; Wikipedia is the last resort. The result says which source answered.
 */
internal class BuiltInSearch(
    private val fetch: suspend (HttpSpec) -> HttpResult,
    private val renderer: () -> PageRenderer?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val lang: () -> String = { Locale.getDefault().language.takeIf { it.length == 2 } ?: "en" },
) {
    suspend fun search(query: String, n: Int): SearchOutcome {
        val started = clock()
        fun elapsed() = clock() - started
        val language = lang()
        val tried = ArrayList<String>()
        var answeredEmpty: String? = null

        for (source in KeylessSources.http) {
            if (elapsed() > HTTP_BUDGET_MS) break
            tried += source.name
            val results = attempt {
                val r = fetch(source.request(query, n, language))
                if (r.ok) source.parse(r.text) else null
            } ?: continue
            if (results.isNotEmpty()) return SearchOutcome(results.take(n), source.name)
            answeredEmpty = answeredEmpty ?: source.name
        }

        val browser = renderer()
        if (browser != null && elapsed() < BROWSER_START_BEFORE_MS) {
            tried += "the agent browser"
            for ((url, parse) in KeylessSources.browserPages(query, n)) {
                val results = attempt { browser.html(url, BROWSER_PAGE_TIMEOUT_MS)?.let(parse) } ?: continue
                if (results.isNotEmpty()) return SearchOutcome(results.take(n), "Agent browser (" + WebServices.host(url).removePrefix("www.").substringBefore('.').let { sourceName(it) } + ")")
                answeredEmpty = answeredEmpty ?: "the agent browser"
            }
        }

        if (elapsed() < WIKIPEDIA_START_BEFORE_MS) {
            tried += "Wikipedia"
            val wiki = attempt { wikipedia(query, n, language) }?.takeIf { it.isNotEmpty() }
                ?: if (language != "en" && elapsed() < WIKIPEDIA_START_BEFORE_MS) attempt { wikipedia(query, n, "en") }?.takeIf { it.isNotEmpty() } else null
            if (!wiki.isNullOrEmpty()) {
                return SearchOutcome(
                    wiki,
                    "Wikipedia",
                    "General web search was unavailable, so these are Wikipedia results. For other sites, use the browser tools (browser_open with a search URL).",
                )
            }
        }

        if (answeredEmpty != null) return SearchOutcome(emptyList(), answeredEmpty)
        throw WebException(
            "Built-in search is unavailable right now (tried ${tried.joinToString(", ")}). " +
                (if (browser == null) "Try the browser (browser_open https://www.bing.com/search?q=...) or s" else "S") +
                "et a search service in Settings > Web search and reading.",
        )
    }

    private suspend fun wikipedia(query: String, n: Int, lang: String): List<SearchResult>? {
        val r = fetch(
            HttpSpec(
                "GET",
                KeylessSources.wikipediaUrl(query, n, lang),
                mapOf("User-Agent" to "AtomAgent/1.0 (Android on-device assistant; bot)", "Api-User-Agent" to "AtomAgent/1.0"),
            ),
        )
        return if (r.ok) SearchParsers.wikipedia(r.text, lang) else null
    }

    private fun sourceName(id: String) = when (id) {
        "bing" -> "Bing"
        "html", "duckduckgo" -> "DuckDuckGo"
        else -> id
    }

    /** A failed source (network error, timeout, unreadable page) is skipped; cancellation still stops the search. */
    private suspend inline fun <T> attempt(block: () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        /** No further direct request starts after this; each has its own short timeout (Http.quickClient). */
        const val HTTP_BUDGET_MS = 20_000L
        const val BROWSER_START_BEFORE_MS = 30_000L
        const val BROWSER_PAGE_TIMEOUT_MS = 10_000L
        const val WIKIPEDIA_START_BEFORE_MS = 45_000L
    }
}
