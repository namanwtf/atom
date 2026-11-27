package atom.web.sites

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A plain JVM test cannot run the preview's WebView, so this guards its rules in the source: the shared secure
 * settings, no JavaScript bridge into the app, the renderer-crash handler, and no WebView call inside a background
 * dispatcher block (WebView calls must stay on the main thread).
 */
class SitePreviewSourceTest {
    private val source = File("src/main/kotlin/atom/web/ui/SiteScreens.kt").readText()

    @Test
    fun theWebViewIsLockedDown() {
        assertTrue("WebViewSecurity.apply(this)" in source, "the preview must use the shared secure WebView settings")
        assertFalse("addJavascriptInterface" in source, "no JavaScript bridge into the app")
        assertFalse(Regex("""allow(File|Content)Access\s*=\s*true""").containsMatchIn(source))
        assertTrue("onRenderProcessGone" in source, "a crashed renderer must be handled")
        assertTrue("shouldInterceptRequest" in source && "isLocal" in source, "only the site's own address is loaded")
        assertTrue("siteWeb.intercept(request)" in source, "the site is served from storage, not from a socket")
        assertTrue("blockedResponse()" in source, "plain http is refused")
    }

    @Test
    fun everyAtomWebViewThatCanOpenASiteServesIt() {
        // The preview, the agent browser (browser_open, and the take-over screen that shows the same WebView) and the
        // user's own browser: each answers the site's https://<id>-<tag>.invalid address itself.
        for (path in listOf("src/main/kotlin/atom/web/browser/Browser.kt", "src/main/kotlin/atom/web/ui/BrowserScreens.kt")) {
            val s = File(path).readText()
            assertTrue("shouldInterceptRequest" in s && "sites.intercept(request)" in s, path)
            assertTrue("sites.onMainFrame(" in s, "$path must tell the interceptor which page its main frame shows")
        }
        assertFalse(File("src/main/kotlin/atom/web/sites/SiteServer.kt").exists(), "no socket server is left")
        val all = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.map { it.readText() }
        assertTrue(all.none { "ServerSocket" in it }, "nothing in :web listens on a port")
    }

    @Test
    fun onlyTheUsersOwnSitesReportConsoleErrorsToTheAgent() {
        val s = File("src/main/kotlin/atom/web/browser/Browser.kt").readText()
        assertTrue("ConsoleMessage.MessageLevel.ERROR && fromOwnSite(message)" in s)
        assertTrue("SiteOrigin.parse(source) != null" in s, "judged by the file the error comes from")
    }

    @Test
    fun noWebViewWorkInsideBackgroundBlocks() {
        val webViewUse = Regex("""\b(web|view)\.(loadUrl|reload|stopLoading|destroy|onPause|onResume|evaluateJavascript|settings|url|title)\b""")
        val lines = source.lines()
        for ((i, line) in lines.withIndex()) {
            if (!Regex("""withContext\([^)]*Dispatchers\.(Default|IO)""").containsMatchIn(line)) continue
            var depth = 0
            val block = StringBuilder()
            for (j in i until lines.size) {
                val l = if (j == i) lines[j].substringAfter("withContext(") else lines[j]
                block.append(l).append('\n')
                depth += l.count { it == '{' } - l.count { it == '}' }
                if (depth <= 0 && l.contains('}')) break
            }
            assertTrue(!webViewUse.containsMatchIn(block), "SiteScreens.kt:${i + 1} touches the WebView inside a background block:\n$block")
        }
    }
}
