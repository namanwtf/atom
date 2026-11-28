package atom.voice

/**
 * Shows a "Hey ATOM" command being captured over whatever app is on screen (the overlay feature installs one with
 * [Wake.presenter]). Without a presenter, or when it declines in [onWake], a recognised command goes out on
 * [Wake.commands] as before and the app opens.
 *
 * Every call arrives on the main thread. Calls after [onWake] arrive only when it returned true, and each capture
 * ends with exactly one of [onCommand], [onMissed] or [onStopped] (or the presenter's own [Wake.cancelCapture]).
 */
interface WakePresenter {
    /** The wake word was heard and the command capture starts. True when the presenter shows it. */
    fun onWake(): Boolean

    /** Input loudness, 0 (silence) to 1 (loud), while the user speaks. */
    fun onLevel(level: Float) {}

    /** The words recognised so far. */
    fun onPartial(text: String) {}

    /** The final words. True when the presenter took the command (it runs it); false sends it on [Wake.commands]. */
    fun onCommand(text: String): Boolean

    /** Nothing usable was heard (silence, timeout or a recognizer error). */
    fun onMissed()

    /** The capture ended without a result because "Hey ATOM" was turned off or its service stopped. */
    fun onStopped() {}
}
