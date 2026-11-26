package atom.ui.feature

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The runs (by task id, the run id) whose screen action is waiting for the phone's screen to be on and unlocked.
 * Set by the device module's screen gate. A waiting run does not hold the task wake lock (the wait is woken by the
 * screen-on and user-present broadcasts), and its notification and capsule say "Waiting for the screen to be
 * unlocked". Per run, so concurrent runs and subagents never clear or show each other's state.
 */
object ScreenWait {
    private val _tasks = MutableStateFlow<Set<String>>(emptySet())
    val tasks: StateFlow<Set<String>> = _tasks.asStateFlow()

    fun begin(taskId: String) = _tasks.update { it + taskId }

    fun end(taskId: String) = _tasks.update { it - taskId }

    fun waiting(taskId: String): Boolean = taskId in _tasks.value
}
