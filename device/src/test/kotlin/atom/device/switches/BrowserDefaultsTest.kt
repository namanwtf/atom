package atom.device.switches

import atom.core.llm.ToolSpec
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolContext
import atom.core.tool.ToolResult
import atom.core.tool.schema
import atom.device.caps.ToolCatalog
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A default install must offer the agent browser to normal chat runs: no switch or one-time conversion hides it. */
class BrowserDefaultsTest {
    private val browser = listOf("browser_open", "browser_navigate", "browser_extract")

    private class Named(name: String) : Tool {
        override val spec = ToolSpec(name, "test", schema())
        override val risk = Risk.Read
        override suspend fun run(args: JsonObject, ctx: ToolContext) = ToolResult.ok("")
    }

    @Test fun noPerToolSwitchHidesTheBrowserByDefault() {
        val kept = browser.map(::Named).withoutSwitchedOff().map { it.spec.name }
        assertEquals(browser, kept)
    }

    @Test fun theWebMasterIsOnByDefault() {
        assertTrue(ToolSwitch.Web.default)
        assertTrue(ToolSwitches.isOn(ToolSwitch.Web))
    }

    @Test fun theOneTimeConversionLeavesTheBrowserOnWithDefaultMasters() {
        val offByDefault = ToolSwitch.entries.filterNot { it.default }
        val folded = ToolSwitches.foldedTools(ToolCatalog.all, emptyMap(), offByDefault)
        browser.forEach { assertTrue(it !in folded, "$it would be switched off: $folded") }
    }

    @Test fun theBrowserHasItsOwnCard() {
        val card = ToolCatalog.all.first { it.id == "browser" }
        assertTrue(card.tools.containsAll(browser))
        assertTrue(card.switches.isEmpty(), "no master switch gates the agent browser")
    }
}
