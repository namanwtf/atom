package atom.overlay.bubble

import atom.ui.feature.HostRun
import atom.ui.feature.RunPhase
import atom.ui.feature.RunSource

/** What the orb shows. Only [Idle] is fully static; the rest animate while a run is alive. */
enum class BubbleMood {
    Idle,
    Thinking,
    Working,
    Paused,

    /** Waiting for the user to approve an action. */
    Waiting,
    ;

    val animates: Boolean get() = this == Thinking || this == Working
}

object BubbleReducer {
    /**
     * The bubble follows its own conversation first, then any other active run started from the bubble, then any
     * active run at all (so a chat task still shows as working once the user leaves the app).
     */
    fun mood(runs: List<HostRun>, ownConversation: String?): BubbleMood = pick(runs, ownConversation)?.let(::moodOf) ?: BubbleMood.Idle

    /** The active run the bubble reports on, in the order described at [mood]; null when nothing runs. */
    fun pick(runs: List<HostRun>, ownConversation: String?): HostRun? {
        val active = runs.filter { it.phase.active }
        return active.firstOrNull { ownConversation != null && it.conversationId == ownConversation }
            ?: active.firstOrNull { it.source == RunSource.Bubble }
            ?: active.firstOrNull()
    }

    fun moodOf(run: HostRun): BubbleMood = when {
        run.pending != null || run.phase == RunPhase.WaitingForApproval -> BubbleMood.Waiting
        run.phase == RunPhase.Paused -> BubbleMood.Paused
        run.phase == RunPhase.Starting || run.phase == RunPhase.WaitingForControl -> BubbleMood.Thinking
        run.phase == RunPhase.Running -> if (run.steps.isEmpty() && run.text.isEmpty()) BubbleMood.Thinking else BubbleMood.Working
        else -> BubbleMood.Idle
    }
}
