package atom.models

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal HTTP GET for model files using the platform [HttpURLConnection].
 *
 * Redirects are followed by hand so that the `Authorization` header is attached on each hop only
 * when that hop is an https Hugging Face host (HF redirects LFS files to signed CDN URLs that must
 * never see the token), and so that https is never downgraded to http.
 */
internal object ModelHttp {

    private const val MAX_REDIRECTS = 10
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

    /** Opens [url] (requesting bytes from [offset]) and returns the connection after the final redirect. */
    fun open(url: String, offset: Long, token: String?, onConnection: (HttpURLConnection) -> Unit): HttpURLConnection {
        var current = URL(url)
        if (current.protocol != "https") throw IOException("Only https downloads are allowed")
        repeat(MAX_REDIRECTS + 1) {
            val conn = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 60_000
                useCaches = false
                setRequestProperty("User-Agent", "Atom-Android/1 (model-manager)")
                setRequestProperty("Accept-Encoding", "identity") // keep byte offsets and lengths exact
                DownloadIo.rangeHeader(offset)?.let { setRequestProperty("Range", it) }
                if (token != null && ModelUrls.isHuggingFaceHost(current.host)) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }
            onConnection(conn)
            val code = conn.responseCode
            if (code !in REDIRECT_CODES) return conn
            val location = conn.getHeaderField("Location")
            conn.disconnect()
            if (location.isNullOrBlank()) throw IOException("Redirect without a location (HTTP $code)")
            val next = URL(current, location)
            if (next.protocol != "https") throw IOException("Refusing to follow a redirect to an insecure link")
            current = next
        }
        throw IOException("Too many redirects")
    }
}
