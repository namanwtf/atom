package atom.automation

import atom.automation.rule.BatteryEstimator
import atom.automation.rule.ConflictDetector
import atom.automation.rule.Triggers
import atom.automation.rule.describe
import atom.automation.schedule.Planner
import atom.automation.schedule.QuietHours
import atom.automation.schedule.ScheduleText
import atom.automation.schedule.problem
import atom.automation.system.AutomationRuntime
import atom.automation.task.NotifyMode
import atom.automation.task.ScheduledTask
import atom.core.llm.ToolSpec
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolContext
import atom.core.tool.ToolResult
import atom.core.tool.optBoolean
import atom.core.tool.optString
import atom.core.tool.schema
import atom.core.tool.string
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

private class SimpleTool(
    name: String,
    description: String,
    parameters: JsonObject,
    override val risk: Risk,
    private val body: suspend (JsonObject) -> ToolResult,
) : Tool {
    override val spec = ToolSpec(name, description, parameters)
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult =
        runCatching { body(args) }.getOrElse { ToolResult.error(it.message ?: it::class.simpleName ?: "error") }
}

/** Chat tools for creating and managing scheduled tasks and automations. */
internal object AutomationTools {
    fun all(rt: AutomationRuntime): List<Tool> = listOf(
        SimpleTool(
            "scheduled_task_create",
            "Create a scheduled task: Atom runs the prompt on a schedule and notifies the result. Schedule examples: " +
                "'daily 21:00', 'weekdays 07:45', 'weekly mon,thu 09:00', 'every 2h', 'in 30m', 'cron 0 9 * * 1-5'.",
            schema {
                string("name", "Short task name", required = true)
                string("prompt", "What Atom should do on each run", required = true)
                string("schedule", "When to run", required = true)
                string("notify", "When to notify", enum = listOf("every_run", "on_change", "silent"))
                string("capabilities", "Comma list from web, device_read, device_control, terminal, mcp, app_ui; empty for chat only")
                boolean("quiet_hours", "Defer runs between 23:00 and 07:30")
            },
            Risk.Sensitive,
        ) { a ->
            val now = Instant.now()
            val schedule = ScheduleText.parse(a.string("schedule"), now)
            schedule.problem(now)?.let { return@SimpleTool ToolResult.error(it) }
            val caps = a.optString("capabilities")?.split(',')?.mapNotNull { Capability.fromTag(it) }?.toSet() ?: Capability.TASK_DEFAULT
            val task = ScheduledTask(
                id = UUID.randomUUID().toString(),
                name = a.string("name").take(60),
                prompt = a.string("prompt"),
                schedule = schedule,
                notify = when (a.optString("notify")) { "on_change" -> NotifyMode.OnChange; "silent" -> NotifyMode.Silent; else -> NotifyMode.EveryRun },
                capabilities = caps,
                quietHours = QuietHours(enabled = a.optBoolean("quiet_hours") ?: false),
            )
            rt.tasks.save(task)
            val saved = rt.state.task(task.id)!!
            ToolResult.ok("Created \"${saved.name}\" (${ScheduleText.describe(saved.schedule)}), id ${saved.id}. Next run: ${next(saved)}.")
        },
        SimpleTool("scheduled_task_list", "List scheduled tasks with id, schedule, status and next run.", schema(), Risk.Read) {
            val tasks = rt.state.value.tasks
            if (tasks.isEmpty()) return@SimpleTool ToolResult.ok("No scheduled tasks.")
            ToolResult.ok(tasks.joinToString("\n") { t ->
                val last = rt.state.runsOf(t.id).firstOrNull()
                "- ${t.name} [${t.id}] ${ScheduleText.describe(t.schedule)}; " + (if (t.enabled) "on, next ${next(t)}" else "off" + (t.pausedReason?.let { " ($it)" } ?: "")) +
                    (last?.let { "; last: ${it.status.label}" } ?: "")
            })
        },
        SimpleTool(
            "scheduled_task_manage",
            "Enable, disable, run now or stop a scheduled task by id.",
            schema {
                string("id", "Task id from scheduled_task_list", required = true)
                string("action", "What to do", required = true, enum = listOf("enable", "disable", "run_now", "stop"))
            },
            Risk.Act,
        ) { a ->
            val id = a.string("id")
            val task = rt.state.task(id) ?: return@SimpleTool ToolResult.error("No task with id $id")
            when (a.string("action")) {
                "enable" -> rt.tasks.setEnabled(id, true)
                "disable" -> rt.tasks.setEnabled(id, false)
                "run_now" -> rt.tasks.runNow(id)?.let { return@SimpleTool ToolResult.error("Not started: $it") }
                "stop" -> rt.tasks.stop(id)
                else -> return@SimpleTool ToolResult.error("Unknown action")
            }
            ToolResult.ok("Done: ${a.string("action")} \"${task.name}\".")
        },
        SimpleTool(
            "scheduled_task_delete",
            "Delete a scheduled task and its run history.",
            schema { string("id", "Task id", required = true) },
            Risk.Sensitive,
        ) { a ->
            val task = rt.state.task(a.string("id")) ?: return@SimpleTool ToolResult.error("No such task")
            rt.tasks.delete(task.id)
            ToolResult.ok("Deleted \"${task.name}\".")
        },
        SimpleTool(
            "automation_create",
            "Create an event-driven automation from one plain sentence, e.g. 'When I plug in the charger after 11 PM, " +
                "turn on Do Not Disturb'. It is compiled once into a trigger, conditions and steps; no model runs at event " +
                "time unless a step asks for it. Returns the compiled rule.",
            schema {
                string("sentence", "The automation in the user's words", required = true)
                boolean("enabled", "Switch it on right away (default true)")
            },
            Risk.Sensitive,
        ) { a ->
            val result = Automations.compiler.compile(a.string("sentence"))
            val rule = result.rule
            if (rule == null || !result.ok) {
                return@SimpleTool ToolResult.error(
                    "Could not build a valid automation: " + result.errors.joinToString("; ") +
                        ". The user can create and fix it in Settings > Automations.",
                )
            }
            val saved = rt.rules.save(rule.copy(enabled = a.optBoolean("enabled") ?: true))
            val conflicts = ConflictDetector.conflictsFor(saved.id, rt.conflicts())
            ToolResult.ok(buildString {
                append("Saved \"${saved.name}\" [${saved.id}]").append(if (saved.enabled) " (on)" else " (off)").append(".\n")
                append("When: ${Triggers.describe(saved.trigger)}\n")
                saved.conditions.forEach { append("Only if: ${atom.automation.rule.Conditions.describe(it)}\n") }
                saved.actions.forEachIndexed { i, s -> append("${i + 1}. ${s.describe()}\n") }
                append("Battery: ${BatteryEstimator.estimate(saved).level.label}\n")
                if (result.warnings.isNotEmpty()) append("Notes: ${result.warnings.joinToString("; ")}\n")
                conflicts.forEach { c -> append("Conflict: ${c.message { id -> rt.state.rule(id)?.name ?: id }}\n") }
            }.trim())
        },
        SimpleTool("automation_list", "List automations with id, trigger, steps and on/off state.", schema(), Risk.Read) {
            val rules = rt.state.value.rules
            if (rules.isEmpty()) return@SimpleTool ToolResult.ok("No automations.")
            ToolResult.ok(rules.joinToString("\n") { r ->
                "- ${r.name} [${r.id}] ${if (r.enabled) "on" else "off"}: ${Triggers.describe(r.trigger)} -> " +
                    r.actions.joinToString("; ") { it.describe() }
            })
        },
        SimpleTool(
            "automation_set_enabled",
            "Switch an automation on or off by id.",
            schema {
                string("id", "Automation id", required = true)
                boolean("enabled", "On or off", required = true)
            },
            Risk.Act,
        ) { a ->
            val rule = rt.state.rule(a.string("id")) ?: return@SimpleTool ToolResult.error("No such automation")
            val on = a.optBoolean("enabled") ?: return@SimpleTool ToolResult.error("Missing enabled")
            rt.rules.setEnabled(rule.id, on)
            ToolResult.ok("\"${rule.name}\" is now ${if (on) "on" else "off"}.")
        },
        SimpleTool(
            "automation_delete",
            "Delete an automation and its history.",
            schema { string("id", "Automation id", required = true) },
            Risk.Sensitive,
        ) { a ->
            val rule = rt.state.rule(a.string("id")) ?: return@SimpleTool ToolResult.error("No such automation")
            rt.rules.delete(rule.id)
            ToolResult.ok("Deleted \"${rule.name}\".")
        },
    )

    private fun next(t: ScheduledTask): String {
        val now = Instant.now()
        val at = t.nextRunAt?.let(Instant::ofEpochMilli) ?: Planner.next(t.schedule, t.quietHours, now, ZoneId.systemDefault())
        return at?.let { ScheduleText.relative(it, now) } ?: "none"
    }
}
