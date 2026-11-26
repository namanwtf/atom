package atom.automation

import atom.automation.rule.Action
import atom.automation.rule.BatteryEstimator
import atom.automation.rule.Condition
import atom.automation.rule.Conditions
import atom.automation.rule.ConflictDetector
import atom.automation.rule.CostLevel
import atom.automation.rule.DeviceState
import atom.automation.rule.LlmRuleCompiler
import atom.automation.rule.Rule
import atom.automation.rule.RuleEngine
import atom.automation.rule.RuleOutcome
import atom.automation.rule.RulePorts
import atom.automation.rule.StaticDeviceState
import atom.automation.rule.Trigger
import atom.automation.rule.TriggerEvent
import atom.automation.rule.WaitFor
import atom.core.llm.EngineCapabilities
import atom.core.llm.FinishReason
import atom.core.llm.LlmEngine
import atom.core.llm.LlmEvent
import atom.core.llm.LlmRequest
import atom.core.llm.ToolSpec
import atom.core.tool.ToolResult
import atom.core.tool.schema
import atom.ui.feature.HostRun
import atom.ui.feature.RunPhase
import atom.ui.feature.RunSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RuleEngineTest {
    private val zone = ZoneOffset.UTC
    private var device: DeviceState = StaticDeviceState(Instant.parse("2026-11-25T23:30:00Z"), zone, batteryPercent = 50, charging = false, wifiSsid = "Home")
    private val calls = mutableListOf<Pair<String, JsonObject>>()
    private val stepCaps = mutableListOf<Set<Capability>>()
    private val prompts = mutableListOf<RunRequest>()
    private val deferred = mutableListOf<Pair<Int, WaitFor>>()
    private val notes = mutableListOf<String>()
    private val slept = mutableListOf<Long>()

    private val invoker = object : ToolInvoker {
        override fun available() = listOf(ToolSpec("set_charge_limit", "", schema { integer("percent", "", true) }), ToolSpec("set_network_mode", "", schema()))
        override suspend fun invoke(name: String, args: JsonObject, capabilities: Set<Capability>): ToolResult {
            calls += name to args
            stepCaps += capabilities
            return if (name == "broken") ToolResult.error("nope") else ToolResult.ok("ok")
        }
    }

    private fun engine(scope: TestScope, state: AutomationState = AutomationState()) = state to RuleEngine(state, object : RulePorts {
        override fun device() = device
        override fun tools() = invoker
        override fun launcher() = RunLauncher { r -> prompts += r; "conv${prompts.size}" }
        override suspend fun awaitRun(conversationId: String, timeoutMs: Long) = HostRun(conversationId, "", RunSource.Automation, RunPhase.Done, "summary", emptyList())
        override fun defer(rule: Rule, actionIndex: Int, vars: Map<String, String>, waitFor: WaitFor) { deferred += actionIndex to waitFor }
        override fun notify(rule: Rule, title: String, text: String) { notes += "$title|$text" }
        override fun wakeupsChanged() {}
        override fun listenersChanged() {}
    }, scope.backgroundScope, sleep = { slept += it })

    private val nightCharge = Rule(
        name = "Night charge",
        trigger = Trigger.ChargerConnected,
        conditions = listOf(Condition.TimeWindow("23:00", "06:00")),
        actions = listOf(
            Action.Tool("set_charge_limit", JsonObject(mapOf("percent" to JsonPrimitive(80)))),
            Action.Tool("set_network_mode", JsonObject(mapOf("mode" to JsonPrimitive("4g")))),
            Action.Agent("Summarise today's voice notes", WaitFor.Charging),
        ),
    )

    @Test fun eventDispatchesActionsInOrder() = runTest {
        val (state, e) = engine(this)
        val saved = e.save(nightCharge)
        e.onEvent(TriggerEvent.ScreenOn)
        e.onEvent(TriggerEvent.ChargerConnected)
        runCurrent()
        assertEquals(listOf("set_charge_limit", "set_network_mode"), calls.map { it.first })
        // Direct steps are limited to the rule's step capabilities.
        assertEquals(listOf(Capability.STEP_DEFAULT, Capability.STEP_DEFAULT), stepCaps)
        assertEquals(listOf(2 to WaitFor.Charging), deferred)
        assertEquals(RuleOutcome.Deferred, state.historyOf(saved.id).single().outcome)
        e.resume(saved.id, 2, emptyMap())
        assertEquals("Summarise today's voice notes", prompts.single().prompt)
        assertEquals(RunSource.Automation, prompts.single().source)
        assertEquals(RuleOutcome.Completed, state.historyOf(saved.id).first().outcome)
    }

    @Test fun conditionsGateTheRule() = runTest {
        val (state, e) = engine(this)
        e.save(nightCharge)
        device = StaticDeviceState(Instant.parse("2026-11-25T15:00:00Z"), zone, charging = true)
        e.onEvent(TriggerEvent.ChargerConnected)
        runCurrent()
        assertTrue(calls.isEmpty())
        assertTrue(state.value.ruleRuns.isEmpty())
    }

    @Test fun stopUnlessWaitAndTemplates() = runTest {
        val (state, e) = engine(this)
        val r = e.save(
            Rule(
                name = "n",
                trigger = Trigger.Notification(app = "WhatsApp"),
                actions = listOf(
                    Action.Notify("From {{event.app}}", "{{event.title}}: {{event.text}}"),
                    Action.Wait(3),
                    Action.StopUnless(Condition.Charging(true)),
                    Action.Notify("never"),
                ),
            ),
        )
        e.onEvent(TriggerEvent.Notification("com.whatsapp", "WhatsApp", "Mum", "Call me"))
        runCurrent()
        assertEquals(listOf("From WhatsApp|Mum: Call me"), notes)
        assertEquals(listOf(3000L), slept)
        assertEquals(RuleOutcome.Stopped, state.historyOf(r.id).single().outcome)
    }

    @Test fun agentRunsRightAwayWhenAlreadyCharging() = runTest {
        val (_, e) = engine(this)
        e.save(nightCharge)
        device = StaticDeviceState(Instant.parse("2026-11-25T23:30:00Z"), zone, charging = true)
        e.onEvent(TriggerEvent.ChargerConnected)
        runCurrent()
        assertTrue(deferred.isEmpty())
        assertEquals(1, prompts.size)
    }

    @Test fun failedToolMarksRunFailedButContinues() = runTest {
        val (state, e) = engine(this)
        val r = e.save(Rule(name = "b", trigger = Trigger.ScreenOff, actions = listOf(Action.Tool("broken"), Action.Notify("after"))))
        e.onEvent(TriggerEvent.ScreenOff)
        runCurrent()
        assertEquals(listOf("after|"), notes)
        assertEquals(RuleOutcome.Failed, state.historyOf(r.id).single().outcome)
    }

    @Test fun overlappingFireIsSkipped() = runTest {
        val state = AutomationState()
        val e = RuleEngine(state, object : RulePorts {
            override fun device() = device
            override fun tools() = invoker
            override fun launcher() = RunLauncher { null }
            override suspend fun awaitRun(conversationId: String, timeoutMs: Long): HostRun? = null
            override fun defer(rule: Rule, actionIndex: Int, vars: Map<String, String>, waitFor: WaitFor) {}
            override fun notify(rule: Rule, title: String, text: String) { notes += title }
            override fun wakeupsChanged() {}
            override fun listenersChanged() {}
        }, backgroundScope, sleep = { kotlinx.coroutines.delay(it) })
        e.save(Rule(name = "slow", trigger = Trigger.Unlocked, actions = listOf(Action.Wait(10), Action.Notify("done"))))
        e.onEvent(TriggerEvent.Unlocked)
        e.onEvent(TriggerEvent.Unlocked)
        testScheduler.advanceTimeBy(20_000)
        testScheduler.runCurrent()
        assertEquals(listOf("done"), notes)
    }

    @Test fun dryRunHasNoSideEffects() = runTest {
        val (state, e) = engine(this)
        val lines = e.dryRun(nightCharge)
        assertTrue(calls.isEmpty() && prompts.isEmpty() && deferred.isEmpty())
        assertTrue(state.value.ruleRuns.isEmpty())
        assertTrue(lines.any { it.text.contains("set_charge_limit") })
        assertTrue(lines.any { it.text.contains("after the phone starts charging") })
        device = StaticDeviceState(Instant.parse("2026-11-25T12:00:00Z"), zone)
        assertEquals(false, e.dryRun(nightCharge).last().ok)
    }

    @Test fun timeTriggerFiresOnWake() = runTest {
        val (_, e) = engine(this)
        device = StaticDeviceState(Instant.parse("2026-11-25T08:00:00Z"), zone)
        e.save(Rule(name = "t", trigger = Trigger.Time("daily 09:00"), actions = listOf(Action.Notify("morning"))))
        assertEquals(Instant.parse("2026-11-25T09:00:00Z").toEpochMilli(), e.earliestWakeup(device.now))
        device = StaticDeviceState(Instant.parse("2026-11-25T09:00:01Z"), zone)
        e.onWake()
        runCurrent()
        assertEquals(listOf("morning|"), notes)
        assertEquals(Instant.parse("2026-11-26T09:00:00Z").toEpochMilli(), e.earliestWakeup(device.now))
    }

    @Test fun batteryCrossing() = runTest {
        val (_, e) = engine(this)
        e.save(Rule(name = "low", trigger = Trigger.BatteryLevel(20), actions = listOf(Action.Notify("low"))))
        e.onEvent(TriggerEvent.Battery(25, 26)); runCurrent()
        e.onEvent(TriggerEvent.Battery(20, 21)); runCurrent()
        e.onEvent(TriggerEvent.Battery(19, 20)); runCurrent()
        e.onEvent(TriggerEvent.Battery(20, null)); runCurrent()
        assertEquals(1, notes.size)
    }

    @Test fun conditionEvaluation() {
        val night = StaticDeviceState(Instant.parse("2026-11-25T23:30:00Z"), zone, batteryPercent = 40, charging = true, wifiSsid = "\"Home\"", rooted = true)
        assertTrue(Conditions.holds(Condition.TimeWindow("23:00", "07:00"), night))
        assertFalse(Conditions.holds(Condition.TimeWindow("08:00", "22:00"), night))
        assertTrue(Conditions.holds(Condition.Days(listOf("wed")), night))
        assertFalse(Conditions.holds(Condition.Days(listOf("sat", "sun")), night))
        assertTrue(Conditions.holds(Condition.Battery(30, 50), night))
        assertFalse(Conditions.holds(Condition.Battery(min = 41), night))
        assertTrue(Conditions.holds(Condition.Charging(true), night))
        assertTrue(Conditions.holds(Condition.WifiSsid("home"), night))
        assertTrue(Conditions.holds(Condition.WifiSsid("Office", connected = false), night))
        assertTrue(Conditions.holds(Condition.Rooted(true), night))
        val unknown = StaticDeviceState(night.now, zone)
        assertFalse(Conditions.holds(Condition.Battery(min = 10), unknown))
    }

    @Test fun conflictDetection() {
        fun r(id: String, value: String, trigger: Trigger = Trigger.ChargerConnected, conditions: List<Condition> = emptyList()) =
            Rule(id = id, name = id, trigger = trigger, conditions = conditions, actions = listOf(Action.Tool("set_network_mode", JsonObject(mapOf("mode" to JsonPrimitive(value))))))
        assertEquals(1, ConflictDetector.find(listOf(r("a", "4g"), r("b", "5g")), zone).size)
        assertTrue(ConflictDetector.find(listOf(r("a", "4g"), r("b", "4g")), zone).isEmpty())
        assertTrue(ConflictDetector.find(listOf(r("a", "4g"), r("b", "5g", Trigger.ChargerDisconnected)), zone).isEmpty())
        val night = listOf(Condition.TimeWindow("23:00", "06:00"))
        val day = listOf(Condition.TimeWindow("08:00", "20:00"))
        assertTrue(ConflictDetector.find(listOf(r("a", "4g", conditions = night), r("b", "5g", conditions = day)), zone).isEmpty())
        assertTrue(ConflictDetector.find(listOf(r("a", "4g", conditions = listOf(Condition.Charging(true))), r("b", "5g", conditions = listOf(Condition.Charging(false)))), zone).isEmpty())
        val now = Instant.parse("2026-11-25T00:00:00Z")
        val t1 = r("a", "4g", Trigger.Time("daily 22:00"))
        val t2 = r("b", "5g", Trigger.Time("cron 0 22 * * 1-5"))
        val t3 = r("c", "5g", Trigger.Time("daily 21:00"))
        assertEquals(1, ConflictDetector.find(listOf(t1, t2), zone, now).size)
        assertTrue(ConflictDetector.find(listOf(t1, t3), zone, now).isEmpty())
        assertTrue(ConflictDetector.find(listOf(r("a", "4g", Trigger.WifiConnected("Home")), r("b", "5g", Trigger.WifiConnected("Work"))), zone).isEmpty())
        assertEquals(1, ConflictDetector.find(listOf(r("a", "4g", Trigger.WifiConnected("Home")), r("b", "5g", Trigger.WifiConnected())), zone).size)
    }

    @Test fun batteryEstimates() {
        val now = Instant.parse("2026-11-25T00:00:00Z")
        fun cost(t: Trigger, vararg a: Action) = BatteryEstimator.estimate(Rule(name = "x", trigger = t, actions = a.toList(), createdAt = now.toEpochMilli()), zone, now).level
        assertEquals(CostLevel.None, cost(Trigger.BootCompleted, Action.Notify("x")))
        assertEquals(CostLevel.VeryLow, cost(Trigger.Time("daily 09:00"), Action.Notify("x")))
        assertEquals(CostLevel.Medium, cost(Trigger.Time("cron */15 * * * *"), Action.Notify("x")))
        assertEquals(CostLevel.Medium, cost(Trigger.Location(1.0, 1.0), Action.Notify("x")))
        assertEquals(CostLevel.Medium, cost(Trigger.ScreenOn, Action.Agent("x")))
        assertEquals(CostLevel.Low, cost(Trigger.Time("daily 09:00"), Action.Agent("x")))
        assertEquals(CostLevel.VeryLow, cost(Trigger.ScreenOn, Action.Agent("x", WaitFor.Charging)))
    }

    @Test fun compilerRetriesWithProblems() = runTest {
        val replies = ArrayDeque(listOf("""{"name":"x","trigger":"screen_on","actions":[]}""", """{"name":"x","trigger":"screen_on","actions":[{"type":"notify","title":"hi"}]}"""))
        val seen = mutableListOf<LlmRequest>()
        val llm = object : LlmEngine {
            override val id = "fake"
            override val capabilities = EngineCapabilities(true, false, 8000, false)
            override fun stream(request: LlmRequest): Flow<LlmEvent> {
                seen += request
                return flowOf(LlmEvent.TextDelta(replies.removeFirst()), LlmEvent.Finished(FinishReason.Stop))
            }
        }
        val compiler = LlmRuleCompiler({ EngineSelection(llm, "m") }, { listOf(ToolSpec("set_dnd", "Do not disturb", schema())) })
        val result = compiler.compile("When the screen turns on, say hi")
        assertTrue(result.ok, result.errors.toString())
        assertEquals(2, seen.size)
        assertTrue(seen[1].messages.last().text.contains("at least one action"))
        assertTrue(seen[0].messages.first().text.contains("set_dnd"))
        assertEquals("When the screen turns on, say hi", result.rule!!.sentence)
        assertFalse(LlmRuleCompiler({ null }, { emptyList() }).compile("x").ok)
    }
}
