package atom.web.browser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every WebView call must happen on the main looper. Tool code runs on background threads, so the agent browser's
 * tools reach the WebView only through [BrowserSession], whose methods hop to the main thread themselves. A plain
 * JVM test cannot run a WebView, so this guards the rule in the sources: a tool touching `webView` directly, or
 * WebView work inside a background dispatcher block, fails here.
 */
class BrowserThreadingTest {
    private fun source(name: String) = File("src/main/kotlin/atom/web/browser/$name").readText()

    @Test
    fun toolsNeverTouchTheWebViewDirectly() {
        val tools = source("BrowserTools.kt")
        val hits = tools.lines().withIndex().filter { (_, l) -> Regex("""\.webView\b""").containsMatchIn(l) }
        assertTrue(hits.isEmpty(), "BrowserTools.kt uses webView directly; go through BrowserSession: " + hits.joinToString { "${it.index + 1}: ${it.value.trim()}" })
    }

    @Test
    fun noWebViewWorkInsideBackgroundBlocks() {
        val webViewUse = Regex("""\b(webView|view|w|s)\.(url|loadUrl|evaluateJavascript|reload|goBack|goForward|stopLoading|draw|capture|eval|load|settings)\b|\bs\.(url|eval|load|capture|preview)\(""")
        for (name in listOf("BrowserTools.kt", "Browser.kt", "BrowserSearch.kt")) {
            val lines = source(name).lines()
            for ((i, line) in lines.withIndex()) {
                if (!Regex("""withContext\(Dispatchers\.(Default|IO)\)""").containsMatchIn(line)) continue
                // The block: this line up to the line where its braces close.
                var depth = 0
                val block = StringBuilder()
                for (j in i until lines.size) {
                    val l = if (j == i) lines[j].substringAfter("withContext(") else lines[j]
                    block.append(l).append('\n')
                    depth += l.count { it == '{' } - l.count { it == '}' }
                    if (depth <= 0 && l.contains('}')) break
                }
                assertTrue(
                    !webViewUse.containsMatchIn(block),
                    "$name:${i + 1} touches the WebView inside a background dispatcher block:\n$block",
                )
            }
        }
    }

    @Test
    fun sessionMethodsHopToTheMainThread() {
        val browser = source("Browser.kt")
        for (method in listOf("load", "awaitLoad", "settleAfterAction", "eval", "url", "captureOnMain")) {
            val decl = Regex("""suspend fun (<[^>]+> )?$method\([^)]*\)[^=\n{]*=\s*onMain""").find(browser)
            assertTrue(decl != null, "BrowserSession.$method must run through onMain")
        }
        assertTrue(browser.contains("withContext(Dispatchers.Main.immediate)"), "onMain must switch to the main looper")
    }

    @Test
    fun keylessSearchWebViewSurvivesARendererCrashAndKeepsItsOwnProfile() {
        val src = source("BrowserSearch.kt")
        assertTrue(src.contains("override fun onRenderProcessGone"), "a renderer death must not kill the app")
        assertTrue(src.contains("WebViewCompat.setProfile"), "keyless search runs in its own WebView profile")
        assertTrue(src.contains("removeAllCookies"), "the search profile is wiped after use")
        assertTrue(src.indexOf("clearCache(true)") in 0 until src.indexOf("view.destroy()"), "its HTTP cache is cleared before the view goes")
        assertTrue(src.contains("returnTimers"), "the global WebView timers go back to how they were")
        assertTrue(Regex("""withTimeoutOrNull\(READ_TIMEOUT_MS\)""").containsMatchIn(src), "reading the page has its own timeout")
    }

    @Test
    fun searchHelpers() {
        assertEquals("<html>a</html>", BrowserSearch.decode("\"<html>a</html>\""))
        assertEquals(null, BrowserSearch.decode("null"))
        val device = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP2A.240805.005; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/127.0.6533.103 Mobile Safari/537.36"
        val generic = BrowserSearch.genericUserAgent(device)
        assertEquals("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/127.0.6533.103 Mobile Safari/537.36", generic)
        assertTrue("Pixel" !in generic)
    }
}
