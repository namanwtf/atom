package atom.web

import atom.core.llm.ToolSpec
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolContext
import atom.core.tool.ToolResult
import atom.core.tool.schema
import atom.web.browser.BrowserToolNames
import atom.web.search.WebConfig
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebToolSetTest {
    private class Named(name: String) : Tool {
        override val spec = ToolSpec(name, "test", schema())
        override val risk = Risk.Read
        override suspend fun run(args: JsonObject, ctx: ToolContext) = ToolResult.ok("")
    }

    private fun build(g: WebToolGroup): List<Tool> = when (g) {
        WebToolGroup.Files -> listOf(Named("file_read"), Named("view_image"))
        WebToolGroup.Web -> listOf(Named("web_search"), Named("read_page"))
        WebToolGroup.Browser -> BrowserToolNames.ALL.map(::Named)
        WebToolGroup.Images -> listOf(Named("generate_image"))
    }

    private fun names(tools: List<Tool>) = tools.map { it.spec.name }.toSet()

    @Test fun aDefaultInstallOffersTheAgentBrowser() {
        val defaults = WebConfig()
        assertTrue(defaults.enabled, "Web search and reading is on by default")
        assertTrue(defaults.browserEnabled, "the agent browser is on by default")
        val groups = WebToolSet.groups(defaults, imagesOn = false)
        assertEquals(listOf(WebToolGroup.Files, WebToolGroup.Web, WebToolGroup.Browser), groups)
        val got = names(WebToolSet.assemble(groups, ::build))
        for (n in listOf("browser_open", "browser_navigate", "browser_extract", "browser_find", "browser_click", "browser_type", "web_search", "read_page")) {
            assertTrue(n in got, "$n missing from a default run: $got")
        }
    }

    @Test fun browserNamesMatchWhatTheAgentIsTold() {
        assertEquals("browser_open", BrowserToolNames.OPEN)
        assertEquals(BrowserToolNames.ALL.size, BrowserToolNames.ALL.toSet().size)
        val hint = WebToolSet.webHint(browser = true)
        listOf(BrowserToolNames.OPEN, BrowserToolNames.EXTRACT, BrowserToolNames.NAVIGATE, BrowserToolNames.CLOSE).forEach {
            assertTrue(it in hint, "$it not in the hint")
        }
    }

    @Test fun oneFailingGroupDoesNotHideTheBrowser() {
        val got = names(
            WebToolSet.assemble(WebToolSet.groups(WebConfig(), imagesOn = true)) { g ->
                if (g == WebToolGroup.Files || g == WebToolGroup.Images) error("boom") else build(g)
            },
        )
        assertTrue("browser_open" in got)
        assertTrue("web_search" in got)
        assertFalse("file_read" in got)
    }

    @Test fun settingsTurnGroupsOff() {
        assertEquals(listOf(WebToolGroup.Files), WebToolSet.groups(WebConfig(enabled = false), imagesOn = false))
        assertEquals(
            listOf(WebToolGroup.Files, WebToolGroup.Web),
            WebToolSet.groups(WebConfig(browserEnabled = false), imagesOn = false),
        )
        assertEquals(WebToolGroup.Images, WebToolSet.groups(WebConfig(), imagesOn = true).last())
    }

    @Test fun theHintNamesTheBrowserAndKeepsSitesInsideAtom() {
        val hint = WebToolSet.webHint(browser = true)
        listOf("\"Agent browser\"", "\"Atom browser\"", "\"built-in browser\"", "not an installed app", "never in the user's external browser", "open_url")
            .forEach { assertTrue(it in hint, "missing: $it") }
        val noBrowser = WebToolSet.webHint(browser = false)
        assertFalse("browser_open" in noBrowser)
        assertTrue("web_search" in noBrowser)
    }
}
