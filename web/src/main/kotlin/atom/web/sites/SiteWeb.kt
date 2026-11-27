package atom.web.sites

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import java.util.concurrent.atomic.AtomicReference

/**
 * Serves the user's sites inside one of Atom's WebViews (the preview, the agent browser, the user's browser), at
 * their own `https://<id>-<tag>.sites.invalid` origins ([SiteOrigin]), the way WebViewAssetLoader serves assets.
 * One per WebView: it remembers which page the WebView's main frame shows, so a page of another origin cannot load
 * a site's files ([SiteRequests]).
 *
 * [intercept] runs on a WebView worker thread: it only reads files and this object's own state, never the WebView.
 */
class SiteWeb(context: Context) {
    private val app = context.applicationContext
    private val page = AtomicReference<String?>(null)

    /** The main frame moved to [url]: call from `onPageStarted` and `doUpdateVisitedHistory` (back, forward). */
    fun onMainFrame(url: String?) = page.set(url)

    /** For `WebViewClient.shouldInterceptRequest`: the reply for a site URL, or null for every other URL. */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url?.toString() ?: return null
        if (request.isForMainFrame) page.set(url)
        if (!SiteOrigin.isSiteUrl(url)) return null
        val reply = try {
            SiteRequests.handle(Sites.store(app), url, request.method ?: "GET", request.requestHeaders.orEmpty(), request.isForMainFrame, page.get())
        } catch (e: Exception) {
            null
        } ?: SiteRequests.error(500, "Internal Error")
        return WebResourceResponse(reply.mime, reply.charset, reply.status, reply.reason, reply.headers, reply.open())
    }

    companion object {
        private val main = Handler(Looper.getMainLooper())

        /**
         * Drops what pages of [origin] stored in the WebView profile: quota-managed storage (IndexedDB, Cache
         * Storage, Web SQL) and cookies. Local storage has no per-origin API; a site made again gets a new origin
         * anyway ([SiteStore.originTag]). Callable from any thread.
         */
        fun forget(origin: String) {
            main.post {
                runCatching { WebStorage.getInstance().deleteOrigin(origin) }
                runCatching {
                    val cookies = CookieManager.getInstance()
                    val url = "$origin/"
                    cookies.getCookie(url)?.split(';')?.map { it.substringBefore('=').trim() }?.filter { it.isNotEmpty() }?.forEach { name ->
                        cookies.setCookie(url, "$name=; Max-Age=0; Path=/")
                    }
                    cookies.flush()
                }
            }
        }
    }
}
