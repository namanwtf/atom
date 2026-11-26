package atom.automation

import atom.automation.rule.Action
import atom.automation.rule.CompileResult
import atom.automation.rule.Rule
import atom.automation.rule.RuleJson
import atom.automation.rule.Trigger
import atom.automation.rule.WaitFor
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StepCapabilitiesTest {
    @Test fun olderRulesGetTheStepDefault() {
        val r = RuleJson.parse("""{"name":"x","trigger":"screen_on","actions":[{"type":"notify","title":"hi"}]}""", null)
        assertTrue(r.ok, r.errors.toString())
        assertEquals(Capability.STEP_DEFAULT, r.rule!!.stepCapabilities)
    }

    @Test fun stepCapabilitiesAreParsedAndRoundTrip() {
        val r = RuleJson.parse(
            """{"name":"x","trigger":"screen_on","step_capabilities":["device_control","nonsense"],"actions":[{"type":"notify","title":"hi"}]}""",
            null,
        )
        assertTrue(r.ok, r.errors.toString())
        val rule = r.rule!!
        assertEquals(setOf(Capability.DeviceControl), rule.stepCapabilities)
        assertEquals(rule, RuleJson.json.decodeFromString(Rule.serializer(), RuleJson.encode(rule)))
    }

    @Test fun stepDefaultNeverOpensTheAutomationTools() {
        assertFalse(CapabilityTools.allows(Capability.STEP_DEFAULT, ToolFamily.Automation))
        assertTrue(CapabilityTools.allows(Capability.STEP_DEFAULT, ToolFamily.DeviceControl))
    }

    @Test fun approvalShowsWhatTheAutomationCompilesTo() {
        val rule = Rule(
            name = "Night charge",
            trigger = Trigger.ChargerConnected,
            actions = listOf(
                Action.Tool("set_dnd", JsonObject(emptyMap())),
                Action.Agent("Summarise the news", WaitFor.None, setOf(Capability.Web)),
            ),
            stepCapabilities = setOf(Capability.DeviceControl),
        )
        val text = AutomationTools.approvalPreview(CompileResult(rule))
        assertTrue("Night charge" in text, text)
        assertTrue("Charger connected" in text, text)
        assertTrue("may use: ${Capability.Web.label}" in text, text)
        assertTrue("Direct steps may use: ${Capability.DeviceControl.label}" in text, text)
    }

    @Test fun approvalForAFailedCompileSaysSo() {
        val text = AutomationTools.approvalPreview(CompileResult(null, errors = listOf("no trigger")))
        assertTrue("could not be compiled" in text && "no trigger" in text, text)
    }
}
