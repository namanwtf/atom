package atom.automation.task

import atom.automation.Capability
import atom.automation.schedule.MissedRunPolicy
import atom.automation.schedule.QuietHours
import atom.automation.schedule.Schedule
import kotlinx.serialization.Serializable

@Serializable
enum class NotifyMode(val label: String) { EveryRun("Every run"), OnChange("Only on change"), Silent("Archive silently") }

@Serializable
enum class Destination(val label: String) { Archive("New chat per run"), Conversation("One conversation") }

@Serializable
data class ScheduledTask(
    val id: String,
    val name: String,
    val prompt: String,
    val schedule: Schedule,
    val enabled: Boolean = true,
    val notify: NotifyMode = NotifyMode.EveryRun,
    val progressNotification: Boolean = false,
    val modelId: String? = null,
    val reasoning: String? = null,
    val capabilities: Set<Capability> = Capability.TASK_DEFAULT,
    val destination: Destination = Destination.Archive,
    val conversationId: String? = null,
    val missedPolicy: MissedRunPolicy = MissedRunPolicy.CatchUp,
    val quietHours: QuietHours = QuietHours(),
    val maxMinutes: Int = 5,
    /** The last result, compared with the next one for "only on change" and given to the agent as context. */
    val memo: String? = null,
    val nextRunAt: Long? = null,
    val consecutiveFailures: Int = 0,
    val pausedReason: String? = null,
    val createdAt: Long = 0,
) {
    val usesPhoneUi: Boolean get() = Capability.AppUi in capabilities

    companion object {
        val MAX_MINUTES_OPTIONS = listOf(2, 5, 10, 15, 30, 60)

        /** The longest duration a task may be given (also through the agent's tools). */
        const val MAX_MINUTES_LIMIT = 120

        /**
         * The duration a new task starts with: 15 minutes, or what the user chose in Settings > Advanced. Tasks saved
         * before keep their own value.
         */
        @Volatile var newTaskMinutes: Int = 15
        /** Consecutive failed runs before the task pauses itself. */
        const val AUTO_PAUSE_AFTER = 5
    }
}

@Serializable
enum class RunStatus(val label: String) {
    Running("Running"),
    Succeeded("Delivered"),
    Unchanged("No change"),
    Failed("Failed"),
    TimedOut("Timed out"),
    Cancelled("Stopped"),
    Skipped("Skipped"),
    WaitingUnlock("Waiting for unlock"),
    Interrupted("Interrupted"),
    ;

    val terminal: Boolean get() = this != Running
    val failure: Boolean get() = this == Failed || this == TimedOut
}

@Serializable
data class TaskRun(
    val id: String,
    val taskId: String,
    val startedAt: Long,
    val status: RunStatus,
    val endedAt: Long? = null,
    val conversationId: String? = null,
    val summary: String = "",
    val manual: Boolean = false,
    /** A scheduled run that started late, after the phone was off or asleep at its time. */
    val catchUp: Boolean = false,
) {
    val trigger: RunTrigger get() = when {
        manual -> RunTrigger.Manual
        catchUp -> RunTrigger.CatchUp
        else -> RunTrigger.Scheduled
    }

    /** How long it ran, once it has ended. */
    fun durationMs(): Long? = endedAt?.let { (it - startedAt).coerceAtLeast(0) }
}

/** What started a run, as the run timeline labels it. */
enum class RunTrigger(val label: String) { Scheduled("Scheduled"), Manual("Manual run"), CatchUp("Catch-up") }

/** Copy for the run timeline (pure, unit-tested). */
object RunTimeline {
    /** "Manual run · Delivered · 1 min 5 s", or without the duration while running. */
    fun line(run: TaskRun): String = buildString {
        append(run.trigger.label).append(" · ").append(run.status.label)
        run.durationMs()?.takeIf { run.status != RunStatus.Skipped && run.status != RunStatus.WaitingUnlock }?.let { append(" · ").append(duration(it)) }
    }

    fun tapHint(run: TaskRun): String? = when {
        run.conversationId == null -> null
        run.status == RunStatus.Running -> "Tap to watch live progress"
        else -> "Tap to view full conversation and follow up"
    }

    fun duration(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "$s s"
            s < 3600 -> if (s % 60 == 0L) "${s / 60} min" else "${s / 60} min ${s % 60} s"
            else -> "${s / 3600} h ${(s % 3600) / 60} min"
        }
    }
}

/** Titles and fallback bodies of scheduled-task notifications (pure, unit-tested). */
object TaskNotices {
    const val COMPLETED_FALLBACK = "Scheduled task completed. Tap to view full conversation."
    const val FAILED_FALLBACK = "Execution failed. Tap to inspect details."

    fun failedTitle(name: String) = "Task failed: $name"
    fun pausedTitle(name: String) = "Task paused: $name"
    fun readyTitle(name: String) = "Ready to run: $name"

    fun resultBody(text: String) = text.ifBlank { COMPLETED_FALLBACK }
    fun failedBody(summary: String) = summary.ifBlank { FAILED_FALLBACK }
}

/** Copy for the Scheduled Tasks list and the task detail (pure, unit-tested). */
object TaskListLabels {
    fun status(task: ScheduledTask, last: TaskRun?): String = when {
        last?.status == RunStatus.Running -> "Running in background…"
        !task.enabled -> "Paused"
        last == null -> "Waiting for first run"
        else -> "Last: ${last.status.label}"
    }

    /** "Next in 3 h · Waiting for first run"; [relative] formats an epoch-millis time. */
    fun summary(task: ScheduledTask, last: TaskRun?, relative: (Long) -> String): String {
        val next = task.nextRunAt?.takeIf { task.enabled }?.let { "Next " + relative(it) }
        return listOfNotNull(next, status(task, last)).joinToString(" · ")
    }

    /** The "Notifications & session" badges. */
    fun badges(task: ScheduledTask): List<String> =
        listOfNotNull(task.notify.label, task.destination.label, "Progress notification".takeIf { task.progressNotification })
}
