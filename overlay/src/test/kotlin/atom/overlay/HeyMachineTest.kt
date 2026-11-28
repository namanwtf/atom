package atom.overlay

import atom.overlay.hey.HeyEffect
import atom.overlay.hey.HeyEvent
import atom.overlay.hey.HeyGlow
import atom.overlay.hey.HeyMachine
import atom.overlay.hey.HeyPanel
import atom.overlay.hey.HeyStage
import atom.overlay.run.CapsuleStatus
import atom.ui.feature.HostApproval
import atom.ui.feature.HostRun
import atom.ui.feature.HostStep
import atom.ui.feature.RunPhase
import atom.ui.feature.RunSource
import atom.ui.feature.StepState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HeyMachineTest {
    private fun run(
        phase: RunPhase,
        id: String = "c1",
        text: String = "",
        steps: List<HostStep> = emptyList(),
        pending: HostApproval? = null,
        error: String? = null,
    ) = HostRun(id, "t", RunSource.Voice, phase, text, steps, pending, error = error)

    /** Applies [events] from [start], returning every step. */
    private fun play(start: HeyStage, vararg events: HeyEvent) = events.runningFold(atom.overlay.hey.HeyStep(start)) { acc, e -> HeyMachine.reduce(acc.stage, e) }.drop(1)

    @Test
    fun wakeShowsListeningWithAGuardTimer() {
        val step = HeyMachine.reduce(HeyStage.Hidden, HeyEvent.Wake)
        assertEquals(HeyStage.Listening(""), step.stage)
        assertEquals(listOf<HeyEffect>(HeyEffect.Timer(HeyMachine.LISTEN_GUARD_MS)), step.effects)
        assertEquals(HeyGlow.Live, HeyMachine.glow(step.stage))
        assertEquals(HeyPanel.Modal, HeyMachine.panel(step.stage))
    }

    @Test
    fun fullHappyPath_listening_gotIt_working_done() {
        val steps = play(
            HeyStage.Hidden,
            HeyEvent.Wake,
            HeyEvent.Partial("set a timer"),
            HeyEvent.Partial("set a timer for ten minutes"),
            HeyEvent.Final("Set a timer for ten minutes"),
            HeyEvent.Started("c1"),
            HeyEvent.Progress(run(RunPhase.Running, steps = listOf(HostStep("s1", "set_timer", "Setting a timer", StepState.Running)))),
            HeyEvent.Finished(run(RunPhase.Done, text = "**Done.** Your timer runs for 10 minutes.")),
            HeyEvent.TimerElapsed,
        )
        assertEquals(HeyStage.Listening("set a timer for ten minutes"), steps[2].stage)
        val heard = steps[3]
        assertEquals(HeyStage.Heard("Set a timer for ten minutes"), heard.stage)
        assertEquals(listOf<HeyEffect>(HeyEffect.ClearTimer), heard.effects)
        assertEquals(HeyGlow.Pulse, HeyMachine.glow(heard.stage))
        val working = assertIs<HeyStage.Working>(steps[5].stage)
        assertEquals(CapsuleStatus.Working, working.status)
        assertEquals("Setting a timer", working.step)
        assertEquals(HeyGlow.Calm, HeyMachine.glow(working))
        assertEquals(HeyPanel.Inline, HeyMachine.panel(working))
        val done = assertIs<HeyStage.Done>(steps[6].stage)
        assertTrue(done.ok)
        assertEquals("Done. Your timer runs for 10 minutes.", done.result)
        assertEquals(listOf<HeyEffect>(HeyEffect.Timer(HeyMachine.DONE_MS)), steps[6].effects)
        assertEquals(HeyStage.Hidden, steps[7].stage)
    }

    @Test
    fun timeoutOrSilenceSaysDidntCatchThatThenFades() {
        val missed = HeyMachine.reduce(HeyStage.Listening("hm"), HeyEvent.NoSpeech)
        assertEquals(HeyStage.Missed, missed.stage)
        assertEquals(listOf<HeyEffect>(HeyEffect.Timer(HeyMachine.MISSED_MS)), missed.effects)
        assertEquals(HeyPanel.Inline, HeyMachine.panel(missed.stage))
        assertEquals(HeyStage.Hidden, HeyMachine.reduce(missed.stage, HeyEvent.TimerElapsed).stage)
    }

    @Test
    fun aCaptureThatNeverReportsBackEndsAtTheGuard() {
        val step = HeyMachine.reduce(HeyStage.Listening(), HeyEvent.TimerElapsed)
        assertEquals(HeyStage.Missed, step.stage)
        assertTrue(HeyEffect.StopCapture in step.effects)
    }

    @Test
    fun cancelWhileListeningStopsTheCapture() {
        val step = HeyMachine.reduce(HeyStage.Listening("open the"), HeyEvent.Cancel)
        assertEquals(HeyStage.Hidden, step.stage)
        assertEquals(listOf(HeyEffect.StopCapture, HeyEffect.ClearTimer), step.effects)
    }

    @Test
    fun cancelWhileWorkingStopsTheRun() {
        val step = HeyMachine.reduce(HeyStage.Working("x", "c1"), HeyEvent.Cancel)
        assertEquals(HeyStage.Hidden, step.stage)
        assertEquals(listOf(HeyEffect.StopRun("c1"), HeyEffect.ClearTimer), step.effects)
    }

    @Test
    fun cancelOnAResultOnlyCloses() {
        val step = HeyMachine.reduce(HeyStage.Done("x", "c1", true, "ok"), HeyEvent.Cancel)
        assertEquals(HeyStage.Hidden, step.stage)
        assertEquals(listOf<HeyEffect>(HeyEffect.ClearTimer), step.effects)
        assertEquals(HeyStage.Hidden, HeyMachine.reduce(HeyStage.Hidden, HeyEvent.Cancel).stage)
    }

    @Test
    fun tapOnTheResultOpensTheConversation() {
        val step = HeyMachine.reduce(HeyStage.Done("x", "c1", true, "ok"), HeyEvent.Open)
        assertEquals(HeyStage.Hidden, step.stage)
        assertTrue(HeyEffect.OpenConversation("c1") in step.effects)
        // While listening a tap on the panel does nothing.
        assertEquals(HeyStage.Listening("a"), HeyMachine.reduce(HeyStage.Listening("a"), HeyEvent.Open).stage)
    }

    @Test
    fun aRunThatCannotStartHereGoesToTheApp() {
        val step = HeyMachine.reduce(HeyStage.Heard("call mom"), HeyEvent.NotStarted)
        assertEquals(HeyStage.Opening("call mom"), step.stage)
        assertEquals(HeyStage.Hidden, HeyMachine.reduce(step.stage, HeyEvent.TimerElapsed).stage)
    }

    @Test
    fun approvalsShowInThePanel() {
        val pending = HostApproval(7, "Send a message", Long.MAX_VALUE)
        val step = HeyMachine.reduce(HeyStage.Working("x", "c1"), HeyEvent.Progress(run(RunPhase.WaitingForApproval, pending = pending)))
        val working = assertIs<HeyStage.Working>(step.stage)
        assertEquals(pending, working.pending)
        assertEquals(CapsuleStatus.NeedsApproval, working.status)
    }

    @Test
    fun usingTheScreenHandsOverAndTheResultStillComesBack() {
        val handed = HeyMachine.reduce(HeyStage.Working("x", "c1"), HeyEvent.ScreenTaken("c1")).stage
        assertEquals(HeyGlow.None, HeyMachine.glow(handed))
        assertEquals(HeyPanel.None, HeyMachine.panel(handed))
        assertIs<HeyStage.Done>(HeyMachine.reduce(handed, HeyEvent.Finished(run(RunPhase.Done, text = "Opened"))).stage)
        // An earlier result goes away when another run takes the screen.
        assertEquals(HeyStage.Hidden, HeyMachine.reduce(HeyStage.Done("x", "c0", true, "ok"), HeyEvent.ScreenTaken("c9")).stage)
        // While the user speaks and a run uses the screen, only the panel takes touches.
        assertEquals(HeyPanel.Inline, HeyMachine.panel(HeyStage.Listening(), screenBusy = true))
    }

    @Test
    fun otherRunsAreIgnored() {
        val working = HeyStage.Working("x", "c1")
        assertEquals(working, HeyMachine.reduce(working, HeyEvent.Finished(run(RunPhase.Done, id = "c2"))).stage)
        assertEquals(working, HeyMachine.reduce(working, HeyEvent.Progress(run(RunPhase.Running, id = "c2"))).stage)
    }

    @Test
    fun failedAndStoppedRuns() {
        val failed = assertIs<HeyStage.Done>(HeyMachine.reduce(HeyStage.Working("x", "c1"), HeyEvent.Finished(run(RunPhase.Failed, error = "No model"))).stage)
        assertEquals(false, failed.ok)
        assertEquals("No model", failed.result)
        assertEquals(HeyStage.Hidden, HeyMachine.reduce(HeyStage.Working("x", "c1"), HeyEvent.Finished(run(RunPhase.Cancelled))).stage)
        // A run that already ended when its first update arrives finishes at once.
        assertIs<HeyStage.Done>(HeyMachine.reduce(HeyStage.Working("x", "c1"), HeyEvent.Progress(run(RunPhase.Done, text = "Hi"))).stage)
    }

    @Test
    fun theServiceStoppingClosesTheOverlay() {
        assertEquals(HeyStage.Hidden, HeyMachine.reduce(HeyStage.Listening("a"), HeyEvent.CaptureStopped).stage)
    }

    @Test
    fun lateEventsDoNotReopen() {
        assertEquals(HeyStage.Hidden, HeyMachine.reduce(HeyStage.Hidden, HeyEvent.Partial("late")).stage)
        assertEquals(HeyStage.Hidden, HeyMachine.reduce(HeyStage.Hidden, HeyEvent.Final("late")).stage)
        assertEquals(HeyStage.Hidden, HeyMachine.reduce(HeyStage.Hidden, HeyEvent.Started("c1")).stage)
        assertEquals(HeyStage.Hidden, HeyMachine.reduce(HeyStage.Hidden, HeyEvent.NoSpeech).stage)
    }

    @Test
    fun longTextIsShortened() {
        val long = (1..80).joinToString(" ") { "word$it" }
        val short = HeyMachine.shorten(long)
        assertTrue(short.length <= HeyMachine.RESULT_CHARS + 1)
        assertTrue(short.endsWith("…"))
        val tail = HeyMachine.tail(long)
        assertTrue(tail.startsWith("…"))
        assertTrue(tail.endsWith("word80"))
        assertEquals("short", HeyMachine.tail("  short "))
    }
}
