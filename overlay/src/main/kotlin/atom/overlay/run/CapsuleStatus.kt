package atom.overlay.run

import atom.ui.feature.RunPhase

/** The capsule's one-line status. */
internal enum class CapsuleStatus {
    Starting,
    Reasoning,
    Working,
    GeneratingAnswer,
    Stopping,
    Paused,
    WaitingForControl,
    NeedsApproval,
    Continuing,
    InputReceived,
    FinishingUp,
    InputUnsupported,
}

/** Short-lived statuses after a control was used; they show for [CapsuleStatuses.NOTICE_MS]. */
internal enum class CapsuleNotice { Continuing, InputReceived, FinishingUp, InputUnsupported }

internal object CapsuleStatuses {
    const val NOTICE_MS = 2_500L

    /**
     * Stopping wins (the user asked), then what blocks the run (approval, pause, waiting for the screen), then a
     * fresh [notice], then what the run is doing: a tool, the final answer streaming, or reasoning.
     */
    fun of(
        phase: RunPhase,
        pending: Boolean,
        toolRunning: Boolean,
        answering: Boolean,
        stopping: Boolean,
        notice: CapsuleNotice?,
    ): CapsuleStatus = when {
        stopping -> CapsuleStatus.Stopping
        pending || phase == RunPhase.WaitingForApproval -> CapsuleStatus.NeedsApproval
        phase == RunPhase.Paused -> CapsuleStatus.Paused
        phase == RunPhase.WaitingForControl -> CapsuleStatus.WaitingForControl
        notice != null -> when (notice) {
            CapsuleNotice.Continuing -> CapsuleStatus.Continuing
            CapsuleNotice.InputReceived -> CapsuleStatus.InputReceived
            CapsuleNotice.FinishingUp -> CapsuleStatus.FinishingUp
            CapsuleNotice.InputUnsupported -> CapsuleStatus.InputUnsupported
        }
        phase == RunPhase.Starting -> CapsuleStatus.Starting
        toolRunning -> CapsuleStatus.Working
        answering -> CapsuleStatus.GeneratingAnswer
        else -> CapsuleStatus.Reasoning
    }

    /**
     * Whether the streamed [text] reads as the final answer rather than a preamble the model writes before a tool
     * call ("Opening Settings."). The stream does not say which it is until the call arrives, so a short single
     * line is taken as a preamble: the capsule keeps "Thinking" and "Add input" stays open (the input then waits
     * for the next safe point, or the next turn).
     */
    fun answerStarted(text: String): Boolean {
        val t = text.trim()
        return t.length >= ANSWER_MIN_CHARS || '\n' in t
    }

    /** Longer than any one-line preamble. */
    const val ANSWER_MIN_CHARS = 160

    /**
     * What "Add input" does now: null opens the steering field; otherwise the notice to show instead. While the
     * final answer streams the run cannot take input any more, and without a host nothing can be delivered.
     */
    fun steerBlocked(answering: Boolean, toolRunning: Boolean, hostAvailable: Boolean): CapsuleNotice? = when {
        !hostAvailable -> CapsuleNotice.InputUnsupported
        answering && !toolRunning -> CapsuleNotice.FinishingUp
        else -> null
    }
}
