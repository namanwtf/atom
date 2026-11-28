package atom.app.agent

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How a run waits for the user to answer an approval. Waiting is suspension only: no polling and, while it lasts, no
 * wake lock (the task runner lets it go).
 */
internal object ApprovalWait {
    /** How long a paused run keeps an unanswered question open before it counts as declined. */
    const val PARKED_MS = 24 * 60 * 60_000L

    /**
     * The user's answer to [answer], waiting up to [waitMs]. When nobody answers in time: with [pauses] the run is
     * paused ([onTimeout]) and the question stays open for [PARKED_MS] more; without it the action is declined.
     */
    suspend fun await(answer: Deferred<Boolean>, waitMs: Long, pauses: Boolean, onTimeout: suspend () -> Unit): Boolean {
        withTimeoutOrNull(waitMs) { answer.await() }?.let { return it }
        if (!pauses) return false
        onTimeout()
        return withTimeoutOrNull(PARKED_MS) { answer.await() } ?: false
    }
}
