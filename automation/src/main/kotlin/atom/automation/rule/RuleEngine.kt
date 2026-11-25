package atom.automation.rule

import atom.automation.AutomationState
import atom.automation.RunLauncher
import atom.automation.RunRequest
import atom.automation.ToolInvoker
import atom.automation.schedule.MissedRunPolicy
import atom.automation.schedule.ScheduleText
import atom.ui.feature.HostRun
import atom.ui.feature.RunPhase
import atom.ui.feature.RunSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.util.UUID

interface RulePorts {
    fun device(): DeviceState
    fun tools(): ToolInvoker?
    fun launcher(): RunLauncher
    /** Waits for an agent run to end; null when it could not be observed in time. */
    suspend fun awaitRun(conversationId: String, timeoutMs: Long): HostRun?
    /** Continue [rule] from [actionIndex] once the phone is charging or idle (WorkManager). */
    fun defer(rule: Rule, actionIndex: Int, vars: Map<String, String>, waitFor: WaitFor)
    fun notify(rule: Rule, title: String, text: String)
    fun wakeupsChanged()
    /** The set of rules changed; register or drop system listeners to match. */
    fun listenersChanged()
}

data class DryLine(val text: String, val ok: Boolean? = null)

/**
 * Executes automations. It is only ever called because the system reported an event (or an alarm fired); it never
 * polls. Each rule runs its actions in order; a rule that is still running when its trigger fires again is skipped.
 */
class RuleEngine(
    private val state: AutomationState,
    private val ports: RulePorts,
    private val scope: CoroutineScope,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val running = MutableStateFlow<Set<String>>(emptySet())

    fun isRunning(ruleId: String) = ruleId in running.value

    /** Suspends until no rule is executing. */
    suspend fun awaitIdle() {
        running.first { it.isEmpty() }
    }

    private fun claim(ruleId: String): Boolean {
        while (true) {
            val cur = running.value
            if (ruleId in cur) return false
            if (running.compareAndSet(cur, cur + ruleId)) return true
        }
    }

    private fun release(ruleId: String) = running.update { it - ruleId }

    fun save(rule: Rule): Rule {
        val now = ports.device().now
        val withId = rule.copy(
            id = rule.id.ifEmpty { UUID.randomUUID().toString() },
            createdAt = rule.createdAt.takeIf { it > 0 } ?: now.toEpochMilli(),
        )
        state.upsertRule(withId)
        ports.wakeupsChanged()
        ports.listenersChanged()
        return withId
    }

    fun setEnabled(ruleId: String, enabled: Boolean) {
        state.updateRule(ruleId) { it.copy(enabled = enabled) }
        ports.wakeupsChanged()
        ports.listenersChanged()
    }

    fun delete(ruleId: String) {
        state.deleteRule(ruleId)
        ports.wakeupsChanged()
        ports.listenersChanged()
    }

    // ------------------------------------------------------------------ time triggers

    fun nextFire(rule: Rule, after: Instant): Instant? {
        val t = rule.trigger as? Trigger.Time ?: return null
        val schedule = ScheduleText.parseOrNull(t.schedule, Instant.ofEpochMilli(rule.createdAt)) ?: return null
        return schedule.next(after, ports.device().zone)
    }

    fun earliestWakeup(after: Instant): Long? = state.value.rules
        .filter { it.enabled }
        .mapNotNull { r -> nextFire(r, Instant.ofEpochMilli(r.lastFiredAt ?: r.createdAt)) }
        .minOfOrNull { maxOf(it.toEpochMilli(), after.toEpochMilli()) }

    /** Fires time-triggered rules that are due; slots missed by more than the grace period are skipped. */
    fun onWake() {
        val now = ports.device().now
        for (rule in state.value.rules.filter { it.enabled && it.trigger is Trigger.Time }) {
            val since = Instant.ofEpochMilli(rule.lastFiredAt ?: rule.createdAt)
            val due = nextFire(rule, since) ?: continue
            if (due.isAfter(now.plusMillis(1_000))) continue
            if (now.toEpochMilli() - due.toEpochMilli() <= MissedRunPolicy.ON_TIME_GRACE_MS) {
                fire(rule, TriggerEvent.Time(rule.id))
            } else {
                state.updateRule(rule.id) { it.copy(lastFiredAt = now.toEpochMilli()) }
            }
        }
        ports.wakeupsChanged()
    }

    // ------------------------------------------------------------------ events

    fun onEvent(event: TriggerEvent) {
        state.value.rules.filter { it.enabled && Triggers.matches(it.trigger, event, it.id) }.forEach { fire(it, event) }
    }

    private fun fire(rule: Rule, event: TriggerEvent) {
        if (!claim(rule.id)) return
        if (rule.trigger is Trigger.Time) state.updateRule(rule.id) { it.copy(lastFiredAt = ports.device().now.toEpochMilli()) }
        scope.launch {
            try {
                execute(rule, event)
            } finally {
                release(rule.id)
            }
        }
    }

    /** Continues a rule deferred until charging/idle. Conditions were already checked when it fired. */
    suspend fun resume(ruleId: String, actionIndex: Int, vars: Map<String, String>) {
        val rule = state.rule(ruleId)?.takeIf { it.enabled } ?: return
        if (!claim(rule.id)) return
        try {
            run(rule, "Resumed (${rule.actions.getOrNull(actionIndex)?.let { "step ${actionIndex + 1}" } ?: "end"})", actionIndex, vars, checkConditions = false, resumed = true)
        } finally {
            release(rule.id)
        }
    }

    suspend fun execute(rule: Rule, event: TriggerEvent): RuleRun? {
        val device = ports.device()
        return run(rule, event.summary, 0, Templates.vars(event, device), checkConditions = true, resumed = false)
    }

    private suspend fun run(rule: Rule, eventLabel: String, from: Int, vars: Map<String, String>, checkConditions: Boolean, resumed: Boolean): RuleRun? {
        val started = ports.device().now.toEpochMilli()
        if (checkConditions) {
            val device = ports.device()
            if (!rule.conditions.all { Conditions.holds(it, device) }) return null
        }
        val steps = mutableListOf<String>()
        var outcome = RuleOutcome.Completed
        var failed = false
        loop@ for (i in from until rule.actions.size) {
            val n = i + 1
            when (val a = rule.actions[i]) {
                is Action.Agent -> {
                    val device = ports.device()
                    val ready = when (a.waitFor) {
                        WaitFor.None -> true
                        WaitFor.Charging -> resumed || device.charging == true
                        WaitFor.Idle -> resumed
                    }
                    if (!ready) {
                        ports.defer(rule, i, vars, a.waitFor)
                        steps += "$n. Waiting until the phone is ${if (a.waitFor == WaitFor.Charging) "charging" else "idle"}"
                        outcome = RuleOutcome.Deferred
                        break@loop
                    }
                    val prompt = Templates.render(a.prompt, vars)
                    val conv = runCatching {
                        ports.launcher().start(RunRequest(prompt, null, RunSource.Automation, a.capabilities, null, null, rule.name, showProgress = false))
                    }.getOrNull()
                    if (conv == null) {
                        steps += "$n. Could not start the agent"
                        failed = true
                        continue@loop
                    }
                    val end = ports.awaitRun(conv, AGENT_TIMEOUT_MS)
                    steps += when (end?.phase) {
                        RunPhase.Done -> "$n. Agent finished: " + end.text.trim().take(200)
                        null -> "$n. Agent still running; continued"
                        else -> { failed = true; "$n. Agent ${end.phase.name.lowercase()}: ${end.error ?: ""}".trim() }
                    }
                }
                is Action.Tool -> {
                    val invoker = ports.tools()
                    if (invoker == null) {
                        steps += "$n. ${a.tool}: tools are not available"
                        failed = true
                        continue@loop
                    }
                    val result = runCatching { invoker.invoke(a.tool, renderArgs(a.args, vars)) }
                        .getOrElse { atom.core.tool.ToolResult.error(it.message ?: "error") }
                    steps += "$n. ${a.tool}: " + (if (result.sensitive) "done" else result.text.trim().take(160))
                    if (result.isError) failed = true
                }
                is Action.Notify -> {
                    ports.notify(rule, Templates.render(a.title, vars), Templates.render(a.text, vars))
                    steps += "$n. Notified"
                }
                is Action.Wait -> {
                    sleep(a.seconds.coerceIn(1, MAX_WAIT_SECONDS) * 1000L)
                    steps += "$n. Waited ${a.seconds} s"
                }
                is Action.StopUnless -> {
                    if (!Conditions.holds(a.condition, ports.device())) {
                        steps += "$n. Stopped: ${Conditions.describe(a.condition).replaceFirstChar { it.lowercase() }} is false"
                        outcome = RuleOutcome.Stopped
                        break@loop
                    }
                    steps += "$n. Check passed"
                }
            }
        }
        if (failed && outcome == RuleOutcome.Completed) outcome = RuleOutcome.Failed
        val run = RuleRun(UUID.randomUUID().toString(), rule.id, started, eventLabel, outcome, steps)
        state.putRuleRun(run)
        return run
    }

    /** What would happen right now, without doing anything. */
    fun dryRun(rule: Rule): List<DryLine> {
        val device = ports.device()
        val out = mutableListOf<DryLine>()
        out += DryLine("When: " + Triggers.describe(rule.trigger))
        nextFire(rule, device.now)?.let { out += DryLine("Next time: " + ScheduleText.relative(it, device.now, device.zone)) }
        var stopped = false
        for (c in rule.conditions) {
            val ok = Conditions.holds(c, device)
            out += DryLine("${Conditions.describe(c)} (now ${Conditions.currentValue(c, device)})", ok)
            if (!ok) stopped = true
        }
        if (stopped) {
            out += DryLine("If it fired now it would stop here: conditions not met", false)
            return out
        }
        val tools = ports.tools()
        val names = tools?.available()?.map { it.name }?.toSet()
        val vars = Templates.vars(TriggerEvent.Manual, device)
        for ((i, a) in rule.actions.withIndex()) {
            val n = i + 1
            when (a) {
                is Action.Tool -> {
                    val known = names?.contains(a.tool)
                    out += DryLine("$n. Would run ${a.tool} ${renderArgs(a.args, vars)}" + if (known == false) " - tool not available" else "", known != false && tools != null)
                }
                is Action.Agent -> out += DryLine(
                    "$n. Would ask Atom: \"${Templates.render(a.prompt, vars)}\"" + when (a.waitFor) {
                        WaitFor.None -> ""
                        WaitFor.Charging -> if (device.charging == true) " (charging now, so right away)" else " after the phone starts charging"
                        WaitFor.Idle -> " once the phone is idle"
                    },
                )
                is Action.Notify -> out += DryLine("$n. Would notify \"${Templates.render(a.title, vars)}\"")
                is Action.Wait -> out += DryLine("$n. Would wait ${a.seconds} s")
                is Action.StopUnless -> {
                    val ok = Conditions.holds(a.condition, device)
                    out += DryLine("$n. ${Conditions.describe(a.condition)} (now ${Conditions.currentValue(a.condition, device)})" + if (ok) "" else " - would stop here", ok)
                    if (!ok) return out
                }
            }
        }
        return out
    }

    companion object {
        const val AGENT_TIMEOUT_MS = 10 * 60_000L

        fun renderArgs(args: JsonObject, vars: Map<String, String>): JsonObject = render(args, vars) as JsonObject

        private fun render(e: JsonElement, vars: Map<String, String>): JsonElement = when (e) {
            is JsonObject -> JsonObject(e.mapValues { render(it.value, vars) })
            is JsonArray -> JsonArray(e.map { render(it, vars) })
            is JsonPrimitive -> if (e.isString && "{{" in e.content) JsonPrimitive(Templates.render(e.content, vars)) else e
        }
    }
}
