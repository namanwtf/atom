package atom.core.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Exclusive phone-UI control. One run holds it at a time and keeps it until it ends or releases it explicitly, so
 * another run can never take over mid-gesture. Waiters are served first come, first served. While the holder is
 * paused the user has the phone ([State.userHasControl]) and everyone else keeps waiting.
 */
class PhoneLease {
    data class State(
        val holder: String? = null,
        val userHasControl: Boolean = false,
        val waiting: List<String> = emptyList(),
    )

    private class Waiter(val runId: String, val granted: CompletableDeferred<Unit> = CompletableDeferred())

    private val lock = Any()
    private val waiters = ArrayList<Waiter>()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun holds(runId: String): Boolean = _state.value.let { it.holder == runId && !it.userHasControl }

    /** Returns once [runId] holds phone control. Cancelling while waiting leaves the queue cleanly. */
    suspend fun acquire(runId: String) {
        val waiter: Waiter
        synchronized(lock) {
            val s = _state.value
            when {
                s.holder == runId && !s.userHasControl -> return
                s.holder == null -> {
                    publishLocked(holder = runId, user = false)
                    return
                }
                else -> {
                    waiter = Waiter(runId)
                    // The paused holder resumes before anyone else gets a turn.
                    if (s.holder == runId) waiters.add(0, waiter) else waiters.add(waiter)
                    publishLocked(s.holder, s.userHasControl)
                }
            }
        }
        try {
            waiter.granted.await()
        } catch (e: CancellationException) {
            synchronized(lock) {
                waiters.remove(waiter)
                val s = _state.value
                if (waiter.granted.isCompleted && s.holder == runId && !s.userHasControl) {
                    handOverLocked()
                } else {
                    publishLocked(s.holder, s.userHasControl)
                }
            }
            throw e
        }
    }

    /** Gives control back. Also drops [runId] from the waiting line. */
    fun release(runId: String) {
        synchronized(lock) {
            waiters.removeAll { it.runId == runId && !it.granted.isCompleted }
            val s = _state.value
            if (s.holder == runId) handOverLocked() else publishLocked(s.holder, s.userHasControl)
        }
    }

    /** The holder pauses: the user gets the phone until [resume]. No effect for a run that does not hold it. */
    fun pause(runId: String) {
        synchronized(lock) {
            val s = _state.value
            if (s.holder == runId) publishLocked(runId, user = true)
        }
    }

    fun resume(runId: String) {
        synchronized(lock) {
            val s = _state.value
            if (s.holder != runId || !s.userHasControl) return
            publishLocked(runId, user = false)
            val own = waiters.filter { it.runId == runId }
            waiters.removeAll(own)
            own.forEach { it.granted.complete(Unit) }
            publishLocked(runId, user = false)
        }
    }

    private fun handOverLocked() {
        val next = waiters.firstOrNull { it.runId != _state.value.holder } ?: waiters.firstOrNull()
        if (next == null) {
            publishLocked(holder = null, user = false)
            return
        }
        waiters.remove(next)
        publishLocked(holder = next.runId, user = false)
        next.granted.complete(Unit)
    }

    private fun publishLocked(holder: String?, user: Boolean) {
        _state.value = State(holder, user, waiters.map { it.runId }.distinct())
    }
}
