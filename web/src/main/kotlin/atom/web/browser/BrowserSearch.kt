package atom.web.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.view.View
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
 * Lets web_search read a results page through the agent browser's engine when direct requests are refused. Each
 * call uses its own short-lived off-screen WebView with the agent browser's security settings: it is never shown,
 * never touches the page a conversation's browser has open, and is destroyed as soon as the HTML is read. Images
 * are not loaded.
 */
internal class BrowserSearch(context: Context) : PageRenderer {
    private val app = context.applicationContext

    override suspend fun html(url: String, timeoutMs: Long): String? = withContext(Dispatchers.Main) {
        val view = create()
        // Keeps page timers running even while the agent browsers are paused (the same counter visible browsers use).
        BrowserSessions.onBrowserVisible(true, view)
        try {
            val loaded = CompletableDeferred<Boolean>()
            view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    !WebViewSecurity.isWeb(request.url.toString())

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = Unit

                override fun onPageFinished(view: WebView, url: String) {
                    loaded.complete(true)
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    loaded.complete(false)
                }
            }
            view.loadUrl(url)
            val ok = withTimeoutOrNull(timeoutMs) { loaded.await() } ?: false
            if (!ok) return@withContext null
            delay(SETTLE_MS)
            read(view)?.takeIf { it.isNotBlank() }?.take(MAX_CHARS)
        } finally {
            BrowserSessions.onBrowserVisible(false, view)
            view.stopLoading()
            view.destroy()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(): WebView = WebView(app).apply {
        WebViewSecurity.apply(this)
        settings.blockNetworkImage = true
        settings.loadsImagesAutomatically = false
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        val m = resources.displayMetrics
        measure(
            View.MeasureSpec.makeMeasureSpec(m.widthPixels.coerceIn(720, 1440), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(m.heightPixels.coerceIn(1280, 3200), View.MeasureSpec.EXACTLY),
        )
        layout(0, 0, measuredWidth, measuredHeight)
    }

    private suspend fun read(view: WebView): String? = suspendCancellableCoroutine { cont ->
        view.evaluateJavascript("document.documentElement ? document.documentElement.outerHTML : ''") { raw ->
            val text = runCatching { (Json.parseToJsonElement(raw ?: "null") as? JsonPrimitive)?.takeIf { it.isString }?.content }.getOrNull()
            if (cont.isActive) cont.resume(text)
        }
    }

    private companion object {
        const val SETTLE_MS = 500L
        const val MAX_CHARS = 2 * 1024 * 1024
    }
}
