package atom.xposed

import kotlin.math.abs

/** A system-wide gesture that should open Circle to Search. */
enum class SearchGesture { GestureBar, TwoFinger }

/**
 * Passive detector for the two Circle to Search gestures, fed with the touch stream system_server already
 * dispatches to its system-gesture listeners (nothing is consumed, nothing polls):
 *
 *  - [SearchGesture.GestureBar]: one finger goes down on the gesture handle (bottom strip, middle of the screen)
 *    and stays there for [longPressMs].
 *  - [SearchGesture.TwoFinger]: a second finger joins within [secondFingerMs], then both stay still for
 *    [longPressMs].
 *
 * The caller schedules a single [check] at the time [onDown]/[onPointerDown] return (no timers here), and calls
 * [reset] on up/cancel. Pure Kotlin so it is unit tested; one instance per display, not thread-safe.
 */
class LongPressGestures(
    private val longPressMs: Long = 500,
    private val secondFingerMs: Long = 300,
) {
    /** Geometry of the display at touch-down time, in pixels. */
    data class Area(val width: Float, val height: Float, val barHeightPx: Float, val slopPx: Float) {
        /** The handle sits centred at the bottom; only its middle half-width counts, so edge swipes are left alone. */
        fun onBar(x: Float, y: Float): Boolean = y >= height - barHeightPx && x >= width * 0.25f && x <= width * 0.75f
    }

    private var area: Area? = null
    private var enabledBar = false
    private var enabledTwoFinger = false
    private var downAt = 0L
    private var secondAt = 0L
    private var pointers = 0
    private var startX = FloatArray(2)
    private var startY = FloatArray(2)
    private var candidate: SearchGesture? = null
    private var fired = false

    /** First finger down. Returns when to call [check], or null if no gesture can start here. */
    fun onDown(x: Float, y: Float, time: Long, area: Area, bar: Boolean, twoFinger: Boolean): Long? {
        reset()
        this.area = area
        enabledBar = bar
        enabledTwoFinger = twoFinger
        downAt = time
        pointers = 1
        startX[0] = x
        startY[0] = y
        candidate = when {
            bar && area.onBar(x, y) -> SearchGesture.GestureBar
            else -> null
        }
        return when {
            candidate != null -> time + longPressMs
            twoFinger -> null // wait for the second finger
            else -> null
        }
    }

    /** Another finger down; [count] is the new pointer count. Returns when to call [check], or null. */
    fun onPointerDown(count: Int, x: Float, y: Float, time: Long): Long? {
        pointers = count
        if (count != 2 || !enabledTwoFinger || fired || time - downAt > secondFingerMs) {
            candidate = null
            return null
        }
        startX[1] = x
        startY[1] = y
        secondAt = time
        candidate = SearchGesture.TwoFinger
        return time + longPressMs
    }

    /** Current positions of pointer 0 and (if down) pointer 1. Movement beyond slop cancels the gesture. */
    fun onMove(x0: Float, y0: Float, x1: Float = Float.NaN, y1: Float = Float.NaN) {
        val slop = area?.slopPx ?: return
        if (candidate == null) return
        if (moved(x0, y0, 0, slop)) candidate = null
        if (pointers >= 2 && !x1.isNaN() && moved(x1, y1, 1, slop)) candidate = null
    }

    /** A finger lifted while others remain. Any lift ends the gesture. */
    fun onPointerUp() {
        candidate = null
    }

    /** The scheduled check. Returns the gesture once, when it completed. */
    fun check(now: Long): SearchGesture? {
        val c = candidate ?: return null
        if (fired) return null
        val since = if (c == SearchGesture.TwoFinger) secondAt else downAt
        val expectedPointers = if (c == SearchGesture.TwoFinger) 2 else 1
        if (pointers != expectedPointers || now - since < longPressMs) return null
        fired = true
        return c
    }

    fun reset() {
        candidate = null
        fired = false
        pointers = 0
        area = null
    }

    private fun moved(x: Float, y: Float, i: Int, slop: Float) = abs(x - startX[i]) > slop || abs(y - startY[i]) > slop
}
