package atom.web.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import atom.ui.components.AtomMenu
import atom.ui.components.CircleIconButton
import atom.ui.components.PrimaryButton
import atom.ui.feature.Atom
import atom.ui.feature.FeatureNav
import atom.web.browser.BrowserSessions
import atom.web.browser.UrlInput
import atom.web.browser.WebViewSecurity
import atom.web.sites.SiteOrigin
import atom.web.sites.SiteWeb
import atom.core.io.AtomOnlyUrls
import java.net.URLDecoder

/**
 * Hands [url] to another app. Never a link to one of the user's sites ([AtomOnlyUrls]): another app would only look
 * the name up and learn the address; those open in Atom. Returns false when nothing was opened.
 */
internal fun openExternally(context: Context, url: String): Boolean {
    if (AtomOnlyUrls.isAtomOnly(url)) return false
    val intent = runCatching {
        if (url.startsWith("intent:")) {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                component = null
                selector = null
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        }
    }.getOrNull() ?: return false
    if (AtomOnlyUrls.isAtomOnly(intent.dataString)) return false
    return try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}

/** The user's own browser (Settings > Browser, or overflow > Open browser). Its WebView lives only while shown. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun UserBrowserScreen(nav: FeatureNav, startUrl: String?) {
    val context = LocalContext.current
    var url by remember { mutableStateOf(startUrl.orEmpty()) }
    val address = rememberTextFieldState(startUrl.orEmpty())
    var editing by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current

    // The user's own sites open here too, at their https://<id>-<tag>.invalid address, served from storage.
    val sites = remember { SiteWeb(context) }
    val web = remember {
        WebView(context).apply {
            WebViewSecurity.apply(this)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = sites.intercept(request)

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val u = request.url.toString()
                    if (WebViewSecurity.isWeb(u)) return false
                    if (request.hasGesture() && !openExternally(context, u)) Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
                    return true
                }

                override fun onPageStarted(view: WebView, u: String, favicon: Bitmap?) {
                    sites.onMainFrame(u)
                    loading = true
                    url = u
                }

                override fun onPageFinished(view: WebView, u: String) {
                    loading = false
                    url = u
                    canBack = view.canGoBack()
                    canForward = view.canGoForward()
                }

                override fun doUpdateVisitedHistory(view: WebView, u: String, isReload: Boolean) {
                    sites.onMainFrame(u)
                    canBack = view.canGoBack()
                    canForward = view.canGoForward()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progress = newProgress
                }
            }
            setDownloadListener { dl, _, _, _, _ ->
                // A file from one of the user's own sites is not handed to another app (that would leak its address).
                if (SiteOrigin.isSiteUrl(dl) || SiteOrigin.isSiteUrl(url)) {
                    Toast.makeText(context, "Files from your own sites are not downloaded. Share the site as a .zip from its preview.", Toast.LENGTH_LONG).show()
                } else {
                    openExternally(context, dl)
                }
            }
            if (!startUrl.isNullOrBlank()) loadUrl(UrlInput.resolve(startUrl))
        }
    }

    DisposableEffect(web) {
        BrowserSessions.onBrowserVisible(true, web)
        onDispose {
            BrowserSessions.onBrowserVisible(false, web)
            (web.parent as? ViewGroup)?.removeView(web)
            web.stopLoading()
            web.destroy()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { web.onPause() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { web.onResume() }
    BackHandler { if (web.canGoBack()) web.goBack() else nav.back() }

    fun go(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        focus.clearFocus()
        editing = false
        web.loadUrl(UrlInput.resolve(t))
    }

    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
            if (url.isBlank()) {
                Column(Modifier.fillMaxSize().background(c.background).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Search or enter address", style = MaterialTheme.typography.titleMedium, color = c.onSurfaceVariant)
                    Text("Sign-ins here are shared with the agent's browser.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
            if (loading) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(5, 100) / 100f).height(2.dp).background(c.primary))
            }
        }
        Row(
            Modifier.fillMaxWidth().background(c.background).navigationBarsPadding().imePadding().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", { if (web.canGoBack()) web.goBack() else nav.back() }, size = 40.dp)
            CircleIconButton(Icons.AutoMirrored.Filled.ArrowForward, "Forward", { if (canForward) web.goForward() }, size = 40.dp)
            Box(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(50)).background(c.surfaceContainerHigh).padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                LaunchedEffect(url, editing) { if (!editing) address.setTextAndPlaceCursorAtEnd(displayHost(url)) }
                if (address.text.isEmpty()) Text("Search or enter address", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                BasicTextField(
                    state = address,
                    lineLimits = TextFieldLineLimits.SingleLine,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.onSurface),
                    cursorBrush = SolidColor(c.onSurface),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                    onKeyboardAction = { go(address.text.toString()) },
                    modifier = Modifier.fillMaxWidth().onFocusChanged { f ->
                        if (f.isFocused && !editing) address.setTextAndPlaceCursorAtEnd(url.takeIf { it != "about:blank" }.orEmpty())
                        editing = f.isFocused
                    },
                )
            }
            CircleIconButton(if (loading) Icons.Filled.Close else Icons.Filled.Refresh, if (loading) "Stop loading" else "Refresh", {
                if (loading) web.stopLoading() else if (url.isNotBlank()) web.reload()
            }, size = 40.dp)
            Box {
                CircleIconButton(Icons.Filled.MoreVert, "More options", { menu = true }, size = 40.dp)
                AtomMenu(expanded = menu, onDismiss = { menu = false }) {
                    // A page of one of the user's own sites opens only in Atom: no other app can show it.
                    if (!SiteOrigin.isSiteUrl(url)) DropdownMenuItem(text = { Text("Open in another app") }, enabled = url.isNotBlank(), onClick = {
                        menu = false
                        if (!openExternally(context, url)) Toast.makeText(context, "No app can open this webpage", Toast.LENGTH_SHORT).show()
                    })
                    DropdownMenuItem(text = { Text("Close page") }, onClick = {
                        menu = false
                        web.stopLoading()
                        web.loadUrl("about:blank")
                        web.clearHistory()
                        url = ""
                        canBack = false
                        canForward = false
                    })
                    DropdownMenuItem(text = { Text("Clear all browser data") }, onClick = { menu = false; confirmClear = true })
                }
            }
        }
    }
    ConfirmDialog(
        "Clear all browser data?",
        "Removes cookies, cache, site data and sign-ins for this browser and the agent's browser. Open agent pages are closed.",
        "Clear",
        onConfirm = {
            BrowserSessions.closeAll()
            web.clearHistory()
            web.clearCache(true)
            WebViewSecurity.clearAll(context) { Toast.makeText(context, "Browser data cleared", Toast.LENGTH_SHORT).show() }
            web.loadUrl("about:blank")
            url = ""
        },
        onDismiss = { confirmClear = false },
        show = confirmClear,
    )
}

private fun displayHost(u: String): String =
    if (u == "about:blank") "" else runCatching { Uri.parse(u).host?.removePrefix("www.") }.getOrNull()?.takeIf { it.isNotBlank() } ?: u

/** Shows the agent's off-screen session so the user can act on it; the run stays paused until they continue. */
@Composable
internal fun TakeOverScreen(nav: FeatureNav, conversationId: String) {
    val context = LocalContext.current
    val session = remember(conversationId) { BrowserSessions.get(conversationId) }
    val c = MaterialTheme.colorScheme
    if (session == null) {
        Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("The agent's browser for this chat is closed.", style = MaterialTheme.typography.titleMedium)
            PrimaryButton("Back", onClick = nav::back, modifier = Modifier.padding(top = 24.dp))
        }
        return
    }
    val page by session.page.collectAsState()
    DisposableEffect(session) {
        runCatching { Atom.host.pause(conversationId) }
        session.attached = true
        session.contextWrapper.baseContext = context
        BrowserSessions.touch(session)
        BrowserSessions.onBrowserVisible(true, session.webView)
        session.webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        onDispose {
            BrowserSessions.onBrowserVisible(false, session.webView)
            (session.webView.parent as? ViewGroup)?.removeView(session.webView)
            session.webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            session.contextWrapper.baseContext = context.applicationContext
            session.attached = false
            session.layoutOffscreen()
            runCatching { Atom.host.resume(conversationId) }
        }
    }
    BackHandler { nav.back() }
    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("You are taking over…", style = MaterialTheme.typography.titleMedium)
            Text(
                "The agent is paused on this page. Do what you need (sign in, solve a check, confirm), then continue.",
                style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant,
            )
            Text(page.url, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = {
                    (session.webView.parent as? ViewGroup)?.removeView(session.webView)
                    session.webView.apply { layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (page.loading) Box(Modifier.fillMaxWidth(page.progress.coerceIn(5, 100) / 100f).height(2.dp).background(c.primary))
        }
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", { if (session.webView.canGoBack()) session.webView.goBack() }, size = 40.dp)
            CircleIconButton(Icons.Filled.Refresh, "Refresh", { session.webView.reload() }, size = 40.dp)
            PrimaryButton("Continue", onClick = nav::back, modifier = Modifier.weight(1f))
        }
    }
}

internal fun decodeRouteUrl(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
