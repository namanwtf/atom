package atom.ui.feature

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * True while a run's screen action is waiting for the phone's screen to be on and unlocked (the screen went off or
 * locked mid-task). Set by the device module's screen tools; the running-task notification and the run capsule
 * show "Waiting for the screen to be unlocked" while it holds.
 */
object ScreenWait {
    private val _waiting = MutableStateFlow(false)
    val waiting: StateFlow<Boolean> = _waiting.asStateFlow()

    fun set(waiting: Boolean) {
        _waiting.value = waiting
    }
}
