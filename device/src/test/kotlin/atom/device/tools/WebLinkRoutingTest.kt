package atom.device.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebLinkRoutingTest {
    @Test fun plainWebPagesAreRefusedWithAPointerToTheBuiltInBrowser() {
        for (url in listOf("https://news.ycombinator.com", "http://example.com/a?b=c", "techmeme.com", "  HTTPS://Example.com ")) {
            val why = assertNotNull(WebLinkRouting.openUrlRefusal(url, external = false), url)
            assertTrue("browser_open" in why, why)
            assertTrue("external=true" in why, why)
        }
    }

    @Test fun webPagesOpenOutsideOnlyWhenTheUserAskedForIt() {
        assertNull(WebLinkRouting.openUrlRefusal("https://example.com", external = true))
        assertNull(WebLinkRouting.openUrlRefusal("example.com", external = true))
    }

    @Test fun deepLinksStillOpenInTheirApps() {
        for (url in listOf("geo:0,0?q=coffee", "tel:+15551234", "mailto:a@b.c", "market://details?id=x", "spotify:track:1", "sms:123")) {
            assertNull(WebLinkRouting.openUrlRefusal(url, external = false), url)
        }
    }

    @Test fun dangerousSchemesAreRefusedEvenWhenExternal() {
        for (url in listOf("file:///sdcard/a", "content://x/y", "javascript:alert(1)", "intent://x#Intent;end", "JavaScript:void(0)")) {
            val why = assertNotNull(WebLinkRouting.openUrlRefusal(url, external = true), url)
            assertTrue("not allowed" in why, why)
        }
    }

    @Test fun bareHostsGetHttps() {
        assertEquals("https://example.com", WebLinkRouting.withScheme(" example.com "))
        assertEquals("geo:1,2", WebLinkRouting.withScheme("geo:1,2"))
        assertTrue(WebLinkRouting.isWebPage("example.com"))
        assertFalse(WebLinkRouting.isWebPage("tel:1"))
    }

    @Test fun agentBrowserIsNotAnInstalledApp() {
        for (q in listOf("Agent Browser", "agent browser", "Atom browser", "Atom's browser", "built-in browser", "the agent browser", "Open Agent Browser")) {
            assertTrue(WebLinkRouting.meansBuiltInBrowser(q), q)
        }
        for (q in listOf("Brave", "Chrome", "browser", "Firefox", "Samsung Internet", "")) {
            assertFalse(WebLinkRouting.meansBuiltInBrowser(q), q)
        }
        assertTrue("browser_open" in WebLinkRouting.BUILT_IN_BROWSER_HINT)
    }
}
