package atom.web.sites

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import java.util.concurrent.atomic.AtomicReference

/**
 * Serves the user's sites inside one of Atom's WebViews (the preview, the agent browser, the user's browser), at
 * their own `https://<id>-<tag>.invalid` origins ([SiteOrigin]), the way WebViewAssetLoader serves assets.
 * One per WebView: it remembers which page the WebView's main frame shows, so a page of another origin cannot load
 * a site's files ([SiteRequests]).
 *
 * [intercept] runs on a WebView worker thread: it only reads files and this object's own state, never the WebView.
 */
class SiteWeb(context: Context) {
    private val app = context.applicationContext
    private val page = AtomicReference<String?>(null)

    init {
        installServiceWorkerClient()
    }

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

        @Volatile
        private var workersCovered = false

        /**
         * Once per process, before any site loads: requests that service workers make go through
         * [SiteRequests.serviceWorker], so a site address never reaches the network from a worker either.
         */
        private fun installServiceWorkerClient() {
            if (workersCovered) return
            synchronized(this) {
                if (workersCovered) return
                runCatching {
                    ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
                        override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                            val url = request.url?.toString() ?: return null
                            val reply = SiteRequests.serviceWorker(url) ?: return null
                            return WebResourceResponse(reply.mime, reply.charset, reply.status, reply.reason, reply.headers, reply.open())
                        }
                    })
                    workersCovered = true
                }
            }
        }

        /**
         * Drops what pages of [origin] stored in the WebView profile: its storage ([WebStorage.deleteOrigin]: IndexedDB,
         * Cache Storage, file systems, Web SQL) and its cookies on every path of the site ([SiteOrigin.cookiePaths], from its files
         * and folders), host-only or for the site's own domain. Each site is its own registrable domain, so no other
         * site's cookie is touched and none of its cookies reached another site. Local storage has no per-origin API;
         * a site made again gets a new origin anyway ([SiteStore.originTag]). Callable from any thread.
         */
        fun forget(origin: String, cookiePaths: Collection<String> = listOf("/")) {
            val host = origin.substringAfter("://")
            main.post {
                runCatching { WebStorage.getInstance().deleteOrigin(origin) }
                runCatching {
                    val cookies = CookieManager.getInstance()
                    for (path in (cookiePaths + "/").distinct()) {
                        val url = origin + path
                        val names = cookies.getCookie(url)?.split(';')?.map { it.substringBefore('=').trim() }?.filter { it.isNotEmpty() }.orEmpty()
                        for (name in names) {
                            for (p in setOf(path, path.trimEnd('/').ifEmpty { "/" }, "/")) {
                                cookies.setCookie(url, "$name=; Max-Age=0; Path=$p")
                                cookies.setCookie(url, "$name=; Max-Age=0; Path=$p; Domain=$host")
                            }
                        }
                    }
                    cookies.flush()
                }
            }
        }
    }
}
