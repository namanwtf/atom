package atom.device.tools

import java.util.Locale

/**
 * Keeps websites inside Atom. Reading, searching or browsing a site belongs to Atom's own web tools (web_search,
 * read_page) and its built-in agent browser (browser_open and friends); `open_url` hands a link to another app
 * outside Atom, which for a plain web page means the user's external browser. Pure, so it is unit tested.
 */
object WebLinkRouting {
    /** Schemes `open_url` never opens. */
    val BLOCKED_SCHEMES = setOf("file", "content", "javascript", "intent")

    private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")

    /** [raw] with `https://` added when it has no scheme (a bare "example.com"). */
    fun withScheme(raw: String): String {
        val t = raw.trim()
        return if (SCHEME.containsMatchIn(t)) t else "https://$t"
    }

    fun schemeOf(raw: String): String? = SCHEME.find(withScheme(raw))?.groupValues?.get(1)?.lowercase(Locale.ROOT)

    fun isWebPage(raw: String): Boolean = schemeOf(raw) in setOf("http", "https")

    /**
     * Why `open_url` must not open [raw], or null when it may. A plain http(s) page is refused unless [external]
     * is set, which the model does only when the user asked for their own browser or a specific app.
     */
    fun openUrlRefusal(raw: String, external: Boolean): String? {
        if (atom.core.io.AtomOnlyUrls.isAtomOnly(withScheme(raw.trim()))) return SITE_REFUSAL
        val scheme = schemeOf(raw)
        if (scheme in BLOCKED_SCHEMES) return "Opening $scheme: links is not allowed."
        if (scheme in setOf("http", "https") && !external) return WEB_PAGE_REFUSAL
        return null
    }

    const val SITE_REFUSAL =
        "Not opened: this is the address of one of the user's own sites, which only Atom can open; another app would " +
            "never reach it. Show it with site_preview, or open it in Atom's agent browser with browser_open."

    const val WEB_PAGE_REFUSAL =
        "Not opened: open_url would hand this web page to another app outside Atom (the user's external browser). " +
            "To read, search, summarize or browse a website, use Atom's built-in agent browser (browser_open, which " +
            "the user can watch live in the chat and take over) or web_search / read_page. Only if the user asked to " +
            "open it in their own browser or in a specific app, call open_url again with external=true."

    /** Names the user may give Atom's own browser; none of them is an installed app. */
    private val BROWSER_ALIASES = setOf(
        "agent browser", "atom browser", "atoms browser", "atom s browser", "built in browser", "builtin browser", "internal browser",
        "in app browser", "inapp browser", "atom agent browser", "agentbrowser",
    )

    /** True when [query] (an app name given to open_app) means Atom's built-in agent browser. */
    fun meansBuiltInBrowser(query: String): Boolean {
        val q = query.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")
        if (q.isEmpty()) return false
        return q in BROWSER_ALIASES || q.removePrefix("the ").removePrefix("open ") in BROWSER_ALIASES
    }

    const val BUILT_IN_BROWSER_HINT =
        "\"Agent browser\" (also \"Atom browser\" or \"built-in browser\") is not an installed app: it is Atom's own " +
            "browser. Use browser_open with the address or search terms; the user watches it live in the chat."
}
