package atom.ui.theme

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Swipe-back-anywhere: a mostly horizontal rightward drag (mirrored in RTL) that starts anywhere on the screen
 * calls [onBack]. It listens in the final pointer pass and gives up as soon as a child consumes the gesture, so
 * horizontal lists, sliders and text selection keep working. Does nothing unless the user enabled
 * [Appearance.swipeBackAnywhere] and [enabled] is true (pass false on the root screen).
 *
 * Usage (app): `Modifier.swipeBackAnywhere(enabled = stack.size > 1, onBack = ::pop)` on the screen host.
 */
@Composable
fun Modifier.swipeBackAnywhere(enabled: Boolean, rtl: Boolean = false, onBack: () -> Unit): Modifier {
    val on = enabled && LocalAppearance.current.swipeBackAnywhere
    if (!on) return this
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    val slop = with(LocalDensity.current) { 12.dp.toPx() }
    val callback = rememberUpdatedState(onBack)
    return pointerInput(rtl, threshold) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            var dx = 0f
            var dy = 0f
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                if (change.isConsumed) break // a child (list, slider, pager) owns this gesture
                dx += (change.position.x - change.previousPosition.x) * (if (rtl) -1f else 1f)
                dy += change.position.y - change.previousPosition.y
                if (abs(dy) > slop && abs(dy) > abs(dx)) break // vertical intent: let it scroll
                if (dx > threshold && dx > 2f * abs(dy)) {
                    change.consume()
                    callback.value()
                    break
                }
            }
        }
    }
}
