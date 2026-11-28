package atom.overlay.hey

import atom.overlay.run.CapsuleStatus
import atom.overlay.run.CapsuleStatuses
import atom.overlay.ui.Markdown
import atom.ui.feature.HostApproval
import atom.ui.feature.HostRun
import atom.ui.feature.RunPhase
import atom.ui.feature.StepState

/** What the "Hey ATOM" overlay shows. Pure data. */
internal sealed interface HeyStage {
    data object Hidden : HeyStage

    /** Capturing the command. [words] is the live transcript; empty until the recognizer hears something. */
    data class Listening(val words: String = "") : HeyStage

    /** The final words arrived ("Got it"); the run is being started. */
    data class Heard(val words: String) : HeyStage

    /**
     * ATOM runs [conversationId] where the user is. [status] and [step] are the run's one-line status; [pending] is an
     * approval the run waits for. [handedOff]: the run uses the screen now, so the overlay steps aside and the run
     * capsule at the top carries the status, approvals and Stop until the run ends.
     */
    data class Working(
        val words: String,
        val conversationId: String,
        val status: CapsuleStatus = CapsuleStatus.Starting,
        val step: String? = null,
        val pending: HostApproval? = null,
        val handedOff: Boolean = false,
    ) : HeyStage

    /** The command went to the ATOM app instead (ATOM is in front, or the run could not start here). */
    data class Opening(val words: String) : HeyStage

    /** The run ended. [result] is its final message (plain text, shortened), or its error when [ok] is false. */
    data class Done(val words: String, val conversationId: String, val ok: Boolean, val result: String) : HeyStage

    /** Nothing usable was heard. */
    data object Missed : HeyStage
}

internal sealed interface HeyEvent {
    data object Wake : HeyEvent
    data class Partial(val text: String) : HeyEvent
    data class Final(val text: String) : HeyEvent

    /** Silence, the capture's timeout or a recognizer error. */
    data object NoSpeech : HeyEvent

    /** The service stopped the capture ("Hey ATOM" turned off). */
    data object CaptureStopped : HeyEvent
    data class Started(val conversationId: String) : HeyEvent

    /** The run could not start in place; the command goes to the ATOM app. */
    data object NotStarted : HeyEvent
    data class Progress(val run: HostRun) : HeyEvent
    data class Finished(val run: HostRun) : HeyEvent

    /**
     * A run (this one or another) started using the screen: the overlay must not cover what the agent reads and taps.
     */
    data class ScreenTaken(val conversationId: String) : HeyEvent

    /** The X, Back, or a tap outside the panel. */
    data object Cancel : HeyEvent

    /** A tap on the panel. */
    data object Open : HeyEvent
    data object TimerElapsed : HeyEvent
}

internal sealed interface HeyEffect {
    /** Stop the speech capture without a result. */
    data object StopCapture : HeyEffect

    /** Stop the run, the same way the capsule's and the notification's Stop do. */
    data class StopRun(val conversationId: String) : HeyEffect

    /** Open ATOM on [conversationId]. */
    data class OpenConversation(val conversationId: String) : HeyEffect

    /** (Re)start the one timer of the overlay. */
    data class Timer(val ms: Long) : HeyEffect
    data object ClearTimer : HeyEffect
}

internal data class HeyStep(val stage: HeyStage, val effects: List<HeyEffect> = emptyList())

/** How the edge light looks for a stage. */
internal enum class HeyGlow {
    /** Shimmers and follows the voice (only while listening, at most a few seconds). */
    Live,

    /** One bright pulse, then [Calm]. */
    Pulse,

    /** A thin, still line: drawn once, no frames while it stays. */
    Calm,
    None,
}

/** How the panel window takes input. */
internal enum class HeyPanel {
    /** Full screen: a tap outside the panel or Back cancels. Only while listening. */
    Modal,

    /** Only the panel itself is touchable; the app below keeps focus and every other touch. */
    Inline,
    None,
}

/**
 * The overlay's state machine: listening → got it → working → done, or didn't catch that, with cancel from any
 * stage. Pure, so it is unit-tested; [HeyAtomOverlay] applies the effects.
 */
internal object HeyMachine {
    /** Longer than the capture's own 10 s limit: only a capture that never reports back ends here. */
    const val LISTEN_GUARD_MS = 14_000L
    const val OPENING_MS = 1_800L
    const val DONE_MS = 6_000L
    const val MISSED_MS = 3_500L
    const val RESULT_CHARS = 180

    fun reduce(stage: HeyStage, event: HeyEvent): HeyStep = when (event) {
        HeyEvent.Wake -> HeyStep(HeyStage.Listening(), listOf(HeyEffect.Timer(LISTEN_GUARD_MS)))
        is HeyEvent.Partial -> if (stage is HeyStage.Listening && event.text.isNotBlank()) {
            HeyStep(HeyStage.Listening(event.text.trim()))
        } else {
            HeyStep(stage)
        }
        is HeyEvent.Final -> if (stage is HeyStage.Listening && event.text.isNotBlank()) {
            HeyStep(HeyStage.Heard(event.text.trim()), listOf(HeyEffect.ClearTimer))
        } else {
            HeyStep(stage)
        }
        HeyEvent.NoSpeech -> when (stage) {
            is HeyStage.Listening, is HeyStage.Heard -> HeyStep(HeyStage.Missed, listOf(HeyEffect.Timer(MISSED_MS)))
            else -> HeyStep(stage)
        }
        HeyEvent.CaptureStopped -> when (stage) {
            is HeyStage.Listening -> HeyStep(HeyStage.Hidden, listOf(HeyEffect.ClearTimer))
            else -> HeyStep(stage)
        }
        is HeyEvent.Started -> when (stage) {
            is HeyStage.Heard -> HeyStep(HeyStage.Working(stage.words, event.conversationId), listOf(HeyEffect.ClearTimer))
            else -> HeyStep(stage)
        }
        HeyEvent.NotStarted -> when (stage) {
            is HeyStage.Heard -> HeyStep(HeyStage.Opening(stage.words), listOf(HeyEffect.Timer(OPENING_MS)))
            else -> HeyStep(stage)
        }
        is HeyEvent.Progress -> when {
            stage !is HeyStage.Working || stage.conversationId != event.run.conversationId -> HeyStep(stage)
            !event.run.phase.active -> finish(stage, event.run)
            else -> HeyStep(progress(stage, event.run))
        }
        is HeyEvent.Finished -> when {
            stage is HeyStage.Working && stage.conversationId == event.run.conversationId -> finish(stage, event.run)
            else -> HeyStep(stage)
        }
        // The run capsule takes over this run's status, approvals and Stop; the result still comes back here. A result
        // or notice of an earlier command just goes away. While the user is still speaking nothing changes (the panel
        // is then inline, see [panel]).
        is HeyEvent.ScreenTaken -> when (stage) {
            is HeyStage.Working -> HeyStep(stage.copy(handedOff = true))
            is HeyStage.Opening, is HeyStage.Done, HeyStage.Missed -> HeyStep(HeyStage.Hidden, listOf(HeyEffect.ClearTimer))
            else -> HeyStep(stage)
        }
        HeyEvent.Cancel -> when (stage) {
            HeyStage.Hidden -> HeyStep(stage)
            is HeyStage.Listening, is HeyStage.Heard -> HeyStep(HeyStage.Hidden, listOf(HeyEffect.StopCapture, HeyEffect.ClearTimer))
            is HeyStage.Working -> HeyStep(HeyStage.Hidden, listOf(HeyEffect.StopRun(stage.conversationId), HeyEffect.ClearTimer))
            else -> HeyStep(HeyStage.Hidden, listOf(HeyEffect.ClearTimer))
        }
        HeyEvent.Open -> when (stage) {
            is HeyStage.Working -> HeyStep(HeyStage.Hidden, listOf(HeyEffect.OpenConversation(stage.conversationId), HeyEffect.ClearTimer))
            is HeyStage.Done -> HeyStep(HeyStage.Hidden, listOf(HeyEffect.OpenConversation(stage.conversationId), HeyEffect.ClearTimer))
            is HeyStage.Opening, HeyStage.Missed -> HeyStep(HeyStage.Hidden, listOf(HeyEffect.ClearTimer))
            else -> HeyStep(stage)
        }
        HeyEvent.TimerElapsed -> when (stage) {
            is HeyStage.Listening -> HeyStep(HeyStage.Missed, listOf(HeyEffect.StopCapture, HeyEffect.Timer(MISSED_MS)))
            is HeyStage.Opening, is HeyStage.Done, HeyStage.Missed -> HeyStep(HeyStage.Hidden)
            else -> HeyStep(stage)
        }
    }

    private fun progress(stage: HeyStage.Working, run: HostRun): HeyStage.Working {
        val toolRunning = run.steps.any { it.state == StepState.Running }
        val status = CapsuleStatuses.of(
            phase = run.phase,
            pending = run.pending != null,
            toolRunning = toolRunning,
            answering = CapsuleStatuses.answerStarted(run.text) && !toolRunning,
            stopping = false,
            notice = null,
        )
        val step = run.steps.lastOrNull { it.state == StepState.Running }?.let { it.summary.ifBlank { it.tool } }
        return stage.copy(status = status, step = step?.takeIf { it.isNotBlank() }, pending = run.pending)
    }

    private fun finish(stage: HeyStage.Working, run: HostRun): HeyStep {
        if (run.phase == RunPhase.Cancelled) return HeyStep(HeyStage.Hidden, listOf(HeyEffect.ClearTimer))
        val ok = run.phase == RunPhase.Done
        val text = if (ok) Markdown.plain(run.text) else run.error.orEmpty()
        return HeyStep(
            HeyStage.Done(stage.words, stage.conversationId, ok, shorten(text)),
            listOf(HeyEffect.Timer(DONE_MS)),
        )
    }

    /** One paragraph of at most [RESULT_CHARS], cut at a word with an ellipsis. */
    fun shorten(text: String): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        if (flat.length <= RESULT_CHARS) return flat
        val cut = flat.take(RESULT_CHARS)
        val space = cut.lastIndexOf(' ')
        return (if (space > RESULT_CHARS / 2) cut.take(space) else cut).trimEnd(' ', ',', ';', ':', '.') + "…"
    }

    /** The end of long live words (the newest matter most), starting at a word, with a leading ellipsis. */
    fun tail(text: String, max: Int = TAIL_CHARS): String {
        val t = text.trim()
        if (t.length <= max) return t
        val cut = t.takeLast(max)
        val space = cut.indexOf(' ')
        return "…" + (if (space in 0 until max / 2) cut.substring(space + 1) else cut)
    }

    const val TAIL_CHARS = 160

    fun glow(stage: HeyStage): HeyGlow = when (stage) {
        is HeyStage.Listening -> HeyGlow.Live
        is HeyStage.Heard -> HeyGlow.Pulse
        is HeyStage.Working -> if (stage.handedOff) HeyGlow.None else HeyGlow.Calm
        is HeyStage.Opening, is HeyStage.Done -> HeyGlow.Calm
        HeyStage.Missed, HeyStage.Hidden -> HeyGlow.None
    }

    /**
     * [screenBusy]: a run is using the screen right now. A full-screen window would then catch the agent's taps, so
     * even while listening only the panel itself is touchable (Back and taps outside no longer cancel; the X does).
     */
    fun panel(stage: HeyStage, screenBusy: Boolean = false): HeyPanel = when (stage) {
        HeyStage.Hidden -> HeyPanel.None
        is HeyStage.Listening, is HeyStage.Heard -> if (screenBusy) HeyPanel.Inline else HeyPanel.Modal
        is HeyStage.Working -> if (stage.handedOff) HeyPanel.None else HeyPanel.Inline
        is HeyStage.Opening, is HeyStage.Done, HeyStage.Missed -> HeyPanel.Inline
    }
}
