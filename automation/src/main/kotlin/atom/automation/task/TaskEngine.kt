package atom.automation.task

import atom.automation.AutomationState
import atom.automation.RunLauncher
import atom.automation.RunRequest
import atom.automation.schedule.Planner
import atom.automation.schedule.Schedule
import atom.automation.schedule.ScheduleText
import atom.core.llm.ReasoningEffort
import atom.ui.feature.HostRun
import atom.ui.feature.RunPhase
import atom.ui.feature.RunSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Side effects the task engine needs; Android implements them, tests fake them. */
interface TaskPorts {
    fun now(): Instant
    fun zone(): ZoneId
    fun isLocked(): Boolean
    fun backgroundDisplay(): Boolean
    fun currentRuns(): List<HostRun>
    fun stop(conversationId: String)
    fun notifyResult(task: ScheduledTask, run: TaskRun, text: String)
    fun notifyAlert(task: ScheduledTask, message: String)
    /** The task paused itself after repeated failures. */
    fun notifyPaused(task: ScheduledTask, message: String) = notifyAlert(task, message)
    fun notifyReadyToRun(task: ScheduledTask)
    /** Wake-up times changed; re-arm the alarm. */
    fun wakeupsChanged()
}

/**
 * Runs scheduled tasks: decides what is due, applies missed-run and overlap rules, starts runs through the
 * launcher, watches them finish, and keeps memo, failure count and next-run time up to date.
 */
class TaskEngine(
    private val state: AutomationState,
    private val launcher: () -> RunLauncher,
    private val ports: TaskPorts,
    private val scope: CoroutineScope,
) {
    private class Active(val runId: String, val conversationId: String, val previous: HostRun?, val timeout: Job?) {
        var sawActive = false
        var timedOut = false
    }

    private val active = mutableMapOf<String, Active>()

    fun isRunning(taskId: String): Boolean = synchronized(active) { taskId in active }

    /** After a process start any "running" record belongs to a dead process. */
    fun recoverInterrupted() {
        val now = ports.now().toEpochMilli()
        state.value.taskRuns.filter { it.status == RunStatus.Running }.forEach {
            state.putRun(it.copy(status = RunStatus.Interrupted, endedAt = now, summary = it.summary.ifEmpty { "The app was closed during this run" }))
        }
    }

    fun earliestWakeup(): Long? = state.value.tasks.filter { it.enabled }.mapNotNull { it.nextRunAt }.minOrNull()

    /** Recomputes future run times (boot, update, clock or zone change). Past-due slots are left for [onWake]. */
    fun replan() {
        val now = ports.now()
        val zone = ports.zone()
        state.edit { s ->
            s.copy(tasks = s.tasks.map { t ->
                when {
                    !t.enabled -> t
                    t.nextRunAt != null && t.nextRunAt <= now.toEpochMilli() -> t
                    else -> t.copy(nextRunAt = Planner.next(t.schedule, t.quietHours, now, zone)?.toEpochMilli())
                }
            })
        }
        ports.wakeupsChanged()
    }

    fun save(task: ScheduledTask) {
        val now = ports.now()
        val next = if (task.enabled) Planner.next(task.schedule, task.quietHours, now, ports.zone())?.toEpochMilli() else null
        state.upsertTask(task.copy(nextRunAt = next, createdAt = task.createdAt.takeIf { it > 0 } ?: now.toEpochMilli()))
        ports.wakeupsChanged()
    }

    fun setEnabled(taskId: String, enabled: Boolean) {
        val task = state.task(taskId) ?: return
        save(task.copy(enabled = enabled, consecutiveFailures = if (enabled) 0 else task.consecutiveFailures, pausedReason = if (enabled) null else task.pausedReason))
    }

    fun delete(taskId: String) {
        stop(taskId)
        state.deleteTask(taskId)
        ports.wakeupsChanged()
    }

    fun clearMemo(taskId: String) = state.updateTask(taskId) { it.copy(memo = null) }

    /** Alarm fired (or the process woke for another reason): run everything that is due. */
    fun onWake() {
        val now = ports.now()
        val zone = ports.zone()
        val due = state.value.tasks.filter { it.enabled && it.nextRunAt != null && it.nextRunAt <= now.toEpochMilli() + EARLY_SLACK_MS }
        for (task in due) {
            val dueAt = Instant.ofEpochMilli(task.nextRunAt!!)
            when (Planner.missed(task.missedPolicy, dueAt, now)) {
                Planner.Decision.RunNow -> trigger(task, manual = false, catchUp = now.toEpochMilli() - dueAt.toEpochMilli() > CATCH_UP_AFTER_MS)
                Planner.Decision.Skip -> record(task, RunStatus.Skipped, "Missed the ${ScheduleText.relative(dueAt, now, zone)} slot while the phone was off or asleep")
            }
            val fresh = state.task(task.id) ?: continue
            val next = if (fresh.schedule is Schedule.Once) null else Planner.next(fresh.schedule, fresh.quietHours, maxOf(now, dueAt), zone)
            state.updateTask(task.id) { it.copy(nextRunAt = next?.toEpochMilli(), enabled = it.enabled && (next != null || it.schedule !is Schedule.Once)) }
        }
        ports.wakeupsChanged()
    }

    fun runNow(taskId: String): String? {
        val task = state.task(taskId) ?: return "No such task"
        return trigger(task, manual = true)
    }

    fun stop(taskId: String) {
        val a = synchronized(active) { active[taskId] } ?: return
        ports.stop(a.conversationId)
    }

    /** Starts [task] now unless a rule stops it; returns why it did not start, or null. */
    private fun trigger(task: ScheduledTask, manual: Boolean, catchUp: Boolean = false): String? {
        if (isRunning(task.id)) {
            record(task, RunStatus.Skipped, "Skipped: the previous run was still going", manual)
            return "Already running"
        }
        if (task.usesPhoneUi && !manual && ports.isLocked() && !ports.backgroundDisplay()) {
            record(task, RunStatus.WaitingUnlock, "Needs the screen; sent a reminder to run it when you unlock", manual)
            ports.notifyReadyToRun(task)
            return "Waiting for unlock"
        }
        val conversation = if (task.destination == Destination.Conversation) task.conversationId else null
        val previous = conversation?.let { c -> ports.currentRuns().firstOrNull { it.conversationId == c } }
        val request = RunRequest(
            prompt = buildPrompt(task, ports.now(), ports.zone()),
            conversationId = conversation,
            source = RunSource.Schedule,
            capabilities = task.capabilities,
            modelId = task.modelId,
            reasoning = task.reasoning?.let { r -> ReasoningEffort.entries.firstOrNull { it.name == r } },
            title = task.name,
            showProgress = task.progressNotification,
        )
        val convId = runCatching { launcher().start(request) }.getOrElse {
            record(task, RunStatus.Failed, "Could not start: ${it.message ?: it::class.simpleName}", manual)
            registerFailure(task.id)
            return "Could not start"
        }
        if (convId == null) {
            record(task, RunStatus.Skipped, "Skipped: its conversation is busy", manual)
            return "Conversation busy"
        }
        val run = TaskRun(UUID.randomUUID().toString(), task.id, ports.now().toEpochMilli(), RunStatus.Running, conversationId = convId, manual = manual, catchUp = catchUp)
        state.putRun(run)
        if (task.destination == Destination.Conversation && task.conversationId != convId) {
            state.updateTask(task.id) { it.copy(conversationId = convId) }
        }
        val timeout = scope.launch {
            delay(task.maxMinutes.coerceAtLeast(1) * 60_000L)
            val a = synchronized(active) { active[task.id] }
            if (a != null && a.runId == run.id) {
                a.timedOut = true
                ports.stop(convId)
            }
        }
        synchronized(active) { active[task.id] = Active(run.id, convId, previous, timeout) }
        onRuns(ports.currentRuns())
        return null
    }

    /** Feed every emission of the host's run list. */
    fun onRuns(runs: List<HostRun>) {
        val finished = mutableListOf<Pair<String, HostRun>>()
        synchronized(active) {
            for ((taskId, a) in active) {
                val run = runs.firstOrNull { it.conversationId == a.conversationId } ?: continue
                if (run.phase.active) {
                    a.sawActive = true
                } else if (a.sawActive || run !== a.previous) {
                    finished += taskId to run
                }
            }
        }
        finished.forEach { (taskId, run) -> finish(taskId, run) }
    }

    private fun finish(taskId: String, host: HostRun) {
        val a = synchronized(active) { active.remove(taskId) } ?: return
        a.timeout?.cancel()
        val task = state.task(taskId) ?: return
        val run = state.value.taskRuns.firstOrNull { it.id == a.runId } ?: return
        val text = host.text.trim()
        val status = when (host.phase) {
            RunPhase.Done -> when {
                a.timedOut -> RunStatus.TimedOut
                isUnchanged(text, task.memo) -> RunStatus.Unchanged
                else -> RunStatus.Succeeded
            }
            RunPhase.Cancelled -> if (a.timedOut) RunStatus.TimedOut else RunStatus.Cancelled
            else -> if (a.timedOut) RunStatus.TimedOut else RunStatus.Failed
        }
        val summary = when (status) {
            RunStatus.Unchanged -> "No change since the last run"
            RunStatus.TimedOut -> "Stopped after ${task.maxMinutes} min (max duration)"
            RunStatus.Failed -> host.error ?: text.ifEmpty { "The run failed" }
            RunStatus.Cancelled -> "Stopped"
            else -> text.ifEmpty { "Finished with no reply" }
        }.take(SUMMARY_CHARS)
        val done = run.copy(status = status, endedAt = ports.now().toEpochMilli(), summary = summary)
        state.putRun(done)
        when {
            status == RunStatus.Succeeded -> state.updateTask(taskId) { it.copy(memo = text.take(MEMO_CHARS), consecutiveFailures = 0) }
            status == RunStatus.Unchanged -> state.updateTask(taskId) { it.copy(consecutiveFailures = 0) }
            status.failure -> registerFailure(taskId)
        }
        val fresh = state.task(taskId) ?: return
        val paused = !fresh.enabled && fresh.pausedReason != null && status.failure
        when {
            paused -> Unit
            status == RunStatus.Succeeded && fresh.notify != NotifyMode.Silent -> ports.notifyResult(fresh, done, text)
            status == RunStatus.Unchanged && fresh.notify == NotifyMode.EveryRun -> ports.notifyResult(fresh, done, summary)
            status.failure && fresh.notify != NotifyMode.Silent -> ports.notifyAlert(fresh, TaskNotices.failedBody(summary))
        }
    }

    private fun registerFailure(taskId: String) {
        val task = state.task(taskId) ?: return
        val failures = task.consecutiveFailures + 1
        if (failures >= ScheduledTask.AUTO_PAUSE_AFTER && task.enabled) {
            val reason = "Paused after $failures failed runs in a row"
            state.updateTask(taskId) { it.copy(consecutiveFailures = failures, enabled = false, pausedReason = reason) }
            ports.notifyPaused(task, "$reason. Open it to check and switch it back on.")
            ports.wakeupsChanged()
        } else {
            state.updateTask(taskId) { it.copy(consecutiveFailures = failures) }
        }
    }

    private fun record(task: ScheduledTask, status: RunStatus, summary: String, manual: Boolean = false) {
        val now = ports.now().toEpochMilli()
        state.putRun(TaskRun(UUID.randomUUID().toString(), task.id, now, status, endedAt = now, summary = summary, manual = manual))
    }

    companion object {
        const val NO_CHANGE = "NO_CHANGE"
        const val EARLY_SLACK_MS = 1_000L

        /** A scheduled run that starts this late counts as a catch-up in the run timeline. */
        const val CATCH_UP_AFTER_MS = 5 * 60_000L
        const val MEMO_CHARS = 4_000
        const val SUMMARY_CHARS = 600

        fun buildPrompt(task: ScheduledTask, now: Instant, zone: ZoneId): String = buildString {
            append(task.prompt.trim())
            append("\n\n(Scheduled task \"").append(task.name).append("\", ").append(ScheduleText.relative(now, now, zone)).append(".)")
            val memo = task.memo?.takeIf { it.isNotBlank() }
            if (memo != null) {
                append("\n\nPrevious run's result, for comparison:\n").append(memo.take(MEMO_CHARS))
            }
            if (task.notify == NotifyMode.OnChange && memo != null) {
                append("\n\nIf nothing meaningful changed since the previous result, reply with exactly $NO_CHANGE.")
            }
        }

        fun isUnchanged(text: String, memo: String?): Boolean {
            if (text.trim().trim('*', '`', '.').equals(NO_CHANGE, ignoreCase = true)) return true
            if (text.trim().startsWith(NO_CHANGE)) return true
            return memo != null && normalize(text) == normalize(memo) && text.isNotBlank()
        }

        private fun normalize(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim()
    }
}
