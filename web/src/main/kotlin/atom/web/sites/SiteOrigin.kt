package atom.web.sites

/**
 * The web origin a site is shown at: `https://<id>-<tag>.invalid/`. Pure, so the rules are unit tested.
 *
 * Nothing listens there. `.invalid` is reserved (RFC 6761) and never resolves, so a request for it can only be
 * answered by Atom's own WebViews, which serve the site's files from storage ([SiteRequests], through
 * `shouldInterceptRequest`). No socket is open, so no other app on the phone can reach the files or run a page on
 * that origin. The origin stays the same for the life of the site (its local storage with it), and differs between
 * sites; the [tag] is new for each site that is created, so a site made again under an old name does not inherit
 * what the deleted one stored in the browser. Each site is its own registrable domain (`invalid` is the top level,
 * as it is not on the public suffix list), so no site can set a cookie another site sees.
 */
object SiteOrigin {
    const val DOMAIN = "invalid"

    /** "https://water-tracker-k3x9q2.invalid" (no trailing slash). */
    fun origin(id: String, tag: String): String = "https://$id-$tag.$DOMAIN"

    /** The site's start page, the address handed to the agent and loaded by the preview. */
    fun url(id: String, tag: String): String = origin(id, tag) + "/"

    private val URL = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://([^/?#]*)(.*)$", RegexOption.DOT_MATCHES_ALL)
    private val LABEL = Regex("^([a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?)-([a-z0-9]{${SiteStore.TAG_LENGTH}})$")

    /**
     * True when [url] points into [DOMAIN] (any scheme, port or form). Every such request is answered by Atom itself,
     * also the malformed ones: none ever goes to the network, and no such link is handed to another app.
     */
    fun isSiteUrl(url: String?): Boolean = atom.core.io.AtomOnlyUrls.isAtomOnly(url)

    /** The site id and tag of a well-formed site URL (https, no port, no user name, one label), else null. */
    fun parse(url: String?): Pair<String, String>? {
        val m = URL.find(url?.trim() ?: return null) ?: return null
        if (!m.groupValues[1].equals("https", ignoreCase = true)) return null
        val authority = m.groupValues[2].lowercase()
        if (!authority.endsWith(".$DOMAIN")) return null
        val label = authority.removeSuffix(".$DOMAIN")
        val parts = LABEL.find(label) ?: return null
        return parts.groupValues[1] to parts.groupValues[2]
    }

    /** The site id of [url], when it is a site URL. */
    fun siteOf(url: String?): String? = parse(url)?.first

    /** Every path a cookie of a site with these files could be set for: "/", each folder and each file. */
    fun cookiePaths(files: List<String>): List<String> {
        val out = linkedSetOf("/")
        for (f in files) {
            val parts = f.split('/')
            for (i in 1 until parts.size) {
                val dir = "/" + parts.take(i).joinToString("/")
                out += dir
                out += "$dir/"
            }
            out += "/$f"
        }
        return out.toList()
    }

    /** The path of a site URL as sent, still percent-encoded and starting with "/" ("" when the URL has none). */
    fun rawPathOf(url: String): String? {
        val m = URL.find(url.trim()) ?: return null
        val rest = m.groupValues[3].substringBefore('#').substringBefore('?')
        return rest.ifEmpty { "/" }.takeIf { it.startsWith("/") }
    }
}

/** Where a link to one of the user's sites opens inside Atom (never in another app). */
object SiteLinks {
    /** The site's preview for its start page; Atom's browser for any other page (or a malformed site address). */
    fun routeFor(url: String): String {
        val site = SiteOrigin.siteOf(url)
        val path = SiteOrigin.rawPathOf(url)
        val query = url.substringAfter('?', "").substringBefore('#')
        return if (site != null && path == "/" && query.isEmpty()) SiteCards.previewRoute(site) else atom.web.WebFeature.browserRoute(url)
    }
}
