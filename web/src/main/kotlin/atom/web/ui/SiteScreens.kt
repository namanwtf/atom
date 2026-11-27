package atom.web.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.text.format.DateUtils
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import atom.ui.components.AtomRow
import atom.ui.components.CircleIconButton
import atom.ui.components.CompactButton
import atom.ui.components.Group
import atom.ui.components.LargeTitleScreen
import atom.ui.components.PrimaryButton
import atom.ui.feature.FeatureNav
import atom.web.R
import atom.web.WebFeature
import atom.web.browser.BrowserSessions
import atom.web.browser.WebViewSecurity
import atom.web.files.Share
import atom.web.files.Sizes
import atom.web.sites.SiteCards
import atom.web.sites.SiteInfo
import atom.web.sites.SiteOrigin
import atom.web.sites.SitePaths
import atom.web.sites.SiteWeb
import atom.web.sites.Sites
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Settings > Sites: the websites Atom made. Tap opens the live preview; the bin deletes a site after asking. */
@Composable
internal fun SitesScreen(nav: FeatureNav) {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val changes by Sites.changes.collectAsState()
    val sites by produceState<List<SiteInfo>?>(null, changes) {
        value = withContext(Dispatchers.IO) { runCatching { Sites.store(app).all() }.getOrDefault(emptyList()) }
    }
    var deleting by remember { mutableStateOf<SiteInfo?>(null) }
    var lastDeleting by remember { mutableStateOf<SiteInfo?>(null) }
    val c = MaterialTheme.colorScheme

    LargeTitleScreen(title = "Sites", onBack = nav::back) {
        item {
            val list = sites
            when {
                list == null -> Unit
                list.isEmpty() -> Column(Modifier.fillMaxWidth().padding(top = 48.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No sites yet", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                    Text(
                        "Ask Atom in any chat to make you a website, for example \"make me a small website that tracks my water intake\". " +
                            "Each site gets a live preview here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                else -> Text(
                    "Websites, web apps and games Atom made. They are kept on this phone; tap one for its live preview.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp),
                )
            }
        }
        val list = sites.orEmpty()
        if (list.isNotEmpty()) {
            item {
                Group {
                    list.forEach { site ->
                        AtomRow(
                            title = site.title,
                            summary = "${site.files} file${if (site.files == 1) "" else "s"} · ${Sizes.human(site.bytes)} · " +
                                DateUtils.getRelativeTimeSpanString(site.modified, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                            icon = WebIcons.Code,
                            onClick = { nav.open(SiteCards.previewRoute(site.id)) },
                            end = {
                                Box(Modifier.align(Alignment.CenterVertically)) {
                                    CircleIconButton(Icons.Filled.Delete, "Delete ${site.title}", {
                                        deleting = site
                                        lastDeleting = site
                                    }, size = 36.dp)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
    val shown = deleting ?: lastDeleting
    ConfirmDialog(
        "Delete ${shown?.title ?: "site"}?",
        "Deletes the site and its files from Atom. Copies you shared stay.",
        "Delete",
        onConfirm = { shown?.let { s -> scope.launch(Dispatchers.IO) { Sites.delete(app, s.id) } } },
        onDismiss = { deleting = null },
        show = deleting != null,
    )
}

/** The chat card for a site_preview call: the site's name and "Open preview". */
@Composable
fun SitePreviewCard(toolText: String, onOpen: ((String) -> Unit)?, modifier: Modifier = Modifier) {
    val id = remember(toolText) { SiteCards.siteOf(toolText) } ?: return
    val app = LocalContext.current.applicationContext
    val title by produceState(id, id) {
        value = withContext(Dispatchers.IO) { runCatching { Sites.store(app).title(id) }.getOrNull() } ?: id
    }
    val c = MaterialTheme.colorScheme
    val open = onOpen?.let { { it(SiteCards.previewRoute(id)) } }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceContainerHigh)
            .then(if (open != null) Modifier.clickable(onClick = open) else Modifier)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(WebIcons.Code, null, Modifier.size(22.dp), tint = c.primary)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Website · live preview", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1)
        }
        if (open != null) CompactButton("Open preview", onClick = open)
    }
}

/**
 * The live preview of one site: a full-screen WebView on the site's own address ([SiteOrigin]), under a small top
 * bar (name, reload, open in Atom's browser, share as .zip, close).
 *
 * The address is answered by the WebView itself from the site's files ([SiteWeb]); nothing listens on a port. The
 * WebView has no file or content access, no JavaScript bridge into the app and blocks mixed content and every
 * plain-http request; a tapped link to another site opens in Atom's browser. The page reloads when the site's files
 * change. Every WebView call is made here, on the main thread; the .zip runs on IO.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun SitePreviewScreen(nav: FeatureNav, id: String) {
    val context = LocalContext.current
    val app = context.applicationContext
    val c = MaterialTheme.colorScheme
    val exists by produceState<Boolean?>(null, id) {
        value = withContext(Dispatchers.IO) { SitePaths.isId(id) && Sites.store(app).exists(id) }
    }
    if (exists == false) {
        Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("This site no longer exists.", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
            PrimaryButton("Back", onClick = nav::back, modifier = Modifier.padding(top = 24.dp))
        }
        return
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifeState by lifecycle.currentStateAsState()
    val started = lifeState.isAtLeast(Lifecycle.State.STARTED)
    var url by remember { mutableStateOf<String?>(null) }
    var siteError by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf(id) }
    var loading by remember { mutableStateOf(true) }
    var progress by remember { mutableIntStateOf(0) }
    var crashed by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    // Read by shouldInterceptRequest on a WebView worker thread: no WebView call there, only these values.
    val site = remember { AtomicReference<Pair<String, String>?>(null) }
    val siteWeb = remember { SiteWeb(app) }

    LaunchedEffect(id, exists) {
        if (exists != true) return@LaunchedEffect
        try {
            val u = withContext(Dispatchers.IO) { Sites.url(app, id) }
            site.set(SiteOrigin.parse(u))
            siteError = null
            url = u
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            siteError = e.message ?: "The preview could not start."
        }
    }

    // While the screen is started the site counts as shown: the agent then knows the preview reloads by itself.
    LaunchedEffect(started, id, exists) {
        if (!started || exists != true) return@LaunchedEffect
        Sites.shown(id)
        try {
            awaitCancellation()
        } finally {
            Sites.hidden(id)
        }
    }

    fun isLocal(u: String): Boolean {
        val own = site.get() ?: return false
        return SiteOrigin.parse(u) == own
    }

    // The page's prefers-color-scheme follows Atom's look, not the system's: a new WebView when it changes.
    val dark = c.background.luminance() < 0.5f
    val destroyed = remember(generation, dark) { booleanArrayOf(false) }
    val web = remember(generation, dark) {
        val themed = ContextThemeWrapper(context, if (dark) R.style.AtomSitePreviewDark else R.style.AtomSitePreviewLight)
        WebView(themed).apply {
            WebViewSecurity.apply(this)
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val u = request.url.toString()
                    if (isLocal(u) || !request.isForMainFrame) return false
                    // Only a tap leaves the site; a page sending itself elsewhere is simply stopped.
                    if (!request.hasGesture()) return true
                    if (WebViewSecurity.isWeb(u)) {
                        nav.open(WebFeature.browserRoute(u))
                    } else if (!openExternally(context, u)) {
                        Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
                    }
                    return true
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    siteWeb.intercept(request)?.let { return it }
                    return if (request.url.scheme.equals("http", ignoreCase = true)) blockedResponse() else null
                }

                override fun onPageStarted(view: WebView, u: String, favicon: Bitmap?) {
                    siteWeb.onMainFrame(u)
                    loading = true
                }

                override fun doUpdateVisitedHistory(view: WebView, u: String, isReload: Boolean) {
                    siteWeb.onMainFrame(u)
                }

                override fun onPageFinished(view: WebView, u: String) {
                    loading = false
                    view.title?.takeIf { it.isNotBlank() && !it.startsWith("http") }?.let { title = it }
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    // The renderer is gone: this WebView must not be used again. Drop it and offer a reload.
                    crashed = true
                    if (!destroyed[0]) {
                        destroyed[0] = true
                        (view.parent as? ViewGroup)?.removeView(view)
                        view.destroy()
                    }
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progress = newProgress
                }

                override fun onReceivedTitle(view: WebView, t: String?) {
                    t?.takeIf { it.isNotBlank() && !it.startsWith("http") }?.let { title = it }
                }

                override fun onPermissionRequest(request: PermissionRequest) = request.deny()

                override fun onGeolocationPermissionsShowPrompt(o: String, callback: GeolocationPermissions.Callback) =
                    callback.invoke(o, false, false)
            }
            setDownloadListener { _, _, _, _, _ ->
                Toast.makeText(context, "Downloads do not work in the preview. Share the site as a .zip instead.", Toast.LENGTH_LONG).show()
            }
        }
    }

    DisposableEffect(web) {
        BrowserSessions.onBrowserVisible(true, web)
        onDispose {
            BrowserSessions.onBrowserVisible(false, web)
            if (!destroyed[0]) {
                destroyed[0] = true
                (web.parent as? ViewGroup)?.removeView(web)
                web.stopLoading()
                web.destroy()
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { if (!destroyed[0]) web.onPause() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (!destroyed[0]) web.onResume() }
    LaunchedEffect(web, url) {
        val u = url ?: return@LaunchedEffect
        if (!destroyed[0]) web.loadUrl(u)
    }
    // The site's files changed (the agent wrote one): the page reloads. Writes in a burst reload it once.
    LaunchedEffect(web, id) {
        Sites.versions.map { it[id] ?: 0L }.distinctUntilChanged().drop(1).collectLatest {
            delay(250)
            if (!destroyed[0] && !crashed && url != null) web.reload()
        }
    }

    Column(Modifier.fillMaxSize().background(c.background)) {
        Row(
            Modifier.fillMaxWidth().background(c.surface).statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CircleIconButton(Icons.Filled.Close, "Close preview", nav::back, size = 40.dp)
            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    siteError ?: url?.removePrefix("https://")?.trimEnd('/') ?: "Starting…",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (siteError != null) c.error else c.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CircleIconButton(Icons.Filled.Refresh, "Reload", {
                if (crashed) {
                    crashed = false
                    generation++
                } else if (!destroyed[0]) {
                    web.reload()
                }
            }, size = 40.dp)
            CircleIconButton(WebIcons.OpenInNew, "Open in Atom's browser", {
                url?.let { nav.open(WebFeature.browserRoute(it)) }
            }, size = 40.dp)
            CircleIconButton(Icons.Filled.Share, "Share as .zip", {
                scope.launch {
                    val zip = withContext(Dispatchers.IO) {
                        runCatching { Sites.store(app).zip(id, File(File(app.cacheDir, "web_share"), "$id.zip")) }.getOrNull()
                    }
                    if (zip == null) {
                        Toast.makeText(context, "Could not pack the site", Toast.LENGTH_SHORT).show()
                    } else {
                        runCatching { Share.share(context, listOf(zip), "application/zip") }
                            .onFailure { Toast.makeText(context, "No app can receive the .zip", Toast.LENGTH_SHORT).show() }
                    }
                }
            }, size = 40.dp)
        }
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (loading && !crashed) Box(Modifier.fillMaxWidth(progress.coerceIn(5, 100) / 100f).height(2.dp).background(c.primary))
        }
        Box(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding()) {
            if (crashed) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("The page stopped", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                    Text(
                        "Its renderer was closed, often because the page used too much memory.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    PrimaryButton("Reload", onClick = {
                        crashed = false
                        generation++
                    }, modifier = Modifier.padding(top = 20.dp))
                }
            } else {
                key(generation, dark) { AndroidView(factory = { web }, modifier = Modifier.fillMaxSize()) }
            }
        }
    }
}

private fun blockedResponse() =
    WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
