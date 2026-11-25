package atom.overlay.assistant

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import atom.overlay.R
import atom.overlay.ui.Glyph
import atom.overlay.ui.GlyphIcon
import atom.overlay.ui.VoiceInput
import atom.overlay.ui.Warm

internal enum class HoldState { None, Holding, Cancelling }

/** Pure rules of the hold-to-talk gesture. */
internal object HoldToTalkMath {
    /** Sliding farther than [cancelDistance] from where the finger went down arms cancel; sliding back disarms it. */
    fun cancelled(dx: Float, dy: Float, cancelDistance: Float): Boolean = dx * dx + dy * dy > cancelDistance * cancelDistance

    /** What a finished hold does with what was heard. */
    fun onRelease(cancelled: Boolean): Release = if (cancelled) Release.Cancel else Release.Send

    enum class Release { Send, Cancel }
}

/**
 * The assistant's mic. Tap: start or finish listening. Hold: listen while held; lift to send, slide away and lift
 * to cancel. A long-press haptic marks the start of a hold.
 */
@Composable
internal fun HoldToTalkMic(voice: VoiceInput, onHold: (HoldState) -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val cancelPx = with(LocalDensity.current) { 72.dp.toPx() }
    var state by remember { mutableStateOf(HoldState.None) }
    fun update(next: HoldState) {
        if (state == next) return
        state = next
        onHold(next)
    }
    val listening = voice.listening
    val level by animateFloatAsState(if (listening) voice.level.coerceIn(0f, 1f) else 0f, tween(120), label = "level")
    val description = stringResource(if (listening) R.string.overlay_finish_and_send else R.string.overlay_tap_to_speak)
    val fill = when {
        state != HoldState.None -> Warm.Bolt
        listening -> Color.White.copy(alpha = 0.95f)
        else -> Color.White.copy(alpha = 0.11f)
    }
    val tint = when {
        state != HoldState.None -> Color.White
        listening -> Color(0xFF17191E)
        else -> Color.White
    }
    Box(
        Modifier
            .size(48.dp)
            .drawBehind {
                if (listening || state != HoldState.None) {
                    drawCircle(Warm.Bolt.copy(alpha = 0.16f + 0.24f * level), radius = size.minDimension / 2f * (1f + 0.3f * level))
                }
                drawCircle(fill, radius = size.minDimension / 2f - 4.dp.toPx())
            }
            .semantics {
                contentDescription = description
                role = Role.Button
                onClick {
                    voice.toggle(context)
                    true
                }
            }
            .pointerInput(voice) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var ended = false
                    val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        waitForUpOrCancellation().also { ended = true }
                    }
                    if (ended) {
                        if (up != null) {
                            up.consume()
                            voice.toggle(context)
                        }
                        return@awaitEachGesture
                    }
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    if (!voice.listening) voice.start(context)
                    update(HoldState.Holding)
                    var cancelled = false
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        change.consume()
                        val d = change.position - down.position
                        val far = HoldToTalkMath.cancelled(d.x, d.y, cancelPx)
                        if (far != cancelled) {
                            cancelled = far
                            update(if (far) HoldState.Cancelling else HoldState.Holding)
                        }
                    }
                    when (HoldToTalkMath.onRelease(cancelled)) {
                        HoldToTalkMath.Release.Cancel -> voice.cancel()
                        HoldToTalkMath.Release.Send -> voice.stop()
                    }
                    update(HoldState.None)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(Glyph.Mic, tint, 22.dp, 2.dp)
    }
}
