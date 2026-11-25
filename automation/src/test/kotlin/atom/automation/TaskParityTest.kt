package atom.automation

import atom.automation.schedule.Schedule
import atom.automation.task.RunStatus
import atom.automation.task.RunTimeline
import atom.automation.task.RunTrigger
import atom.automation.task.ScheduledTask
import atom.automation.task.TaskListLabels
import atom.automation.task.TaskNotices
import atom.automation.task.TaskRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskParityTest {
    private val task = ScheduledTask("t", "Digest", "Summarize", Schedule.Daily(9 * 60), nextRunAt = 1_000)

    @Test
    fun newTaskDefaults() {
        assertFalse(task.progressNotification)
        assertFalse(task.quietHours.enabled)
        assertEquals(setOf(Capability.Web, Capability.DeviceRead, Capability.Terminal, Capability.Mcp), task.capabilities)
        assertFalse(Capability.DeviceControl in task.capabilities)
        assertFalse(Capability.AppUi in task.capabilities)
        assertEquals(5, ScheduledTask.AUTO_PAUSE_AFTER)
        assertEquals(5, task.maxMinutes)
    }

    @Test
    fun listStatus() {
        val rel = { at: Long -> "in ${at / 1000} s" }
        assertEquals("Next in 1 s · Waiting for first run", TaskListLabels.summary(task, null, rel))
        val running = TaskRun("r", "t", 0, RunStatus.Running)
        assertEquals("Next in 1 s · Running in background…", TaskListLabels.summary(task, running, rel))
        assertEquals("Paused", TaskListLabels.summary(task.copy(enabled = false), null, rel))
        val done = TaskRun("r", "t", 0, RunStatus.Succeeded, endedAt = 10)
        assertEquals("Last: Delivered", TaskListLabels.status(task, done))
    }

    @Test
    fun runTimelineRows() {
        val manual = TaskRun("r", "t", 0, RunStatus.Succeeded, endedAt = 65_000, conversationId = "c", manual = true)
        assertEquals(RunTrigger.Manual, manual.trigger)
        assertEquals("Manual run · Delivered · 1 min 5 s", RunTimeline.line(manual))
        assertEquals("Tap to view full conversation and follow up", RunTimeline.tapHint(manual))
        val live = TaskRun("r", "t", 0, RunStatus.Running, conversationId = "c")
        assertEquals("Scheduled · Running", RunTimeline.line(live))
        assertEquals("Tap to watch live progress", RunTimeline.tapHint(live))
        val late = TaskRun("r", "t", 0, RunStatus.Unchanged, endedAt = 3_000, catchUp = true)
        assertEquals("Catch-up · No change · 3 s", RunTimeline.line(late))
        assertNull(RunTimeline.tapHint(late))
        val skipped = TaskRun("r", "t", 0, RunStatus.Skipped, endedAt = 0)
        assertEquals("Scheduled · Skipped", RunTimeline.line(skipped))
        assertEquals("Stopped", RunStatus.Cancelled.label)
        assertEquals("Timed out", RunStatus.TimedOut.label)
    }

    @Test
    fun notificationCopy() {
        assertEquals("Task failed: Digest", TaskNotices.failedTitle("Digest"))
        assertEquals("Task paused: Digest", TaskNotices.pausedTitle("Digest"))
        assertEquals("Ready to run: Digest", TaskNotices.readyTitle("Digest"))
        assertEquals("Scheduled task completed. Tap to view full conversation.", TaskNotices.resultBody(" "))
        assertEquals("Execution failed. Tap to inspect details.", TaskNotices.failedBody(""))
        assertTrue(TaskNotices.resultBody("Done").startsWith("Done"))
    }
}
