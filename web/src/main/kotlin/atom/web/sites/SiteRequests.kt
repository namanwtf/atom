package atom.web.sites

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** One answer to a request for a site URL, in the shape of a WebView `WebResourceResponse`. */
class SiteReply(
    val status: Int,
    val reason: String,
    /** "text/html", without parameters. */
    val mime: String,
    /** "utf-8" for text types, null for binary ones. */
    val charset: String?,
    val headers: Map<String, String>,
    private val body: () -> InputStream,
) {
    fun open(): InputStream = body()

    fun text(): String = open().use { it.readBytes().toString(Charsets.UTF_8) }
}

/**
 * Answers the requests Atom's WebViews make for site URLs ([SiteOrigin]): the site's own files from [SiteStore],
 * read-only, and nothing else. Pure JVM, so every rule is unit tested.
 *
 * - Only `https://<id>-<tag>.sites.invalid` of a site that exists, with the tag it has now; anything else under
 *   `sites.invalid` is answered here too (with an error), so it never reaches the network.
 * - Only to that site itself: a page of any other origin (another site, or the web) gets 403 for every file of it,
 *   in an iframe, a script tag or a fetch. Its top-level navigation is allowed; the site then runs in its own origin.
 * - GET and HEAD only. Paths are decoded once, strictly; "..", backslashes and NUL are refused, hidden names are
 *   404, and a symbolic link anywhere on the way, or a path that resolves outside the site, is 403.
 * - Nothing is cached (`Cache-Control: no-store`), types are never sniffed, no referrer leaves the site.
 */
object SiteRequests {
    /**
     * The reply for [url], or null when it is not a site URL (the WebView then loads it as usual). [pageUrl] is the
     * URL the requesting WebView's main frame shows (for a main-frame request, [url] itself).
     */
    fun handle(store: SiteStore, url: String, method: String, headers: Map<String, String>, mainFrame: Boolean, pageUrl: String?): SiteReply? {
        if (!SiteOrigin.isSiteUrl(url)) return null
        val (id, tag) = SiteOrigin.parse(url) ?: return error(400, "Bad Request")
        val h = headers.mapKeys { it.key.lowercase() }
        if (!mainFrame) {
            // Only the site's own pages may load its files.
            val page = SiteOrigin.parse(pageUrl)
            if (page == null || page != id to tag) return error(403, "Forbidden")
            val fetchSite = h["sec-fetch-site"]?.lowercase()
            if (fetchSite != null && fetchSite != "same-origin" && fetchSite != "none") return error(403, "Forbidden")
        }
        h["origin"]?.let { if (it != SiteOrigin.origin(id, tag)) return error(403, "Forbidden") }
        if (!method.equals("GET", ignoreCase = true) && !method.equals("HEAD", ignoreCase = true)) {
            return error(405, "Method Not Allowed", extra = mapOf("Allow" to "GET, HEAD"))
        }
        val head = method.equals("HEAD", ignoreCase = true)
        if (!store.exists(id) || store.existingOriginTag(id) != tag) {
            return html(404, "Not Found", page("This site no longer exists."), head)
        }
        val rawPath = SiteOrigin.rawPathOf(url) ?: return error(400, "Bad Request")
        return when (val r = resolve(store.dir(id), rawPath)) {
            is Resolved.Refused -> error(r.status, r.reason, head)
            // A WebView reply cannot redirect: a small page sends the browser to the folder's address instead.
            is Resolved.Redirect -> html(200, "OK", redirectPage(r.location), head)
            is Resolved.Missing -> html(404, "Not Found", page("Nothing here yet: ${escape(percentDecode(rawPath) ?: rawPath)}"), head)
            is Resolved.Found -> file(r.file, head)
        }
    }

    sealed interface Resolved {
        data class Found(val file: File) : Resolved
        data class Redirect(val location: String) : Resolved
        data object Missing : Resolved
        data class Refused(val status: Int, val reason: String) : Resolved
    }

    /** Maps a URL path (still percent-encoded, starting with "/") to a file under [root]. */
    fun resolve(root: File, rawPath: String): Resolved {
        val decoded = percentDecode(rawPath) ?: return Resolved.Refused(400, "Bad Request")
        if (!decoded.startsWith("/") || '\u0000' in decoded || '\\' in decoded) return Resolved.Refused(400, "Bad Request")
        val segments = ArrayList<String>()
        for (s in decoded.split('/')) {
            when {
                s.isEmpty() || s == "." -> Unit
                s == ".." -> return Resolved.Refused(403, "Forbidden")
                s.startsWith(".") -> return Resolved.Missing
                else -> segments += s
            }
        }
        if (SitePaths.isLink(root)) return Resolved.Refused(403, "Forbidden")
        var f = root
        for (s in segments) {
            f = File(f, s)
            if (SitePaths.isLink(f)) return Resolved.Refused(403, "Forbidden")
        }
        val base = root.canonicalPath
        val canonical = f.canonicalPath
        if (canonical != base && !canonical.startsWith(base + File.separator)) return Resolved.Refused(403, "Forbidden")
        if (SitePaths.isDirectory(f)) {
            if (!rawPath.endsWith("/")) return Resolved.Redirect("$rawPath/")
            val index = File(f, "index.html")
            if (SitePaths.isLink(index)) return Resolved.Refused(403, "Forbidden")
            return if (SitePaths.isRegularFile(index)) Resolved.Found(index) else Resolved.Missing
        }
        return if (SitePaths.isRegularFile(f)) Resolved.Found(f) else Resolved.Missing
    }

    /** Strict percent-decoding into UTF-8: a bad escape or invalid UTF-8 gives null. "+" stays "+". */
    fun percentDecode(s: String): String? {
        val bytes = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length) return null
                val hi = Character.digit(s[i + 1], 16)
                val lo = Character.digit(s[i + 2], 16)
                if (hi < 0 || lo < 0) return null
                bytes.write(hi * 16 + lo)
                i += 3
            } else {
                if (c.code > 0x7f) bytes.write(c.toString().toByteArray(Charsets.UTF_8)) else bytes.write(c.code)
                i++
            }
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }

    private val BASE_HEADERS = mapOf(
        "Cache-Control" to "no-store",
        "X-Content-Type-Options" to "nosniff",
        "Referrer-Policy" to "no-referrer",
    )

    private fun file(f: File, head: Boolean): SiteReply {
        val (mime, charset) = split(SitePaths.mimeOf(f.name))
        return SiteReply(200, "OK", mime, charset, BASE_HEADERS) {
            val empty: InputStream = ByteArrayInputStream(ByteArray(0))
            if (head) empty else runCatching<InputStream> { f.inputStream() }.getOrDefault(empty)
        }
    }

    private fun html(status: Int, reason: String, body: String, head: Boolean) =
        SiteReply(status, reason, "text/html", "utf-8", BASE_HEADERS) { ByteArrayInputStream(if (head) ByteArray(0) else body.toByteArray()) }

    /** A plain-text refusal. Also for a request that failed in a way nobody expected. */
    fun error(status: Int, reason: String, head: Boolean = false, extra: Map<String, String> = emptyMap()) =
        SiteReply(status, reason, "text/plain", "utf-8", BASE_HEADERS + extra) { ByteArrayInputStream(if (head) ByteArray(0) else reason.toByteArray()) }

    private fun split(type: String): Pair<String, String?> {
        val mime = type.substringBefore(';').trim()
        val charset = type.substringAfter("charset=", "").trim().takeIf { it.isNotEmpty() }
        return mime to charset
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun page(text: String) =
        "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<title>Not found</title><style>body{font-family:sans-serif;margin:2rem;color:#666}</style></head>" +
            "<body><p>$text</p></body></html>"

    private fun redirectPage(location: String): String {
        val safe = escape(location)
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta http-equiv=\"refresh\" content=\"0;url=$safe\">" +
            "<title></title></head><body><a href=\"$safe\">$safe</a></body></html>"
    }
}
