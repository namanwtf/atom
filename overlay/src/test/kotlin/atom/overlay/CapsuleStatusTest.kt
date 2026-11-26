package atom.overlay

import atom.overlay.run.CapsuleNotice
import atom.overlay.run.CapsuleStatus
import atom.overlay.run.CapsuleStatuses
import atom.ui.feature.RunPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CapsuleStatusTest {
    private fun of(
        phase: RunPhase = RunPhase.Running,
        pending: Boolean = false,
        tool: Boolean = false,
        answering: Boolean = false,
        stopping: Boolean = false,
        notice: CapsuleNotice? = null,
    ) = CapsuleStatuses.of(phase, pending, tool, answering, stopping, notice)

    @Test
    fun aShortPreambleIsNotTheAnswer() {
        assertEquals(false, CapsuleStatuses.answerStarted(""))
        assertEquals(false, CapsuleStatuses.answerStarted("Opening Settings to check the battery."))
        assertEquals(true, CapsuleStatuses.answerStarted("Here is what I found:\n- 82% battery"))
        assertEquals(true, CapsuleStatuses.answerStarted("x".repeat(CapsuleStatuses.ANSWER_MIN_CHARS)))
    }

    @Test
    fun runningStatesFollowWhatTheRunDoes() {
        assertEquals(CapsuleStatus.Reasoning, of())
        assertEquals(CapsuleStatus.Working, of(tool = true))
        assertEquals(CapsuleStatus.GeneratingAnswer, of(answering = true))
        assertEquals(CapsuleStatus.Starting, of(phase = RunPhase.Starting))
    }

    @Test
    fun aScreenActionWaitingForUnlockSaysSo() {
        val waiting = CapsuleStatuses.of(RunPhase.Running, pending = false, toolRunning = true, answering = false, stopping = false, notice = null, waitingForUnlock = true)
        assertEquals(CapsuleStatus.WaitingForUnlock, waiting)
        // Only while a (screen) tool is running; stopping and approvals still win.
        assertEquals(CapsuleStatus.Reasoning, CapsuleStatuses.of(RunPhase.Running, false, false, false, false, null, waitingForUnlock = true))
        assertEquals(CapsuleStatus.Stopping, CapsuleStatuses.of(RunPhase.Running, false, true, false, true, null, waitingForUnlock = true))
    }

    @Test
    fun stoppingAndBlockersWinOverNotices() {
        assertEquals(CapsuleStatus.Stopping, of(phase = RunPhase.Paused, stopping = true, notice = CapsuleNotice.Continuing))
        assertEquals(CapsuleStatus.NeedsApproval, of(pending = true, notice = CapsuleNotice.InputReceived))
        assertEquals(CapsuleStatus.Paused, of(phase = RunPhase.Paused, notice = CapsuleNotice.InputReceived))
        assertEquals(CapsuleStatus.WaitingForControl, of(phase = RunPhase.WaitingForControl))
    }

    @Test
    fun noticesShowWhileTheyLast() {
        assertEquals(CapsuleStatus.Continuing, of(tool = true, notice = CapsuleNotice.Continuing))
        assertEquals(CapsuleStatus.InputReceived, of(notice = CapsuleNotice.InputReceived))
        assertEquals(CapsuleStatus.FinishingUp, of(answering = true, notice = CapsuleNotice.FinishingUp))
        assertEquals(CapsuleStatus.InputUnsupported, of(notice = CapsuleNotice.InputUnsupported))
    }

    @Test
    fun addInputIsRefusedWhileTheAnswerStreamsOrWithoutAHost() {
        assertEquals(CapsuleNotice.FinishingUp, CapsuleStatuses.steerBlocked(answering = true, toolRunning = false, hostAvailable = true))
        assertNull(CapsuleStatuses.steerBlocked(answering = true, toolRunning = true, hostAvailable = true))
        assertNull(CapsuleStatuses.steerBlocked(answering = false, toolRunning = false, hostAvailable = true))
        assertEquals(CapsuleNotice.InputUnsupported, CapsuleStatuses.steerBlocked(answering = false, toolRunning = false, hostAvailable = false))
    }
}
