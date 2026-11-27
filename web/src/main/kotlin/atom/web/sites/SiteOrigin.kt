package atom.web.sites

/**
 * The web origin a site is shown at: `https://<id>-<tag>.sites.invalid/`. Pure, so the rules are unit tested.
 *
 * Nothing listens there. `.invalid` is reserved (RFC 6761) and never resolves, so a request for it can only be
 * answered by Atom's own WebViews, which serve the site's files from storage ([SiteRequests], through
 * `shouldInterceptRequest`). No socket is open, so no other app on the phone can reach the files or run a page on
 * that origin. The origin stays the same for the life of the site (its local storage with it), and differs between
 * sites; the [tag] is new for each site that is created, so a site made again under an old name does not inherit
 * what the deleted one stored in the browser.
 */
object SiteOrigin {
    const val DOMAIN = "sites.invalid"

    /** "https://water-tracker-k3x9q2.sites.invalid" (no trailing slash). */
    fun origin(id: String, tag: String): String = "https://$id-$tag.$DOMAIN"

    /** The site's start page, the address handed to the agent and loaded by the preview. */
    fun url(id: String, tag: String): String = origin(id, tag) + "/"

    private val URL = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://([^/?#]*)(.*)$", RegexOption.DOT_MATCHES_ALL)
    private val LABEL = Regex("^([a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?)-([a-z0-9]{${SiteStore.TAG_LENGTH}})$")

    /**
     * The authority of [url] when it points into [DOMAIN] (any scheme, port or form), lower-cased, else null. Every
     * such request is answered by Atom itself, also the malformed ones: none ever goes to the network.
     */
    fun authorityOf(url: String?): String? {
        val m = URL.find(url?.trim() ?: return null) ?: return null
        val authority = m.groupValues[2].lowercase()
        val host = authority.substringAfterLast('@').substringBefore(':').trimEnd('.')
        return authority.takeIf { host == DOMAIN || host.endsWith(".$DOMAIN") }
    }

    fun isSiteUrl(url: String?): Boolean = authorityOf(url) != null

    /** The site id and tag of a well-formed site URL (https, no port, no user name), else null. */
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

    /** The path of a site URL as sent, still percent-encoded and starting with "/" ("" when the URL has none). */
    fun rawPathOf(url: String): String? {
        val m = URL.find(url.trim()) ?: return null
        val rest = m.groupValues[3].substringBefore('#').substringBefore('?')
        return rest.ifEmpty { "/" }.takeIf { it.startsWith("/") }
    }
}
