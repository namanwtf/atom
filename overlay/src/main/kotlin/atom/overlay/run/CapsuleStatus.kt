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
     * What "Add input" does now: null opens the steering field; otherwise the notice to show instead. While the
     * final answer streams the run cannot take input any more, and without a host nothing can be delivered.
     */
    fun steerBlocked(answering: Boolean, toolRunning: Boolean, hostAvailable: Boolean): CapsuleNotice? = when {
        !hostAvailable -> CapsuleNotice.InputUnsupported
        answering && !toolRunning -> CapsuleNotice.FinishingUp
        else -> null
    }
}
