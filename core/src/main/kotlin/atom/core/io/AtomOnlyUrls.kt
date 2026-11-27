package atom.core.io

/**
 * Addresses only Atom itself can open: the user's websites live at `https://<id>-<tag>.invalid/`, a reserved name
 * that never resolves and that Atom's own WebViews answer from storage. Such a link must never be handed to another
 * app (a browser would look the name up, and learn the site's address): anything that opens links checks
 * [isAtomOnly] and calls [open] instead. Pure apart from [opener], which the app installs at start.
 */
object AtomOnlyUrls {
    private val AUTHORITY = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://([^/?#]*)")

    /** True for any URL whose host is `invalid` or ends in `.invalid`, in any form (port, user name, trailing dot). */
    fun isAtomOnly(url: String?): Boolean {
        val authority = AUTHORITY.find(url?.trim() ?: return false)?.groupValues?.get(1)?.lowercase() ?: return false
        val host = authority.substringAfterLast('@').substringBefore(':').trimEnd('.')
        return host == "invalid" || host.endsWith(".invalid")
    }

    /** Opens [url] inside Atom (the site's preview or Atom's browser). Set once by the app. */
    @Volatile
    var opener: ((String) -> Unit)? = null

    /** Opens [url] inside Atom; returns false when no opener is installed (the link is then simply not opened). */
    fun open(url: String): Boolean {
        val o = opener ?: return false
        runCatching { o(url) }
        return true
    }
}
