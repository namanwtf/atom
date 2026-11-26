package atom.core.tool

/**
 * Tools that need the phone's screen on and unlocked ([Tool.needsUnlockedScreen]) wait here before they run, outside
 * their own timeout: the device module installs [gate], which waits event-driven (no polling) for the screen to be
 * unlocked and marks the run as waiting meanwhile. Without a gate (tests, other hosts) nothing waits.
 */
object PhoneScreen {
    fun interface Gate {
        /** True as soon as the screen is usable for run [taskId]; false when it stayed locked too long. */
        suspend fun awaitUsable(taskId: String): Boolean
    }

    @Volatile
    var gate: Gate? = null

    const val LOCKED_TOO_LONG =
        "The phone's screen stayed off or locked for 10 minutes, so the screen cannot be read or used. " +
            "Tell the user to unlock the phone to continue."
}
