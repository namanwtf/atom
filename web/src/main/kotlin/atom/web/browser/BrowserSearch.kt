package atom.web.browser

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import atom.web.search.PageRenderer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.resume

/**
 * Lets web_search read a results page through the WebView engine when direct requests are refused. Each call uses
 * its own short-lived off-screen WebView with the agent browser's security settings: it is never shown, never touches
 * the page a conversation's browser has open, and is destroyed as soon as the HTML is read. Images are not loaded.
 *
 * It never shares the agent browser's session: it runs in its own WebView profile ([PROFILE]), whose cookies and
 * storage are wiped after every use, with a generic user agent (no device model). Where the WebView cannot run
 * separate profiles, this tier is skipped rather than using the shared cookie jar.
 */
internal class BrowserSearch(context: Context) : PageRenderer {
    private val app = context.applicationContext

    override suspend fun html(url: String, timeoutMs: Long): String? {
        val raw = withContext(Dispatchers.Main) { load(url, timeoutMs) } ?: return null
        // Decoding up to a few MB of JSON is kept off the main thread; only WebView calls run there.
        return withContext(Dispatchers.Default) { decode(raw)?.takeIf { it.isNotBlank() }?.take(MAX_CHARS) }
    }

    /** Main thread: loads [url] in a throwaway WebView and returns the page's HTML as the raw JavaScript result. */
    private suspend fun load(url: String, timeoutMs: Long): String? {
        if (!isolationSupported()) return null
        val view = create() ?: return null
        // Page timers must run, even while the agent browsers have them paused; the earlier state comes back after.
        val hadPausedTimers = BrowserSessions.borrowTimers(view)
        var gone = false
        try {
            val loaded = CompletableDeferred<Boolean>()
            view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    !WebViewSecurity.isWeb(request.url.toString())

                override fun onPageFinished(view: WebView, url: String) {
                    loaded.complete(true)
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    loaded.complete(false)
                }

                // A dead renderer would otherwise take the whole app down. This tier just fails.
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    gone = true
                    loaded.complete(false)
                    return true
                }
            }
            view.loadUrl(url)
            val ok = withTimeoutOrNull(timeoutMs) { loaded.await() } ?: false
            if (!ok || gone) return null
            delay(SETTLE_MS)
            if (gone) return null
            return withTimeoutOrNull(READ_TIMEOUT_MS) { read(view) }
        } finally {
            BrowserSessions.returnTimers(view, hadPausedTimers)
            if (!gone) view.stopLoading()
            // The search profile's HTTP cache goes too, not only its cookies and storage.
            runCatching { view.clearCache(true) }
            view.destroy()
            wipeProfile()
        }
    }

    private fun isolationSupported(): Boolean = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)

    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    private fun create(): WebView? = runCatching {
        WebView(app).apply {
            // The profile has to be set before anything else touches this WebView.
            ProfileStore.getInstance().getOrCreateProfile(PROFILE)
            WebViewCompat.setProfile(this, PROFILE)
            WebViewSecurity.apply(this)
            settings.blockNetworkImage = true
            settings.loadsImagesAutomatically = false
            settings.userAgentString = genericUserAgent(WebSettings.getDefaultUserAgent(app))
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            val m = resources.displayMetrics
            measure(
                View.MeasureSpec.makeMeasureSpec(m.widthPixels.coerceIn(720, 1440), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(m.heightPixels.coerceIn(1280, 3200), View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, measuredWidth, measuredHeight)
        }
    }.getOrNull()

    @SuppressLint("RequiresFeature")
    private fun wipeProfile() {
        runCatching {
            val profile = ProfileStore.getInstance().getProfile(PROFILE) ?: return
            profile.cookieManager.removeAllCookies(null)
            profile.cookieManager.flush()
            profile.webStorage.deleteAllData()
        }
    }

    private suspend fun read(view: WebView): String? = suspendCancellableCoroutine { cont ->
        view.evaluateJavascript("document.documentElement ? document.documentElement.outerHTML : ''") { raw ->
            if (cont.isActive) cont.resume(raw)
        }
    }

    companion object {
        /** The WebView profile keyless search runs in; nothing in it outlives one search. */
        const val PROFILE = "atom-search"

        private const val SETTLE_MS = 500L
        private const val READ_TIMEOUT_MS = 5_000L
        private const val MAX_CHARS = 2 * 1024 * 1024

        /** The JavaScript result is a JSON string literal; null when it is not one. */
        internal fun decode(raw: String): String? =
            runCatching { (Json.parseToJsonElement(raw) as? JsonPrimitive)?.takeIf { it.isString }?.content }.getOrNull()

        /** The device's WebView user agent without its model and build: "(Linux; Android 10; K)", as Chrome reports it. */
        internal fun genericUserAgent(device: String): String =
            device.replace(Regex("""\(Linux; Android [^)]*\)"""), "(Linux; Android 10; K)").replace(Regex("""\s*Build/[^\s;)]+"""), "")
    }
}
