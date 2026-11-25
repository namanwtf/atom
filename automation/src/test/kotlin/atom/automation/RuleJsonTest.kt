package atom.automation

import atom.automation.rule.Action
import atom.automation.rule.Condition
import atom.automation.rule.Rule
import atom.automation.rule.RuleJson
import atom.automation.rule.Trigger
import atom.automation.rule.WaitFor
import atom.core.llm.ToolSpec
import atom.core.tool.schema
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuleJsonTest {
    private val tools = listOf(
        ToolSpec("set_charge_limit", "Cap charging", schema { integer("percent", "Limit", required = true) }),
        ToolSpec("set_network_mode", "Set 5G/4G", schema { string("mode", "Mode", required = true) }),
        ToolSpec("set_dnd", "Do not disturb", schema { boolean("on", "On", required = true) }),
    )

    @Test fun parsesMessyModelOutput() {
        val reply = """
            Sure! Here is the rule:
            ```json
            {
              "title": "Night charging",
              "when": {"type": "Charger Plugged In"},
              "if": [{"type": "between", "from": "11 PM", "to": "7am"}],
              "then": [
                {"type": "call_tool", "name": "set_charge_limt", "arguments": "{\"percent\": \"80\"}"},
                {"action": "tool", "tool": "set_network_mode", "params": {"mode": "4g"}},
                {"type": "run_agent", "instruction": "Summarise today's WhatsApp voice notes", "wait_until": "until charging"},
                {"type": "sleep", "duration": "2 min"},
              ]
            }
            ```
        """.trimIndent()
        val r = RuleJson.parse(reply, tools, "Whenever I plug in my charger after 11 PM...")
        assertTrue(r.ok, r.errors.toString())
        val rule = r.rule!!
        assertEquals("Night charging", rule.name)
        assertEquals(Trigger.ChargerConnected, rule.trigger)
        assertEquals(Condition.TimeWindow("23:00", "07:00"), rule.conditions.single())
        val first = assertIs<Action.Tool>(rule.actions[0])
        assertEquals("set_charge_limit", first.tool)
        assertEquals(JsonPrimitive("80"), first.args["percent"])
        assertEquals("set_network_mode", (rule.actions[1] as Action.Tool).tool)
        val agent = assertIs<Action.Agent>(rule.actions[2])
        assertEquals(WaitFor.Charging, agent.waitFor)
        assertEquals(120, (rule.actions[3] as Action.Wait).seconds)
        assertTrue(r.warnings.any { "set_charge_limt" in it })
    }

    @Test fun unknownToolBecomesAgentStep() {
        val r = RuleJson.parse("""{"name":"x","trigger":"screen_on","actions":[{"type":"tool","tool":"launch_rocket","args":{}}]}""", tools)
        assertTrue(r.ok)
        assertIs<Action.Agent>(r.rule!!.actions.single())
    }

    @Test fun missingRequiredToolArgumentIsAnError() {
        val r = RuleJson.parse("""{"name":"x","trigger":{"type":"screen_off"},"actions":[{"type":"tool","tool":"set_dnd","args":{}}]}""", tools)
        assertFalse(r.ok)
        assertTrue(r.errors.single().contains("on"))
    }

    @Test fun missingTriggerOrActionsFails() {
        assertFalse(RuleJson.parse("""{"name":"x","actions":[{"type":"notify","title":"hi"}]}""", null).ok)
        assertFalse(RuleJson.parse("""{"name":"x","trigger":"screen_on","actions":[]}""", null).ok)
        assertNull(RuleJson.parse("no json here", null).rule)
    }

    @Test fun normalisesTriggersAndConditions() {
        val r = RuleJson.parse(
            """{"name":"Low","trigger":{"type":"battery_below","level":"15%"},
               "conditions":[{"type":"weekdays"},{"type":"is_charging","value":"no"},{"type":"wifi","network":"Home"}],
               "actions":[{"type":"notification","message":"Low battery at {{battery}}%"}]}""",
            null,
        )
        assertTrue(r.ok, r.errors.toString())
        assertEquals(Trigger.BatteryLevel(15, "below"), r.rule!!.trigger)
        assertEquals(Condition.Days(listOf("mon", "tue", "wed", "thu", "fri")), r.rule!!.conditions[0])
        assertEquals(Condition.Charging(false), r.rule!!.conditions[1])
        assertEquals(Condition.WifiSsid("Home", true), r.rule!!.conditions[2])
        assertEquals(Action.Notify("Atom", "Low battery at {{battery}}%"), r.rule!!.actions.single())
    }

    @Test fun scheduleTriggerVariants() {
        fun trig(json: String) = RuleJson.parse("""{"name":"t","trigger":$json,"actions":[{"type":"notify","title":"x"}]}""", null).rule!!.trigger
        assertEquals(Trigger.Time("daily 23:00"), trig("""{"type":"time","time":"11pm"}"""))
        assertEquals(Trigger.Time("cron 0 9 * * 1-5"), trig("""{"type":"cron","cron":"0 9 * * 1-5"}"""))
        assertEquals(Trigger.Time("every 30m"), trig("""{"type":"interval","interval_minutes":30}"""))
        assertEquals(Trigger.Time("weekly mon,fri 08:00"), trig("""{"type":"schedule","schedule":"weekly mon,fri 8:00"}"""))
        assertFalse(RuleJson.parse("""{"name":"t","trigger":{"type":"schedule","schedule":"whenever"},"actions":[{"type":"notify","title":"x"}]}""", null).ok)
    }

    @Test fun stopUnlessWrapsCondition() {
        val r = RuleJson.parse("""{"name":"t","trigger":"unlocked","actions":[{"type":"only_if","type2":"x","condition":{"type":"battery","above":50}},{"type":"notify","title":"ok"}]}""", null)
        assertTrue(r.ok, r.errors.toString())
        assertEquals(Action.StopUnless(Condition.Battery(min = 50)), r.rule!!.actions[0])
    }

    @Test fun locationNeedsAPlace() {
        val r = RuleJson.parse("""{"name":"Home","trigger":{"type":"arrive","lat":0,"lng":0},"actions":[{"type":"notify","title":"hi"}]}""", null)
        assertFalse(r.ok)
        assertEquals("enter", (r.rule!!.trigger as Trigger.Location).transition)
    }

    @Test fun canonicalJsonRoundTrips() {
        val rule = Rule(
            id = "a",
            name = "n",
            trigger = Trigger.Notification("WhatsApp", "voice"),
            conditions = listOf(Condition.TimeWindow("22:00", "06:00")),
            actions = listOf(Action.Agent("Summarise {{event.text}}", WaitFor.Idle), Action.Wait(5)),
        )
        val back = RuleJson.json.decodeFromString(Rule.serializer(), RuleJson.encode(rule))
        assertEquals(rule, back)
        assertTrue(RuleJson.parse(RuleJson.encode(rule), null).ok)
    }
}
