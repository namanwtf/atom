package atom.automation.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import atom.automation.Automations
import atom.automation.Capability
import atom.automation.schedule.MissedRunPolicy
import atom.automation.schedule.Planner
import atom.automation.schedule.Schedule
import atom.automation.schedule.ScheduleText
import atom.automation.schedule.problem
import atom.automation.system.AutomationRuntime
import atom.automation.system.Health
import atom.automation.system.HealthItem
import atom.automation.task.Destination
import atom.automation.task.NotifyMode
import atom.automation.task.Presets
import atom.automation.task.RunStatus
import atom.automation.task.ScheduledTask
import atom.automation.task.TaskRun
import atom.core.llm.ReasoningEffort
import atom.ui.components.AtomCard
import atom.ui.components.AtomSwitch
import atom.ui.components.BottomActionBar
import atom.ui.components.CircleIconButton
import atom.ui.components.CompactButton
import atom.ui.components.Group
import atom.ui.components.GroupDivider
import atom.ui.components.GroupRow
import atom.ui.components.LargeTitleScreen
import atom.ui.components.PrimaryButton
import atom.ui.components.SectionLabel
import atom.ui.components.SegmentedControl
import atom.ui.components.StatusLine
import atom.ui.feature.FeatureNav
import atom.ui.theme.extras
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

internal const val R_TASKS = "automation/tasks"
internal const val R_TASK = "automation/task/"

@Composable
internal fun rememberRuntime(): AutomationRuntime {
    val context = LocalContext.current
    return remember { Automations.attachIfNeeded(context); AutomationRuntime.get(context).also { it.start() } }
}

/** Recomputed whenever the screen resumes, so returning from a settings page refreshes the checks. */
@Composable
internal fun <T> rememberOnResume(compute: (Context) -> T): T {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    return remember(tick) { compute(context) }
}

internal fun Context.launch(intent: Intent?) {
    if (intent == null) return
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { runCatching { startActivity(Health.appSettings(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}

@Composable
internal fun HealthCard(items: List<HealthItem>) {
    val context = LocalContext.current
    val bad = items.count { !it.ok }
    Group {
        Box(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            StatusLine(
                if (bad == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.extras.warning,
                if (bad == 0) "All good" else if (bad == 1) "1 item needs attention" else "$bad items need attention",
            )
        }
        items.forEach { h ->
            GroupDivider(18.dp)
            GroupRow(h.title, subtitle = h.detail, trailing = {
                if (h.ok) HealthDot(true) else CompactButton("Fix", { context.launch(h.fix) })
            })
        }
    }
}

private fun statusLine(task: ScheduledTask, last: TaskRun?, now: Instant): String = when {
    last?.status == RunStatus.Running -> "Running now"
    !task.enabled && task.pausedReason != null -> task.pausedReason
    !task.enabled -> "Off"
    task.nextRunAt != null -> "Next " + ScheduleText.relative(Instant.ofEpochMilli(task.nextRunAt), now)
    else -> "Finished"
}

@Composable
internal fun TasksScreen(nav: FeatureNav) {
    val rt = rememberRuntime()
    val snap by rt.state.flow.collectAsState()
    val health = rememberOnResume { Health.scheduled(it) }
    val now = Instant.now()
    LargeTitleScreen(
        "Scheduled tasks",
        onBack = nav::back,
        actions = { CircleIconButton(Icons.Filled.Add, "New task", { nav.open(R_TASK + "new") }, Modifier.padding(end = 8.dp)) },
    ) {
        item { HealthCard(health) }
        item { SectionLabel("All tasks · ${snap.tasks.count { it.enabled }}/${snap.tasks.size} enabled") }
        item {
            if (snap.tasks.isEmpty()) {
                AtomCard { Text("No tasks yet. Start from a preset below or tap +.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                Group {
                    snap.tasks.sortedBy { it.nextRunAt ?: Long.MAX_VALUE }.forEachIndexed { i, t ->
                        if (i > 0) GroupDivider(18.dp)
                        val last = snap.taskRuns.filter { it.taskId == t.id }.maxByOrNull { it.startedAt }
                        GroupRow(
                            t.name,
                            subtitle = ScheduleText.describe(t.schedule) + " · " + statusLine(t, last, now) +
                                (last?.takeIf { it.status != RunStatus.Running }?.let { " · Last: ${it.status.label}" } ?: ""),
                            onClick = { nav.open(R_TASK + t.id) },
                            trailing = { AtomSwitch(t.enabled, { rt.tasks.setEnabled(t.id, it) }) },
                        )
                    }
                }
            }
        }
        item { SectionLabel("Quick start") }
        item {
            Group {
                Presets.all.forEachIndexed { i, p ->
                    if (i > 0) GroupDivider(18.dp)
                    NavRow(p.title, p.blurb) { nav.open(R_TASK + "new/$i") }
                }
            }
        }
    }
}

private enum class Kind(val label: String) { Daily("Daily"), Weekdays("Weekdays"), Weekly("Weekly"), Interval("Interval"), Once("One time"), Cron("Cron") }

private fun Schedule.kind() = when (this) {
    is Schedule.Daily -> Kind.Daily
    is Schedule.Weekdays -> Kind.Weekdays
    is Schedule.Weekly -> Kind.Weekly
    is Schedule.Interval -> Kind.Interval
    is Schedule.Once -> Kind.Once
    is Schedule.CronSchedule -> Kind.Cron
}

private fun Schedule.minuteOfDay(): Int = when (this) {
    is Schedule.Daily -> minuteOfDay
    is Schedule.Weekdays -> minuteOfDay
    is Schedule.Weekly -> minuteOfDay
    else -> 9 * 60
}

private fun convert(s: Schedule, k: Kind, now: Long): Schedule = when (k) {
    Kind.Daily -> Schedule.Daily(s.minuteOfDay())
    Kind.Weekdays -> Schedule.Weekdays(s.minuteOfDay())
    Kind.Weekly -> Schedule.Weekly(setOf(java.time.DayOfWeek.MONDAY), s.minuteOfDay())
    Kind.Interval -> Schedule.Interval(60, now)
    Kind.Once -> Schedule.Once(now + 60 * 60_000L)
    Kind.Cron -> Schedule.CronSchedule("0 9 * * 1-5")
}

private val INTERVALS = listOf(15, 30, 60, 120, 180, 360, 720, 1440, 2880, 10080)
private val DELAYS = listOf(15, 30, 60, 120, 240, 480, 1440)

@Composable
internal fun TaskEditorScreen(taskId: String?, presetIndex: Int?, nav: FeatureNav) {
    val rt = rememberRuntime()
    val initial = remember {
        val now = System.currentTimeMillis()
        taskId?.let { rt.state.task(it) }
            ?: presetIndex?.let { Presets.all.getOrNull(it)?.build?.invoke(now) }
            ?: ScheduledTask("", "", "", Schedule.Daily(9 * 60), createdAt = now)
    }
    var t by remember { mutableStateOf(initial) }
    var cronText by remember { mutableStateOf((initial.schedule as? Schedule.CronSchedule)?.expression ?: "0 9 * * 1-5") }
    var customMinutes by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
    val now = Instant.now()
    val zone = ZoneId.systemDefault()
    val problem = t.schedule.problem(now)
    val valid = t.name.isNotBlank() && t.prompt.isNotBlank() && problem == null
    val models = Automations.models

    LargeTitleScreen(
        if (taskId == null) "New task" else "Edit task",
        onBack = nav::back,
        bottomBar = {
            BottomActionBar {
                PrimaryButton("Save", {
                    val saved = t.copy(id = t.id.ifEmpty { UUID.randomUUID().toString() }, name = t.name.trim(), prompt = t.prompt.trim())
                    rt.tasks.save(saved)
                    nav.back()
                }, enabled = valid)
            }
        },
    ) {
        item {
            Group {
                AtomField(t.name, { t = t.copy(name = it) }, "Name")
                GroupDivider(18.dp)
                AtomField(t.prompt, { t = t.copy(prompt = it) }, "What should Atom do?", singleLine = false, minLines = 3)
            }
        }
        item { SectionLabel("Schedule") }
        item {
            Group {
                ChoiceRow("Repeats", t.schedule.kind(), Kind.entries, { it.label }, { k ->
                    if (k != t.schedule.kind()) t = t.copy(schedule = convert(t.schedule, k, System.currentTimeMillis()))
                    if (k == Kind.Cron) cronText = (t.schedule as Schedule.CronSchedule).expression
                })
                GroupDivider(18.dp)
                when (val s = t.schedule) {
                    is Schedule.Daily -> TimeRow("At", s.minuteOfDay) { t = t.copy(schedule = s.copy(minuteOfDay = it)) }
                    is Schedule.Weekdays -> TimeRow("At", s.minuteOfDay) { t = t.copy(schedule = s.copy(minuteOfDay = it)) }
                    is Schedule.Weekly -> {
                        DayPicker(s.days) { t = t.copy(schedule = s.copy(days = it)) }
                        GroupDivider(18.dp)
                        TimeRow("At", s.minuteOfDay) { t = t.copy(schedule = s.copy(minuteOfDay = it)) }
                    }
                    is Schedule.Interval -> {
                        PillChoices(INTERVALS, s.minutes.takeIf { it in INTERVALS }, { ScheduleText.duration(it) }) {
                            t = t.copy(schedule = s.copy(minutes = it))
                        }
                        GroupDivider(18.dp)
                        AtomField(customMinutes, { v ->
                            customMinutes = v.filter(Char::isDigit)
                            customMinutes.toIntOrNull()?.let { t = t.copy(schedule = s.copy(minutes = it)) }
                        }, "Custom, in minutes (15 to 10080)", number = true)
                    }
                    is Schedule.Once -> {
                        val left = ((s.atMillis - now.toEpochMilli()) / 60_000L).toInt()
                        PillChoices(DELAYS, null, { "In " + ScheduleText.duration(it) }) {
                            t = t.copy(schedule = Schedule.Once(System.currentTimeMillis() + it * 60_000L))
                        }
                        GroupDivider(18.dp)
                        AtomField(customMinutes, { v ->
                            customMinutes = v.filter(Char::isDigit)
                            customMinutes.toIntOrNull()?.takeIf { it > 0 }?.let { t = t.copy(schedule = Schedule.Once(System.currentTimeMillis() + it * 60_000L)) }
                        }, "Custom delay, in minutes", number = true)
                        if (left > 0) Note("Runs ${ScheduleText.relative(Instant.ofEpochMilli(s.atMillis), now)}")
                    }
                    is Schedule.CronSchedule -> {
                        AtomField(cronText, { v ->
                            cronText = v
                            t = t.copy(schedule = Schedule.CronSchedule(v.trim()))
                        }, "minute hour day month weekday", label = "Cron")
                        Note("Five fields, e.g. 0 9 * * 1-5 (09:00 on weekdays) or */30 8-20 * * * (every 30 min, 08-20).")
                    }
                }
            }
        }
        if (problem != null) item { Note(problem, warning = true) }
        item { SectionLabel("Next runs") }
        item {
            val preview = if (problem == null) Planner.preview(t.schedule, t.quietHours, now, zone, 5) else emptyList()
            Group {
                if (preview.isEmpty()) GroupRow("No upcoming runs")
                preview.forEachIndexed { i, at ->
                    if (i > 0) GroupDivider(18.dp)
                    val raw = t.schedule.next(if (i == 0) now else preview[i - 1], zone)
                    GroupRow(ScheduleText.relative(at, now), subtitle = if (raw != null && raw != at) "Moved from ${ScheduleText.relative(raw, now)} by quiet hours" else null)
                }
            }
        }
        item { SectionLabel("Notifications") }
        item {
            Group {
                Box(Modifier.padding(12.dp)) {
                    SegmentedControl(
                        listOf("Every run" to null, "On change" to null, "Silent" to null),
                        NotifyMode.entries.indexOf(t.notify),
                        { t = t.copy(notify = NotifyMode.entries[it]) },
                    )
                }
                Note(
                    when (t.notify) {
                        NotifyMode.EveryRun -> "Notify the result of every run."
                        NotifyMode.OnChange -> "Notify only when the result differs from the memo of the last run."
                        NotifyMode.Silent -> "Keep results in the run timeline; only failures alert you."
                    },
                )
                GroupDivider(18.dp)
                GroupRow("Progress notification", subtitle = "Show a notification while it runs", trailing = {
                    AtomSwitch(t.progressNotification, { t = t.copy(progressNotification = it) })
                })
            }
        }
        item { Gap(8) }
        item { Group { NavRow("Advanced", value = if (advanced) "Hide" else null) { advanced = !advanced } } }
        if (advanced) {
            item { SectionLabel("Model") }
            item {
                Group {
                    val modelIds = listOf<String?>(null) + models.choices().map { it.id }
                    ChoiceRow("Model", t.modelId, modelIds, { models.label(it) }, { t = t.copy(modelId = it) })
                    GroupDivider(18.dp)
                    val efforts = listOf<String?>(null) + ReasoningEffort.entries.map { it.name }
                    ChoiceRow("Reasoning", t.reasoning, efforts, { it ?: "Follow global" }, { t = t.copy(reasoning = it) })
                }
            }
            item { SectionLabel("Capabilities") }
            item { CapabilityGroup(t.capabilities) { t = t.copy(capabilities = it) } }
            item { SectionLabel("Results go to") }
            item {
                Group {
                    Box(Modifier.padding(12.dp)) {
                        SegmentedControl(
                            listOf("New chat each run" to null, "One conversation" to null),
                            Destination.entries.indexOf(t.destination),
                            { t = t.copy(destination = Destination.entries[it]) },
                        )
                    }
                }
            }
            item { SectionLabel("Missed runs") }
            item {
                Group {
                    Box(Modifier.padding(12.dp)) {
                        SegmentedControl(
                            listOf("Catch up once" to null, "Skip" to null),
                            MissedRunPolicy.entries.indexOf(t.missedPolicy),
                            { t = t.copy(missedPolicy = MissedRunPolicy.entries[it]) },
                        )
                    }
                    Note(if (t.missedPolicy == MissedRunPolicy.CatchUp) "If the phone was off or asleep, run once when it wakes, within 3 hours." else "Missed runs are skipped.")
                }
            }
            item { SectionLabel("Quiet hours") }
            item {
                Group {
                    GroupRow("Quiet hours", subtitle = "No runs; a run due inside waits until it ends", trailing = {
                        AtomSwitch(t.quietHours.enabled, { t = t.copy(quietHours = t.quietHours.copy(enabled = it)) })
                    })
                    if (t.quietHours.enabled) {
                        GroupDivider(18.dp)
                        TimeRow("From", t.quietHours.startMinute) { t = t.copy(quietHours = t.quietHours.copy(startMinute = it)) }
                        GroupDivider(18.dp)
                        TimeRow("Until", t.quietHours.endMinute) { t = t.copy(quietHours = t.quietHours.copy(endMinute = it)) }
                    }
                }
            }
            item { SectionLabel("Max duration") }
            item {
                Group {
                    Box(Modifier.padding(12.dp)) {
                        SegmentedControl(
                            ScheduledTask.MAX_MINUTES_OPTIONS.map { "$it min" to null },
                            ScheduledTask.MAX_MINUTES_OPTIONS.indexOf(t.maxMinutes).coerceAtLeast(0),
                            { t = t.copy(maxMinutes = ScheduledTask.MAX_MINUTES_OPTIONS[it]) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun CapabilityGroup(caps: Set<Capability>, onChange: (Set<Capability>) -> Unit) {
    Group {
        GroupRow("Chat only", subtitle = "No tools; the model answers from what it knows", trailing = {
            AtomSwitch(caps.isEmpty(), { on -> onChange(if (on) emptySet() else Capability.DEFAULT) })
        })
        Capability.entries.forEach { c ->
            GroupDivider(18.dp)
            GroupRow(
                c.label,
                subtitle = if (c == Capability.AppUi) "Drives other apps' screens. On a locked phone you get a reminder instead." else null,
                trailing = { AtomSwitch(c in caps, { on -> onChange(if (on) caps + c else caps - c) }) },
            )
        }
    }
}

@Composable
internal fun TaskDetailScreen(taskId: String, nav: FeatureNav) {
    val rt = rememberRuntime()
    val snap by rt.state.flow.collectAsState()
    val task = snap.tasks.firstOrNull { it.id == taskId }
    var confirmDelete by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    if (task == null) {
        LargeTitleScreen("Task", onBack = nav::back) { item { AtomCard { Text("This task was deleted.") } } }
        return
    }
    val runs = snap.taskRuns.filter { it.taskId == taskId }.sortedByDescending { it.startedAt }
    val running = runs.firstOrNull()?.status == RunStatus.Running
    val now = Instant.now()
    LargeTitleScreen(
        task.name,
        onBack = nav::back,
        actions = { CircleIconButton(Icons.Filled.Edit, "Edit", { nav.open(R_TASK + task.id + "/edit") }, Modifier.padding(end = 8.dp)) },
    ) {
        item {
            AtomCard {
                StatusLine(
                    if (task.pausedReason != null && !task.enabled) MaterialTheme.extras.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                    statusLine(task, runs.firstOrNull(), now),
                )
                Text(ScheduleText.describe(task.schedule) + " · " + Capability.describe(task.capabilities), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CompactButton("Run now", { message = rt.tasks.runNow(task.id) }, enabled = !running)
                    if (running) CompactButton("Stop", { rt.tasks.stop(task.id) }, primary = false)
                    CompactButton(if (task.enabled) "Turn off" else "Turn on", { rt.tasks.setEnabled(task.id, !task.enabled) }, primary = false)
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        item { SectionLabel("Prompt") }
        item { AtomCard { Text(task.prompt, style = MaterialTheme.typography.bodyLarge) } }
        item { SectionLabel("Memo") }
        item {
            AtomCard {
                Text(
                    task.memo?.take(1200) ?: "Empty. The next successful result is saved here and the run after it is compared against it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (task.memo == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                if (task.memo != null) CompactButton("Clear memo", { rt.tasks.clearMemo(task.id) }, primary = false)
            }
        }
        item { SectionLabel("Runs") }
        item {
            Group {
                if (runs.isEmpty()) GroupRow("No runs yet")
                runs.forEachIndexed { i, r ->
                    if (i > 0) GroupDivider(18.dp)
                    val open = r.conversationId
                    GroupRow(
                        r.status.label + " · " + ScheduleText.relative(Instant.ofEpochMilli(r.startedAt), now) + if (r.manual) " · manual" else "",
                        subtitle = r.summary.take(200).ifEmpty { null },
                        onClick = open?.let { { nav.openChat(it) } },
                        trailing = if (open != null) { { atom.ui.components.RowChevron() } } else null,
                    )
                }
            }
        }
        item { Gap(20) }
        item { Group { GroupRow("Delete task", onClick = { confirmDelete = true }) } }
    }
    if (confirmDelete) {
        ConfirmDialog("Delete task?", "\"${task.name}\" and its run history will be removed.", "Delete", { confirmDelete = false }) {
            confirmDelete = false
            rt.tasks.delete(task.id)
            nav.back()
        }
    }
}
