package atom.ui.feature

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The screen actions waiting for the phone's screen to be on and unlocked, one entry per waiting call, tagged with
 * the run (task id) it belongs to. Set by the device module's screen gate. A run with a waiting call does not hold
 * the task wake lock (the wait is woken by the screen-on and user-present broadcasts), and its notification and
 * capsule say "Waiting for the screen to be unlocked". Each call has its own entry, so a subagent sharing its
 * parent's task id, or another run, never clears someone else's wait.
 */
object ScreenWait {
    private val next = AtomicLong()
    private val _tasks = MutableStateFlow<Set<String>>(emptySet())

    /** Entries "<task id>#<n>", for observing changes. */
    val tasks: StateFlow<Set<String>> = _tasks.asStateFlow()

    /** Starts one wait for [taskId]; pass the returned token to [end]. */
    fun begin(taskId: String): String {
        val token = taskId + "#" + next.incrementAndGet()
        _tasks.update { it + token }
        return token
    }

    fun end(token: String) = _tasks.update { it - token }

    fun waiting(taskId: String): Boolean = _tasks.value.any { it.substringBeforeLast('#') == taskId }
}
