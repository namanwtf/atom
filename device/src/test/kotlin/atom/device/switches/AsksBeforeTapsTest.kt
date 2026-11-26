package atom.device.switches

import atom.core.tool.Risk
import atom.core.tool.ToolResult
import atom.device.tools.SelfConfirmingTool
import atom.device.tools.SimpleTool
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AsksBeforeTapsTest {
    private val noArgs = JsonObject(emptyMap())

    private fun plain(risk: Risk) = SimpleTool("tap", "Tap", risk, noArgs) { _, _ -> ToolResult.ok("") }

    private fun selfConfirming() = SelfConfirmingTool("freeze_app", "Freeze", noArgs) { _, _ -> ToolResult.ok("") }

    @Test
    fun plainActToolIsAskedAboutWhenTheSettingIsOn() {
        assertTrue(plain(Risk.Act).asksBeforeTaps(confirmAct = true))
        assertTrue(GatedTool(plain(Risk.Act), emptySet()).asksBeforeTaps(confirmAct = true))
    }

    @Test
    fun nothingIsAskedAboutWhenTheSettingIsOff() {
        assertFalse(plain(Risk.Act).asksBeforeTaps(confirmAct = false))
    }

    @Test
    fun readAndSensitiveToolsAreLeftAlone() {
        assertFalse(plain(Risk.Read).asksBeforeTaps(confirmAct = true))
        assertFalse(plain(Risk.Sensitive).asksBeforeTaps(confirmAct = true))
    }

    @Test
    fun selfConfirmingToolsAreAskedAboutOnceEvenWhenGated() {
        assertFalse(selfConfirming().asksBeforeTaps(confirmAct = true))
        assertFalse(GatedTool(selfConfirming(), emptySet()).asksBeforeTaps(confirmAct = true))
        assertFalse(GatedTool(GatedTool(selfConfirming(), emptySet()), emptySet()).asksBeforeTaps(confirmAct = true))
    }
}
