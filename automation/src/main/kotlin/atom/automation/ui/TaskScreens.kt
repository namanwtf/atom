package atom.automation.ui

import atom.ui.permission.PermissionAsks
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import atom.automation.task.RunTimeline
import atom.automation.task.TaskListLabels
import atom.ui.components.ArrowRow
import atom.ui.components.AtomRow
import atom.ui.components.AtomTextButton
import atom.ui.components.RowValue
import atom.ui.components.SwitchRow
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
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
import atom.automation.task.Routine
import atom.automation.R
import androidx.compose.ui.res.stringResource
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

/**
 * Scheduled tasks and automations report through notifications. Nothing asks for them at setup; the first time
 * the user turns one on, Atom asks once, with the reason.
 */
internal fun Context.askNotificationsForResults() =
    PermissionAsks.notifications(this, "Atom sends each scheduled task's result, and any failure, as a notification.")

/** The health banner copy and button for one failing check; null when the check passes. */
private fun banner(h: HealthItem): Pair<String, String>? = if (h.ok) null else when (h.key) {
    Health.KEY_BATTERY -> "Allow unrestricted background battery" to "Open settings"
    Health.KEY_EXACT -> "Exact alarm permission not granted" to "Allow exact alarms"
    Health.KEY_NOTIFICATIONS -> "Notifications are turned off" to "Enable notifications"
    else -> h.title to "Fix"
}

/** Only the checks that fail, each as a banner row with its fix button. */
@Composable
internal fun HealthCard(items: List<HealthItem>) {
    val context = LocalContext.current
    val failing = items.mapNotNull { h -> banner(h)?.let { h to it } }
    if (failing.isEmpty()) return
    Group(Modifier.padding(top = 12.dp)) {
        failing.forEach { (h, copy) ->
            AtomRow(
                title = copy.first,
                summary = h.detail,
                titleColor = MaterialTheme.extras.warning,
                end = {
                    Box(Modifier.align(Alignment.CenterVertically)) { AtomTextButton(copy.second, { context.launch(h.fix) }, primary = true) }
                },
            )
        }
    }
}

@Composable
internal fun TasksScreen(nav: FeatureNav) {
    val context = LocalContext.current
    val rt = rememberRuntime()
    val snap by rt.state.flow.collectAsState()
    val health = rememberOnResume { Health.scheduled(it) }
    val now = Instant.now()
    var asking by remember { mutableStateOf(false) }
    val routines = remember(nav) { RoutineLauncher(ask = { asking = true }, onOpen = { nav.open(R_TASK + it) }) }
    PriceWatchDialog(asking, onDismiss = { asking = false }) { what, where ->
        asking = false
        nav.open(R_TASK + atom.automation.task.Routine.PriceWatch.route(what, where))
    }
    LargeTitleScreen(
        "Scheduled Tasks",
        onBack = nav::back,
        actions = {
            IconButton(onClick = { nav.open(R_TASK + "new") }, modifier = Modifier.padding(end = 12.dp)) {
                Icon(MiuixIcons.Add, "New Scheduled Task", tint = MiuixTheme.colorScheme.onSurface)
            }
        },
    ) {
        item { HealthCard(health) }
        if (snap.tasks.isEmpty()) {
            item(key = "routines-empty") { RoutinesEmptyCard(routines) }
        } else {
            item(key = "routines-label") { SectionLabel(stringResource(R.string.routines_section)) }
            item(key = "routines") { RoutineCards(routines) }
        }
        item { SectionLabel("Quick start") }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Presets.all.forEachIndexed { i, p -> PresetCard(p.title, p.blurb) { nav.open(R_TASK + "new/$i") } }
            }
        }
        item {
            Text(
                "Atom runs these in the background at the times you set, using the tools you allow, and sends you the result. " +
                    "Each run is kept in its own conversation so you can follow up.",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
            )
        }
        item { SectionLabel("All tasks · ${snap.tasks.count { it.enabled }}/${snap.tasks.size} enabled") }
        item {
            Group {
                if (snap.tasks.isEmpty()) AtomRow(title = "No scheduled tasks", summary = "Start from a Quick start card above or tap +.")
                snap.tasks.sortedBy { it.nextRunAt ?: Long.MAX_VALUE }.forEach { t ->
                    val last = snap.taskRuns.filter { it.taskId == t.id }.maxByOrNull { it.startedAt }
                    AtomRow(
                        title = t.name,
                        summary = TaskListLabels.summary(t, last) { at -> ScheduleText.relative(Instant.ofEpochMilli(at), now) },
                        onClick = { nav.open(R_TASK + t.id) },
                        end = { Box(Modifier.align(Alignment.CenterVertically)) { AtomSwitch(t.enabled, { on ->
                                rt.tasks.setEnabled(t.id, on)
                                if (on) context.askNotificationsForResults()
                            }) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetCard(title: String, blurb: String, onClick: () -> Unit) {
    Card(modifier = Modifier.width(220.dp), insideMargin = PaddingValues(16.dp), showIndication = true, onClick = onClick) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface, maxLines = 2)
            Text(blurb, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 3)
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
    Kind.Weekly -> Schedule.Weekly(java.time.DayOfWeek.entries.take(5).toSet(), s.minuteOfDay())
    Kind.Interval -> Schedule.Interval(60, now)
    Kind.Once -> Schedule.Once(now + 60 * 60_000L)
    Kind.Cron -> Schedule.CronSchedule("0 9 * * 1-5")
}

private val INTERVALS = listOf(15, 30, 60, 120, 180, 360, 720, 1440, 2880, 10080)
private val DELAYS = listOf(15, 30, 60, 120, 240, 480, 1440)

@Composable
internal fun TaskEditorScreen(taskId: String?, presetIndex: Int?, nav: FeatureNav, routine: Triple<Routine, String, String>? = null) {
    val rt = rememberRuntime()
    val context = LocalContext.current
    val initial = remember {
        val now = System.currentTimeMillis()
        taskId?.let { rt.state.task(it) }
            ?: presetIndex?.let { Presets.all.getOrNull(it)?.build?.invoke(now) }
            ?: routine?.let { (r, what, where) -> routineDraft(context, r, what, where, now) }
            ?: ScheduledTask("", "", "", Schedule.Daily(9 * 60), maxMinutes = ScheduledTask.newTaskMinutes, createdAt = now)
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
        if (taskId == null) "New Scheduled Task" else "Edit Scheduled Task",
        onBack = nav::back,
        bottomBar = {
            BottomActionBar {
                PrimaryButton("Save", {
                    val saved = t.copy(id = t.id.ifEmpty { UUID.randomUUID().toString() }, name = t.name.trim(), prompt = t.prompt.trim())
                    if (runCatching { rt.tasks.save(saved) }.isSuccess) {
                        if (saved.enabled) context.askNotificationsForResults()
                        nav.back()
                    } else {
                        Toast.makeText(context, "Failed to save scheduled task", Toast.LENGTH_SHORT).show()
                    }
                }, enabled = valid)
            }
        },
    ) {
        item {
            Group {
                AtomField(t.name, { t = t.copy(name = it) }, "Name")
                GroupDivider(18.dp)
                AtomField(t.prompt, { t = t.copy(prompt = it) }, "What should Atom do", singleLine = false, minLines = 3)
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
                GroupRow("Show progress notification while running", subtitle = "Off by default; results still arrive when the run ends", trailing = {
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
            item { SectionLabel("Max duration (minutes)") }
            item {
                Group {
                    Box(Modifier.padding(12.dp)) {
                        // A value set by the agent that is not one of the options shows as the nearest one.
                        val options = ScheduledTask.MAX_MINUTES_OPTIONS
                        SegmentedControl(
                            options.map { "$it" to null },
                            options.indexOf(options.minBy { o -> kotlin.math.abs(o - t.maxMinutes) }),
                            { t = t.copy(maxMinutes = options[it]) },
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
            AtomSwitch(caps.isEmpty(), { on -> onChange(if (on) emptySet() else Capability.TASK_DEFAULT) })
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
    val context = LocalContext.current
    val snap by rt.state.flow.collectAsState()
    val task = snap.tasks.firstOrNull { it.id == taskId }
    var confirmDelete by remember { mutableStateOf(false) }
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    if (task == null) {
        LargeTitleScreen("Task Details", onBack = nav::back) { item { Group(Modifier.padding(top = 12.dp)) { AtomRow(title = "This task was deleted.") } } }
        return
    }
    val runs = snap.taskRuns.filter { it.taskId == taskId }.sortedByDescending { it.startedAt }
    val running = runs.firstOrNull()?.status == RunStatus.Running
    val now = Instant.now()
    val models = Automations.models
    LargeTitleScreen(
        "Task Details",
        onBack = nav::back,
        actions = {
            IconButton(onClick = { nav.open(R_TASK + task.id + "/edit") }, modifier = Modifier.padding(end = 12.dp)) {
                Icon(MiuixIcons.Edit, "Edit", tint = MiuixTheme.colorScheme.onSurface)
            }
        },
    ) {
        item {
            Group(Modifier.padding(top = 12.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(task.name, style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface)
                    val next = task.nextRunAt?.takeIf { task.enabled }
                    Text(
                        when {
                            running -> "Running in background…"
                            next != null -> "Next run " + ScheduleText.relative(Instant.ofEpochMilli(next), now)
                            !task.enabled -> "Currently paused" + (task.pausedReason?.let { " · $it" } ?: "")
                            else -> "No upcoming runs"
                        },
                        style = MiuixTheme.textStyles.body2,
                        color = if (!task.enabled && task.pausedReason != null) MaterialTheme.extras.warning else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(6.dp))
                    if (running) {
                        AtomTextButton("Stop", {
                            rt.tasks.stop(task.id)
                            toast("Stopping current run…")
                        }, Modifier.fillMaxWidth())
                    } else {
                        AtomTextButton("Run now", {
                            when (val why = rt.tasks.runNow(task.id)) {
                                null -> toast("Started background test run")
                                "Already running" -> toast("This task is already running")
                                else -> toast(why)
                            }
                        }, Modifier.fillMaxWidth(), primary = true)
                    }
                }
            }
        }
        item {
            Group(Modifier.padding(top = 12.dp)) {
                SwitchRow(
                    title = "Enable schedule",
                    checked = task.enabled,
                    onCheckedChange = {
                        rt.tasks.setEnabled(task.id, it)
                        if (it) context.askNotificationsForResults()
                    },
                    summary = if (task.enabled) {
                        "Automatically wakes Atom to run " + ScheduleText.describe(task.schedule).replaceFirstChar { it.lowercase() }
                    } else {
                        "Automatic triggers paused; you can still run manually anytime"
                    },
                )
            }
        }
        item { SectionLabel("Prompt & configuration") }
        item {
            Group {
                Text(
                    task.prompt,
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
                AtomRow(title = "Model", end = { Box(Modifier.align(Alignment.CenterVertically)) { RowValue(models.label(task.modelId)) } })
                AtomRow(
                    title = "Notifications & session",
                    summary = TaskListLabels.badges(task).joinToString(" · "),
                )
                AtomRow(title = "Capabilities", summary = Capability.describe(task.capabilities))
            }
        }
        item { SectionLabel("Task memo") }
        item {
            Group {
                AtomRow(
                    title = task.memo?.take(1200) ?: "Empty",
                    summary = if (task.memo == null) "The next successful result is saved here, and the run after it is compared against it." else null,
                )
                if (task.memo != null) {
                    ArrowRow("Clear memo", {
                        rt.tasks.clearMemo(task.id)
                        toast("Task memo cleared")
                    })
                }
            }
        }
        item { SectionLabel("Run timeline") }
        item {
            Group {
                if (runs.isEmpty()) AtomRow(title = "No runs yet. Tap \"Run now\" above to test this task.")
                runs.forEach { r ->
                    val open = r.conversationId
                    val summary = listOfNotNull(
                        ScheduleText.relative(Instant.ofEpochMilli(r.startedAt), now),
                        r.summary.take(200).ifEmpty { null },
                        RunTimeline.tapHint(r),
                    ).joinToString("\n")
                    if (open != null) {
                        ArrowRow(RunTimeline.line(r), { nav.openChat(open) }, summary = summary)
                    } else {
                        AtomRow(title = RunTimeline.line(r), summary = summary)
                    }
                }
            }
        }
        item {
            Group(Modifier.padding(top = 16.dp)) {
                GroupRow("Delete scheduled task", titleColor = MiuixTheme.colorScheme.error, onClick = { confirmDelete = true })
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog("Delete scheduled task?", "\"${task.name}\" and its run history will be removed.", "Delete", { confirmDelete = false }) {
            confirmDelete = false
            rt.tasks.delete(task.id)
            nav.back()
        }
    }
}
