package atom.core.runtime

/**
 * Android 16 "Live Update" (promoted ongoing notification) policy for a running task. The status-bar chip shows a
 * short word: "Working" while the agent acts or thinks, "Answering" while the final answer streams.
 */
object LiveUpdate {
    /** Android 16 (API 36) is the first release that promotes ongoing notifications to Live Updates. */
    const val MIN_SDK = 36

    enum class Chip { Working, Answering, Paused, Waiting }

    /**
     * Whether to ask the system to promote the run notification. Only one run at a time is promoted (the multi-run
     * summary is a group summary, which the system never promotes), and only when the user leaves it on.
     */
    fun promote(sdkInt: Int, enabled: Boolean, singleRun: Boolean): Boolean = enabled && singleRun && sdkInt >= MIN_SDK

    /** The chip for a run's current state. */
    fun chip(paused: Boolean, waitingForUser: Boolean, usingTool: Boolean, streamingAnswer: Boolean): Chip = when {
        paused -> Chip.Paused
        waitingForUser -> Chip.Waiting
        usingTool -> Chip.Working
        streamingAnswer -> Chip.Answering
        else -> Chip.Working
    }
}
