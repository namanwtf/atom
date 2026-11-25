package atom.web.search

import atom.web.ProviderKeys
import atom.web.SecretStore
import atom.web.net.Http
import atom.web.net.HttpSpec
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

data class SearchResult(val title: String, val url: String, val snippet: String)

data class SearchOutcome(val results: List<SearchResult>, val source: String, val note: String? = null)

data class PageText(
    val url: String,
    val title: String,
    val text: String,
    val source: String,
    val note: String? = null,
    val links: List<ReadableLink> = emptyList(),
)

class WebException(message: String) : Exception(message)

/** Pure parsers for the built-in sources, kept separate so they can be tested on fixtures. */
object SearchParsers {
    fun duckDuckGo(html: String): List<SearchResult> {
        val root = Html.parse(html)
        val out = ArrayList<SearchResult>()
        val blocks = root.walk().filter { n -> n.classes().any { it == "result" || it == "web-result" } }.toList()
        val candidates = if (blocks.isNotEmpty()) blocks else listOf(root)
        for (block in candidates) {
            val anchors = block.walk().filter { it.tag == "a" && it.classes().contains("result__a") }.toList()
            for (a in anchors) {
                val url = unwrapDuckLink(a.attr("href") ?: continue) ?: continue
                if (url.contains("duckduckgo.com/y.js") || url.contains("/y.js?")) continue
                val snippet = (if (blocks.isNotEmpty()) block else a.parent ?: block).walk()
                    .firstOrNull { n -> n.classes().contains("result__snippet") }?.text().orEmpty()
                out += SearchResult(clean(a.text()), url, clean(snippet))
            }
        }
        return out.distinctBy { it.url }
    }

    fun isDuckDuckGoChallenge(html: String): Boolean =
        html.contains("anomaly-modal", true) || html.contains("challenge-form", true) ||
            (html.contains("bots", true) && !html.contains("result__a"))

    fun unwrapDuckLink(href: String): String? {
        val h = Entities.decode(href.trim())
        val abs = if (h.startsWith("//")) "https:$h" else if (h.startsWith("/")) "https://duckduckgo.com$h" else h
        val query = runCatching { URI(abs).rawQuery }.getOrNull()
        if (abs.contains("duckduckgo.com/l/") && query != null) {
            val p = query.split('&').firstOrNull { it.startsWith("uddg=") } ?: return null
            return URLDecoder.decode(p.removePrefix("uddg="), "UTF-8")
        }
        return abs.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    fun wikipedia(json: String, lang: String): List<SearchResult> {
        val root = JsonPath.parse(json)
        return JsonPath.items(root, "pages").mapNotNull { p ->
            val key = JsonPath.string(p, "key") ?: return@mapNotNull null
            val title = JsonPath.string(p, "title") ?: key
            val excerpt = JsonPath.string(p, "excerpt").orEmpty()
            val desc = JsonPath.string(p, "description")
            val snippet = listOfNotNull(desc, clean(Html.parse(excerpt).text())).filter { it.isNotBlank() }.joinToString(" — ")
            SearchResult(title, "https://$lang.wikipedia.org/wiki/" + URLEncoder.encode(key, "UTF-8").replace("+", "_"), snippet)
        }
    }

    fun custom(json: String, ep: CustomEndpoint): List<SearchResult> {
        val root = JsonPath.parse(json)
        return JsonPath.items(root, ep.resultsPath).mapNotNull { item ->
            val link = JsonPath.string(item, ep.linkPath) ?: return@mapNotNull null
            SearchResult(
                JsonPath.string(item, ep.titlePath)?.let(::clean) ?: link,
                link,
                JsonPath.string(item, ep.snippetPath)?.let(::clean).orEmpty(),
            )
        }
    }

    fun customPage(json: String, ep: CustomEndpoint): Pair<String, String>? {
        val root = JsonPath.parse(json)
        val base = if (ep.resultsPath.isBlank()) root else JsonPath.items(root, ep.resultsPath).firstOrNull() ?: return null
        val body = JsonPath.string(base, ep.bodyPath) ?: return null
        return (JsonPath.string(base, ep.titlePath).orEmpty()) to body
    }

    private fun clean(s: String) = s.replace(Regex("\\s+"), " ").trim()

    private fun Node.classes(): List<String> = attr("class")?.split(' ', '\t', '\n')?.filter { it.isNotEmpty() } ?: emptyList()
}

/** Builds the HTTP requests for the configurable backends. Pure, so it is unit-tested. */
object WebRequests {
    fun deepSeekSearch(base: String, key: String, query: String, count: Int) = HttpSpec(
        "POST",
        base.trimEnd('/').removeSuffix("/v1") + "/v1/tools/search",
        mapOf("Authorization" to "Bearer $key"),
        """{"query":"${Template.escape(query.take(128))}","count":$count}""",
    )

    fun deepSeekFetch(base: String, key: String, url: String) = HttpSpec(
        "POST",
        base.trimEnd('/').removeSuffix("/v1") + "/v1/tools/fetch",
        mapOf("Authorization" to "Bearer $key"),
        """{"url":"${Template.escape(url)}"}""",
    )

    fun custom(ep: CustomEndpoint, key: String?, values: Map<String, String>): HttpSpec {
        val method = ep.method.uppercase().ifBlank { "GET" }
        val headers = LinkedHashMap<String, String>()
        if (!key.isNullOrBlank()) headers["Authorization"] = "Bearer $key"
        ep.headers.filter { it.name.isNotBlank() }.forEach { headers[it.name.trim()] = Template.url(it.value, emptyMap()) }
        val body = if (method == "GET" || method == "DELETE" || ep.bodyTemplate.isBlank()) null else Template.json(ep.bodyTemplate, values)
        if (body != null) runCatching { JsonPath.parse(body) }.onFailure { throw WebException("The request body template is not valid JSON after filling placeholders.") }
        return HttpSpec(method, Template.url(ep.url, values), headers, body)
    }
}

/** Runs searches and page reads against whichever services the user picked. */
class WebServices(
    private val config: () -> WebConfig,
    private val keys: ProviderKeys,
    private val secrets: SecretStore,
) {
    private val pageCache = object : LinkedHashMap<String, PageText>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PageText>?) = size > 8
    }

    suspend fun search(query: String, count: Int): SearchOutcome {
        val cfg = config()
        val n = count.coerceIn(1, 20)
        return when (cfg.search) {
            ServiceKind.BuiltIn -> builtInSearch(query, n)
            ServiceKind.DeepSeek -> {
                val (base, key) = deepSeek(cfg)
                val r = Http.execute(WebRequests.deepSeekSearch(base, key, query, n))
                if (!r.ok) throw WebException("DeepSeek search failed (${Http.describeError(r)})")
                val items = JsonPath.items(JsonPath.parse(r.text), "results").map {
                    SearchResult(
                        JsonPath.string(it, "title").orEmpty(),
                        JsonPath.string(it, "url").orEmpty(),
                        (JsonPath.string(it, "snippet") ?: JsonPath.string(it, "summary")).orEmpty().take(400),
                    )
                }.filter { it.url.isNotBlank() }
                SearchOutcome(items.take(n), "DeepSeek")
            }
            ServiceKind.Custom -> {
                val ep = cfg.searchEndpoint
                if (!ep.configured) throw WebException("The custom search endpoint has no URL. Set it in Settings > Web search and reading.")
                val r = Http.execute(WebRequests.custom(ep, secrets.get(WebSecrets.SEARCH_KEY), mapOf("query" to query, "count" to n.toString())))
                if (!r.ok) throw WebException("Custom search failed (${Http.describeError(r)})")
                SearchOutcome(SearchParsers.custom(r.text, ep).take(n), "Custom endpoint")
            }
        }
    }

    private suspend fun builtInSearch(query: String, n: Int): SearchOutcome {
        val ddg = runCatching {
            val r = Http.execute(
                HttpSpec(
                    "POST",
                    "https://html.duckduckgo.com/html/",
                    mapOf("Accept" to "text/html", "Accept-Language" to Locale.getDefault().toLanguageTag()),
                    "q=" + URLEncoder.encode(query, "UTF-8") + "&kl=",
                    contentType = "application/x-www-form-urlencoded",
                ),
                maxBytes = 2 * 1024 * 1024,
            )
            if (!r.ok || SearchParsers.isDuckDuckGoChallenge(r.text)) null else SearchParsers.duckDuckGo(r.text)
        }.getOrNull()
        if (!ddg.isNullOrEmpty()) return SearchOutcome(ddg.take(n), "DuckDuckGo")
        val lang = Locale.getDefault().language.takeIf { it.length == 2 } ?: "en"
        val wiki = runCatching { wikipedia(query, n, lang) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: runCatching { if (lang != "en") wikipedia(query, n, "en") else null }.getOrNull()
        if (!wiki.isNullOrEmpty()) {
            return SearchOutcome(
                wiki,
                "Wikipedia",
                "General web search was unavailable, so these are Wikipedia results. For other sites, use the browser tools (browser_open with a search URL).",
            )
        }
        throw WebException(
            "Built-in search is unavailable right now. Try the browser (browser_open https://www.google.com/search?q=...) or set a search service in Settings > Web search and reading.",
        )
    }

    private suspend fun wikipedia(query: String, n: Int, lang: String): List<SearchResult> {
        val url = "https://$lang.wikipedia.org/w/rest.php/v1/search/page?q=" + URLEncoder.encode(query, "UTF-8") + "&limit=$n"
        val r = Http.get(url, mapOf("User-Agent" to "AtomAgent/1.0 (Android on-device assistant; bot)", "Api-User-Agent" to "AtomAgent/1.0"))
        if (!r.ok) return emptyList()
        return SearchParsers.wikipedia(r.text, lang)
    }

    suspend fun read(url: String): PageText {
        synchronized(pageCache) { pageCache[url] }?.let { return it }
        val cfg = config()
        val page = when (cfg.read) {
            ServiceKind.BuiltIn -> readLocal(url)
            ServiceKind.DeepSeek -> runCatching {
                val (base, key) = deepSeek(cfg)
                val r = Http.execute(WebRequests.deepSeekFetch(base, key, url))
                if (!r.ok) throw WebException(Http.describeError(r))
                val el = JsonPath.parse(r.text)
                val text = JsonPath.string(el, "content") ?: throw WebException("empty response")
                PageText(url, JsonPath.string(el, "title").orEmpty(), text, "DeepSeek")
            }.getOrElse { e -> readLocal(url).copy(note = "DeepSeek reading failed (${e.message}); fetched the page directly instead.") }
            ServiceKind.Custom -> runCatching {
                val ep = cfg.readEndpoint
                if (!ep.configured) throw WebException("no URL configured")
                val r = Http.execute(WebRequests.custom(ep, secrets.get(WebSecrets.READ_KEY), mapOf("url" to url)))
                if (!r.ok) throw WebException(Http.describeError(r))
                val (title, body) = SearchParsers.customPage(r.text, ep) ?: throw WebException("no text at '${ep.bodyPath}'")
                PageText(url, title, body, "Custom endpoint")
            }.getOrElse { e -> readLocal(url).copy(note = "The custom reader failed (${e.message}); fetched the page directly instead.") }
        }
        synchronized(pageCache) { pageCache[url] = page }
        return page
    }

    suspend fun readLocal(url: String): PageText {
        val r = Http.get(
            url,
            mapOf(
                "Accept" to "text/html,application/xhtml+xml,text/plain,application/json;q=0.9,*/*;q=0.5",
                "Accept-Language" to Locale.getDefault().toLanguageTag() + ",en;q=0.8",
            ),
            maxBytes = 4 * 1024 * 1024,
        )
        if (!r.ok) throw WebException("Could not load the page (HTTP ${r.code}).")
        val type = r.contentType.orEmpty().lowercase()
        val charset = Regex("charset=([\\w-]+)").find(type)?.groupValues?.get(1)
        val raw = runCatching { String(r.body, charset(charset ?: sniffCharset(r.body))) }.getOrElse { r.text }
        return when {
            type.contains("pdf") -> throw WebException("This is a PDF; it cannot be read as text here. Try browser_open to view it.")
            type.startsWith("image/") || type.startsWith("video/") || type.startsWith("audio/") ->
                throw WebException("This link is a ${type.substringBefore(';')} file, not a page.")
            type.contains("html") || type.isBlank() && raw.trimStart().startsWith("<") -> {
                val doc = Readable.extract(raw, r.finalUrl)
                PageText(r.finalUrl, doc.title, doc.text, "Direct fetch", links = doc.links)
            }
            else -> PageText(r.finalUrl, "", raw, "Direct fetch")
        }
    }

    private fun sniffCharset(bytes: ByteArray): String {
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
        return Regex("<meta[^>]+charset=[\"']?([\\w-]+)", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1) ?: "UTF-8"
    }

    private suspend fun deepSeek(cfg: WebConfig): Pair<String, String> {
        val override = secrets.get(WebSecrets.DEEPSEEK_KEY)
        val provider = keys.all().firstOrNull { host(it.baseUrl).endsWith("deepseek.com") }
        val key = override?.takeIf { it.isNotBlank() } ?: provider?.apiKey
            ?: throw WebException("No DeepSeek key. Add a DeepSeek provider or enter a key in Settings > Web search and reading.")
        val base = cfg.deepSeekBaseUrl.ifBlank { provider?.baseUrl ?: "https://api.deepseek.com" }
        return base to key
    }

    fun clearCache() = synchronized(pageCache) { pageCache.clear() }

    companion object {
        fun host(url: String): String = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
    }
}
