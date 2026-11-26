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
        assertTrue("shouldInterceptRequest" in source && "isLocal" in source, "only the site's own address is loaded over plain http")
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
