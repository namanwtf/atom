package atom.vdisplay.display

/**
 * Frame-rate cap for the viewfinder. New frames arrive only when the hidden app redraws; [offer] says whether one
 * may be shown now or, if it came too soon, when to show the latest one (a single scheduled show, never a loop),
 * so the last frame of an animation is never lost. Not thread-safe; used on one handler thread.
 */
class FrameGate(maxFps: Int) {
    private val intervalMs: Long = 1000L / maxFps.coerceIn(1, 60)
    private var lastShown = Long.MIN_VALUE / 2
    private var scheduledAt: Long? = null

    sealed interface Decision {
        data object ShowNow : Decision
        data class ShowAt(val time: Long) : Decision
        /** A show is already scheduled; it will pick up this newer frame. */
        data object AlreadyScheduled : Decision
    }

    fun offer(now: Long): Decision {
        if (scheduledAt != null) return Decision.AlreadyScheduled
        val next = lastShown + intervalMs
        return if (now >= next) {
            lastShown = now
            Decision.ShowNow
        } else {
            scheduledAt = next
            Decision.ShowAt(next)
        }
    }

    /** The scheduled show ran at [now]. */
    fun shown(now: Long) {
        scheduledAt = null
        lastShown = now
    }

    fun reset() {
        scheduledAt = null
        lastShown = Long.MIN_VALUE / 2
    }
}
