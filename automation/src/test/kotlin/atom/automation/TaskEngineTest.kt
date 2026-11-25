package atom.automation

import atom.automation.schedule.MissedRunPolicy
import atom.automation.schedule.Schedule
import atom.automation.task.NotifyMode
import atom.automation.task.RunStatus
import atom.automation.task.ScheduledTask
import atom.automation.task.TaskEngine
import atom.automation.task.TaskPorts
import atom.automation.task.TaskRun
import atom.ui.feature.HostRun
import atom.ui.feature.RunPhase
import atom.ui.feature.RunSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TaskEngineTest {
    private class Fake(var now: Instant) : TaskPorts {
        var locked = false
        val runs = mutableListOf<HostRun>()
        val stopped = mutableListOf<String>()
        val results = mutableListOf<String>()
        val alerts = mutableListOf<String>()
        val reminders = mutableListOf<String>()
        var wakeups = 0
        override fun now() = now
        override fun zone(): ZoneId = ZoneOffset.UTC
        override fun isLocked() = locked
        override fun backgroundDisplay() = false
        override fun currentRuns() = runs.toList()
        override fun stop(conversationId: String) { stopped += conversationId }
        override fun notifyResult(task: ScheduledTask, run: TaskRun, text: String) { results += text }
        override fun notifyAlert(task: ScheduledTask, message: String) { alerts += message }
        override fun notifyReadyToRun(task: ScheduledTask) { reminders += task.id }
        override fun wakeupsChanged() { wakeups++ }
    }

    private val start = Instant.parse("2026-11-25T08:00:00Z")
    private val requests = mutableListOf<RunRequest>()
    private var nextConv = 0

    private fun setup(scope: TestScope): Triple<AutomationState, TaskEngine, Fake> {
        val state = AutomationState()
        val ports = Fake(start)
        val launcher = RunLauncher { r -> requests += r; "c${nextConv++}" }
        return Triple(state, TaskEngine(state, { launcher }, ports, scope.backgroundScope), ports)
    }

    private fun task(id: String = "t", notify: NotifyMode = NotifyMode.EveryRun, caps: Set<Capability> = Capability.DEFAULT, schedule: Schedule = Schedule.Daily(9 * 60)) =
        ScheduledTask(id, "Task $id", "Do it", schedule, notify = notify, capabilities = caps)

    private fun finish(engine: TaskEngine, ports: Fake, conv: String, phase: RunPhase, text: String = "", error: String? = null) {
        ports.runs.removeAll { it.conversationId == conv }
        ports.runs += HostRun(conv, "t", RunSource.Schedule, RunPhase.Running, "", emptyList())
        engine.onRuns(ports.runs)
        ports.runs.removeAll { it.conversationId == conv }
        ports.runs += HostRun(conv, "t", RunSource.Schedule, phase, text, emptyList(), error = error)
        engine.onRuns(ports.runs)
    }

    @Test fun dueTaskRunsAndRecordsResult() = runTest {
        val (state, engine, ports) = setup(this)
        engine.save(task())
        assertEquals(Instant.parse("2026-11-25T09:00:00Z").toEpochMilli(), state.task("t")!!.nextRunAt)
        ports.now = Instant.parse("2026-11-25T09:00:00Z")
        engine.onWake()
        assertEquals(RunSource.Schedule, requests.single().source)
        assertEquals(Capability.DEFAULT, requests.single().capabilities)
        assertEquals(Instant.parse("2026-11-26T09:00:00Z").toEpochMilli(), state.task("t")!!.nextRunAt)
        finish(engine, ports, "c0", RunPhase.Done, "Weather is fine")
        assertEquals(RunStatus.Succeeded, state.runsOf("t").first().status)
        assertEquals("Weather is fine", state.task("t")!!.memo)
        assertEquals(listOf("Weather is fine"), ports.results)
    }

    @Test fun overlappingRunIsSkipped() = runTest {
        val (state, engine, _) = setup(this)
        engine.save(task())
        assertNull(engine.runNow("t"))
        assertEquals("Already running", engine.runNow("t"))
        assertEquals(1, requests.size)
        assertEquals(RunStatus.Skipped, state.runsOf("t").first().status)
    }

    @Test fun autoPausesAfterRepeatedFailures() = runTest {
        val (state, engine, ports) = setup(this)
        engine.save(task())
        repeat(3) { i ->
            engine.runNow("t")
            finish(engine, ports, "c$i", RunPhase.Failed, error = "boom")
        }
        val t = state.task("t")!!
        assertFalse(t.enabled)
        assertNotNull(t.pausedReason)
        assertTrue(ports.alerts.last().contains("Paused"))
        assertNull(engine.earliestWakeup())
    }

    @Test fun successResetsFailureCount() = runTest {
        val (state, engine, ports) = setup(this)
        engine.save(task())
        engine.runNow("t"); finish(engine, ports, "c0", RunPhase.Failed, error = "x")
        engine.runNow("t"); finish(engine, ports, "c1", RunPhase.Done, "ok")
        assertEquals(0, state.task("t")!!.consecutiveFailures)
    }

    @Test fun onlyOnChangeSuppressesUnchangedResults() = runTest {
        val (state, engine, ports) = setup(this)
        engine.save(task(notify = NotifyMode.OnChange).copy(memo = "Price 10"))
        engine.runNow("t")
        assertTrue(requests.last().prompt.contains("Price 10"))
        assertTrue(requests.last().prompt.contains(TaskEngine.NO_CHANGE))
        finish(engine, ports, "c0", RunPhase.Done, "NO_CHANGE")
        assertEquals(RunStatus.Unchanged, state.runsOf("t").first().status)
        assertTrue(ports.results.isEmpty())
        engine.runNow("t")
        finish(engine, ports, "c1", RunPhase.Done, "Price 12")
        assertEquals(listOf("Price 12"), ports.results)
        assertEquals("Price 12", state.task("t")!!.memo)
    }

    @Test fun maxDurationStopsTheRun() = runTest {
        val (state, engine, ports) = setup(this)
        engine.save(task().copy(maxMinutes = 2))
        engine.runNow("t")
        runCurrent()
        advanceTimeBy(2 * 60_000L + 1)
        assertEquals(listOf("c0"), ports.stopped)
        finish(engine, ports, "c0", RunPhase.Cancelled)
        assertEquals(RunStatus.TimedOut, state.runsOf("t").first().status)
        assertEquals(1, state.task("t")!!.consecutiveFailures)
    }

    @Test fun uiTaskOnLockedPhoneSendsReminder() = runTest {
        val (state, engine, ports) = setup(this)
        engine.save(task(caps = setOf(Capability.AppUi)))
        ports.locked = true
        ports.now = Instant.parse("2026-11-25T09:00:00Z")
        engine.onWake()
        assertTrue(requests.isEmpty())
        assertEquals(listOf("t"), ports.reminders)
        assertEquals(RunStatus.WaitingUnlock, state.runsOf("t").first().status)
        assertNull(engine.runNow("t"))
        assertEquals(1, requests.size)
    }

    @Test fun missedRunSkippedOrCaughtUp() = runTest {
        val (state, engine, ports) = setup(this)
        engine.save(task("skip").copy(missedPolicy = MissedRunPolicy.Skip))
        engine.save(task("catch").copy(missedPolicy = MissedRunPolicy.CatchUp))
        ports.now = Instant.parse("2026-11-25T10:30:00Z")
        engine.onWake()
        assertEquals(RunStatus.Skipped, state.runsOf("skip").first().status)
        assertEquals(RunStatus.Running, state.runsOf("catch").first().status)
        assertEquals(1, requests.size)
        assertEquals(Instant.parse("2026-11-26T09:00:00Z").toEpochMilli(), state.task("skip")!!.nextRunAt)
    }

    @Test fun oneTimeTaskTurnsOffAfterFiring() = runTest {
        val (state, engine, ports) = setup(this)
        val at = Instant.parse("2026-11-25T08:30:00Z")
        engine.save(task(schedule = Schedule.Once(at.toEpochMilli())))
        ports.now = at
        engine.onWake()
        assertEquals(1, requests.size)
        assertFalse(state.task("t")!!.enabled)
        assertNull(state.task("t")!!.nextRunAt)
    }

    @Test fun interruptedRunsAreRecovered() = runTest {
        val (state, engine, _) = setup(this)
        state.putRun(TaskRun("r", "t", 0, RunStatus.Running))
        engine.recoverInterrupted()
        assertEquals(RunStatus.Interrupted, state.value.taskRuns.single().status)
    }

    @Test fun dedicatedConversationIsReused() = runTest {
        val (state, engine, ports) = setup(this)
        engine.save(task().copy(destination = atom.automation.task.Destination.Conversation))
        engine.runNow("t")
        finish(engine, ports, "c0", RunPhase.Done, "a")
        assertEquals("c0", state.task("t")!!.conversationId)
        engine.runNow("t")
        assertEquals("c0", requests.last().conversationId)
    }

    @Test fun snapshotRoundTrips() {
        val snap = Snapshot(
            tasks = listOf(
                task("a", schedule = Schedule.Weekly(setOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.FRIDAY), 600)),
                task("b", schedule = Schedule.CronSchedule("0 9 * * 1-5")).copy(quietHours = atom.automation.schedule.QuietHours(true), missedPolicy = MissedRunPolicy.Skip),
                task("c", schedule = Schedule.Interval(30, 5)),
            ),
            taskRuns = listOf(TaskRun("r", "a", 1, RunStatus.Unchanged, summary = "s")),
        )
        val json = atom.automation.rule.RuleJson.json
        assertEquals(snap, json.decodeFromString(Snapshot.serializer(), json.encodeToString(Snapshot.serializer(), snap)))
    }
}
