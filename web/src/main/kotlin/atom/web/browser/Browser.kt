package atom.web.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import atom.ui.feature.Atom
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

data class PageState(
    val url: String = "",
    val title: String = "",
    val loading: Boolean = false,
    val progress: Int = 0,
    val canBack: Boolean = false,
    val canForward: Boolean = false,
    val previewPath: String? = null,
)

/**
 * Fails loudly when a WebView is touched off the main looper (Android then throws "WebView method was called on
 * thread ..." deep inside the WebView, or misbehaves). Unit tests run without a looper and pass through.
 */
internal fun assertMain() {
    val main = Looper.getMainLooper() ?: return
    check(Looper.myLooper() == main) { "WebView used off the main thread (${Thread.currentThread().name})" }
}

/** Shared WebView setup: no file or content access, no mixed content, no geolocation, Safe Browsing on. */
internal object WebViewSecurity {
    @SuppressLint("SetJavaScriptEnabled")
    fun apply(view: WebView, javaScript: Boolean = true) {
        view.settings.apply {
            javaScriptEnabled = javaScript
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            safeBrowsingEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
    }

    fun isWeb(url: String) = url.startsWith("http://") || url.startsWith("https://") || url == "about:blank"

    /** Cookies, cache, storage, saved form data and HTTP auth, for every browser in the app. */
    fun clearAll(context: Context, done: () -> Unit = {}) {
        CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush(); done() }
        WebStorage.getInstance().deleteAllData()
        WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword()
        @Suppress("DEPRECATION")
        WebViewDatabase.getInstance(context).clearFormData()
        WebView(context.applicationContext).apply { clearCache(true); destroy() }
    }
}

/** One conversation's off-screen browser. All members must be used on the main thread. */
class BrowserSession internal constructor(val key: String, context: Context, private val owner: BrowserSessions) {
    internal val contextWrapper = MutableContextWrapper(context.applicationContext)
    val webView: WebView = WebView(contextWrapper)
    private val state = MutableStateFlow(PageState())
    val page: StateFlow<PageState> = state.asStateFlow()

    internal var navigations = 0
    internal var lastError: String? = null
    internal val notices = ArrayList<String>()
    private var waiter: CompletableDeferred<Unit>? = null
    private var previewSeq = 0
    internal var lastUsed = System.currentTimeMillis()
    @Volatile
    internal var attached = false
    internal var dead = false

    init {
        WebViewSecurity.apply(webView)
        webView.setBackgroundColor(Color.WHITE)
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        layoutOffscreen()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (WebViewSecurity.isWeb(url)) return false
                notices += "The page tried to open an app link (${url.take(120)}); it was blocked. Use open_url if the user wants it opened."
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                navigations++
                lastError = null
                state.update { it.copy(url = url, loading = true, progress = 5) }
            }

            override fun onPageFinished(view: WebView, url: String) {
                state.update { it.copy(url = url, title = view.title.orEmpty(), loading = false, progress = 100, canBack = view.canGoBack(), canForward = view.canGoForward()) }
                waiter?.complete(Unit)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) lastError = "${error.description} (${error.errorCode})"
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                lastError = "The site's security certificate is not valid; the page was not loaded."
                handler.cancel()
                waiter?.complete(Unit)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                dead = true
                waiter?.complete(Unit)
                Handler(Looper.getMainLooper()).post { owner.close(key) }
                return true
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                state.update { it.copy(progress = newProgress) }
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                state.update { it.copy(title = title.orEmpty()) }
            }

            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                notices += "The page showed an alert: ${message.take(300)}"
                result.confirm()
                return true
            }

            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                notices += "The page asked to confirm \"${message.take(300)}\"; it was dismissed (Cancel). Ask the user to take over if it must be accepted."
                result.cancel()
                return true
            }

            override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String?, result: JsPromptResult): Boolean {
                notices += "The page asked for input (\"${message.take(200)}\"); it was dismissed."
                result.cancel()
                return true
            }

            override fun onJsBeforeUnload(view: WebView, url: String, message: String, result: JsResult): Boolean {
                result.confirm()
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) = request.deny()

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) =
                callback.invoke(origin, false, false)

            override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                notices += "The page asked to upload a file; the agent browser cannot pick files. Ask the user to take over."
                cb.onReceiveValue(null)
                return true
            }
        }
        webView.setDownloadListener { url, _, _, mime, length ->
            notices += "The page offered a download: ${url.take(200)} ($mime${if (length > 0) ", ${length / 1024} KB" else ""}). Not downloaded."
        }
    }

    /**
     * Runs [block] with the WebView on the main looper, where every WebView call must happen. Callable from any
     * thread; on the main thread it runs at once. Tool code reaches the WebView only through this and the methods
     * below, never directly from a background dispatcher.
     */
    suspend fun <T> onMain(block: suspend (WebView) -> T): T = withContext(Dispatchers.Main.immediate) { block(webView) }

    /** The page's current URL, read on the main thread. */
    suspend fun url(): String? = onMain { it.url }

    internal fun layoutOffscreen() {
        assertMain()
        val m = webView.context.resources.displayMetrics
        val w = m.widthPixels.coerceIn(720, 1440)
        val h = (m.heightPixels.coerceIn(1280, 3200))
        webView.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        webView.layout(0, 0, w, h)
    }

    fun takeNotices(): List<String> = notices.toList().also { notices.clear() }

    suspend fun load(url: String, timeoutMs: Long = 25_000) = onMain { view ->
        waiter = CompletableDeferred()
        view.loadUrl(url)
        awaitLoad(timeoutMs)
    }

    /** Waits for the current navigation (if any) to finish, then a short settle for scripts. */
    suspend fun awaitLoad(timeoutMs: Long = 25_000) = onMain {
        val w = waiter ?: CompletableDeferred<Unit>().also { waiter = it }
        withTimeoutOrNull(timeoutMs) { w.await() }
        waiter = null
        kotlinx.coroutines.delay(400)
    }

    /** Lets a click or submit start a navigation, and waits for it if it did. */
    suspend fun settleAfterAction() = onMain {
        val before = navigations
        waiter = CompletableDeferred()
        kotlinx.coroutines.delay(700)
        if (navigations != before || state.value.loading) awaitLoad(20_000) else waiter = null
        Unit
    }

    suspend fun eval(script: String): String? = onMain { view ->
        suspendCancellableCoroutine { cont ->
            view.evaluateJavascript(script) { raw ->
                val decoded = runCatching {
                    val el = Json.parseToJsonElement(raw ?: "null")
                    if (el is JsonPrimitive && el.isString) el.content else if (raw == "null") null else raw
                }.getOrNull()
                if (cont.isActive) cont.resume(decoded)
            }
        }
    }

    /** Draws the visible viewport on the main thread. [maxSide] bounds the long side of the bitmap. */
    suspend fun captureOnMain(maxSide: Int): Bitmap? = onMain { capture(maxSide) }

    /** Draws the visible viewport. Main thread only. [maxSide] bounds the long side of the bitmap. */
    fun capture(maxSide: Int): Bitmap? {
        assertMain()
        if (webView.width == 0 || webView.height == 0) layoutOffscreen()
        val w = webView.width
        val h = webView.height
        if (w == 0 || h == 0) return null
        val scale = minOf(1f, maxSide.toFloat() / maxOf(w, h))
        val bmp = Bitmap.createBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        c.scale(scale, scale)
        c.translate(-webView.scrollX.toFloat(), -webView.scrollY.toFloat())
        webView.draw(c)
        return bmp
    }

    /** Saves a small JPEG of the viewport for the chat card; keeps the last few per session. */
    suspend fun preview(cacheDir: File): String? {
        val bmp = captureOnMain(540) ?: return null
        val dir = File(cacheDir, "browser_previews").apply { mkdirs() }
        val safe = key.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40)
        val file = File(dir, "${safe}_${previewSeq++}.jpg")
        withContext(Dispatchers.IO) {
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 72, it) }
            bmp.recycle()
            dir.listFiles { f -> f.name.startsWith(safe + "_") }?.sortedByDescending { it.lastModified() }?.drop(6)?.forEach { it.delete() }
        }
        state.update { it.copy(previewPath = file.path) }
        return file.path
    }

    internal fun destroy() {
        assertMain()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.webChromeClient = null
        webView.destroy()
    }
}

/**
 * Off-screen browser sessions, one per conversation, created on first use. They share the app's cookie jar.
 * A session is paused (timers stopped) as soon as its run ends or after [PAUSE_AFTER_MS] without a tool call,
 * and destroyed after [DESTROY_AFTER_MS] idle. Nothing polls: run ends are observed from [Atom.host].
 */
object BrowserSessions {
    private const val PAUSE_AFTER_MS = 45_000L
    private const val DESTROY_AFTER_MS = 4 * 60_000L
    private const val MAX_SESSIONS = 3

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessions = LinkedHashMap<String, BrowserSession>()
    private val pauseTasks = HashMap<String, Runnable>()
    private val destroyTasks = HashMap<String, Runnable>()
    private var watcher: Job? = null
    private val _active = MutableStateFlow<Set<String>>(emptySet())
    private var timersPaused = false
    @Volatile
    internal var visibleBrowsers = 0

    /** Conversations that currently have a live agent browser. */
    val active: StateFlow<Set<String>> = _active.asStateFlow()

    fun get(key: String): BrowserSession? = sessions[key]

    /** The live session of [key], or null when there is none or it has been torn down. Key UI on [active]. */
    fun live(key: String): BrowserSession? = sessions[key]?.takeIf { !it.dead }

    internal fun obtain(context: Context, key: String): BrowserSession {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (sessions[key]?.dead == true) {
            // Drop the dead one first, so [active] changes and UI holding it looks the new session up.
            sessions.remove(key)
            _active.value = sessions.keys.toSet()
        }
        val s = sessions[key] ?: BrowserSession(key, context, this).also {
            sessions[key] = it
            _active.value = sessions.keys.toSet()
            while (sessions.size > MAX_SESSIONS) closeNow(sessions.keys.first { k -> k != key })
            watchRuns()
        }
        touch(s)
        return s
    }

    internal fun touch(s: BrowserSession) {
        s.lastUsed = System.currentTimeMillis()
        cancel(s.key)
        s.webView.onResume()
        if (timersPaused) {
            s.webView.resumeTimers()
            timersPaused = false
        }
        schedule(pauseTasks, s.key, PAUSE_AFTER_MS) { pause(s.key) }
    }

    /** Stops page activity now and schedules teardown. */
    fun pause(key: String) {
        val s = sessions[key] ?: return
        if (s.attached) return
        s.webView.onPause()
        if (visibleBrowsers == 0 && sessions.values.none { it !== s && it.lastUsed > System.currentTimeMillis() - PAUSE_AFTER_MS }) {
            s.webView.pauseTimers()
            timersPaused = true
        }
        schedule(destroyTasks, key, DESTROY_AFTER_MS) { close(key) }
    }

    fun close(key: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) closeNow(key) else main.post { closeNow(key) }
    }

    private fun closeNow(key: String) {
        cancel(key)
        sessions.remove(key)?.destroy()
        _active.value = sessions.keys.toSet()
        if (sessions.isEmpty()) {
            watcher?.cancel()
            watcher = null
        }
    }

    fun closeAll() {
        main.post { sessions.keys.toList().forEach(::closeNow) }
    }

    /**
     * For a short-lived WebView (keyless search) that needs page timers running: resumes them if the agent browsers
     * had paused them (timers are global to the process). Returns whether they were paused, for [returnTimers].
     */
    internal fun borrowTimers(view: WebView): Boolean {
        visibleBrowsers++
        val wasPaused = timersPaused
        if (wasPaused) {
            view.resumeTimers()
            timersPaused = false
        }
        return wasPaused
    }

    /** Puts the timers back as [borrowTimers] found them, unless a browser started needing them meanwhile. */
    internal fun returnTimers(view: WebView, wasPaused: Boolean) {
        visibleBrowsers = (visibleBrowsers - 1).coerceAtLeast(0)
        val recent = System.currentTimeMillis() - PAUSE_AFTER_MS
        if (wasPaused && !timersPaused && visibleBrowsers == 0 && sessions.values.none { it.lastUsed > recent || it.attached }) {
            view.pauseTimers()
            timersPaused = true
        }
    }

    /** Called by the visible browsers so a paused agent session does not freeze their scripts. */
    internal fun onBrowserVisible(visible: Boolean, anyView: WebView) {
        visibleBrowsers = (visibleBrowsers + if (visible) 1 else -1).coerceAtLeast(0)
        if (visible && timersPaused) {
            anyView.resumeTimers()
            timersPaused = false
        }
    }

    private fun cancel(key: String) {
        pauseTasks.remove(key)?.let(main::removeCallbacks)
        destroyTasks.remove(key)?.let(main::removeCallbacks)
    }

    private fun schedule(map: HashMap<String, Runnable>, key: String, delay: Long, block: () -> Unit) {
        map.remove(key)?.let(main::removeCallbacks)
        val r = Runnable { map.remove(key); block() }
        map[key] = r
        main.postDelayed(r, delay)
    }

    private fun watchRuns() {
        if (watcher != null) return
        val host = runCatching { Atom.host }.getOrNull() ?: return
        watcher = scope.launch {
            host.runs.collect { runs ->
                for (key in sessions.keys.toList()) {
                    val run = runs.firstOrNull { it.conversationId == key } ?: continue
                    if (!run.phase.active) pause(key)
                }
            }
        }
    }
}
