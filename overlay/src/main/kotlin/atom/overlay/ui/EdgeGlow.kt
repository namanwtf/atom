package atom.overlay.ui

import android.os.Build
import android.view.RoundedCorner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView

/**
 * ATOM's one light around the screen: the run glow while the agent operates the phone, the "Hey ATOM" overlay and
 * the assistant's entry all draw it from here, so they look the same and hand over to each other without a seam.
 * A soft halo and a sharp line in a blue → violet → pink → peach sweep; a still amber line while paused.
 */
internal object EdgeGlowStyle {
    /** The sweep's colours, back to blue at the end so it closes without a seam. */
    val PALETTE = listOf(Color(0xFF7AB8FF), Color(0xFFB59CFF), Color(0xFFFF9EC7), Color(0xFFFFC98A), Color(0xFF7AB8FF))
    val PAUSED = Color(0xFFFF9F0A)

    /** Width in dp and alpha of each stroke, from the soft halo to the sharp line. */
    val LAYERS = listOf(14f to 0.12f, 8f to 0.22f, 3f to 0.95f)
    const val INSET_DP = 1.5f
    const val FALLBACK_CORNER_DP = 28f
    private const val STOPS = 24

    /**
     * The sweep, turned by [turn] (in whole turns; 0 or any whole number is the resting position, the exact brush the
     * run glow draws).
     */
    fun brush(center: Offset, turn: Float): Brush {
        val shift = turn - kotlin.math.floor(turn)
        if (shift < 1e-4f || shift > 1f - 1e-4f) return Brush.sweepGradient(PALETTE, center = center)
        val stops = Array(STOPS + 1) { k ->
            val t = k / STOPS.toFloat()
            t to colorAt(t - shift)
        }
        return Brush.sweepGradient(*stops, center = center)
    }

    /** The palette's colour at [position] round the sweep (wrapped into 0..1), as the gradient interpolates it. */
    fun colorAt(position: Float): Color {
        val p = position - kotlin.math.floor(position)
        val scaled = p * (PALETTE.size - 1)
        val i = scaled.toInt().coerceIn(0, PALETTE.size - 2)
        return lerp(PALETTE[i], PALETTE[i + 1], scaled - i)
    }
}

/** The display's corner radius in px (null where Android does not say). */
@Composable
internal fun rememberDisplayCorner(): Float? {
    val view = LocalView.current
    return remember(view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat()
        } else {
            null
        }
    }
}

/**
 * Draws the light along the display outline. [strength] scales every stroke's alpha (1 is the run glow), [spread]
 * their widths, [turn] the sweep's rotation; [paused] draws the still amber line instead.
 */
internal fun DrawScope.drawEdgeGlow(
    cornerPx: Float?,
    strength: Float = 1f,
    spread: Float = 1f,
    turn: Float = 0f,
    paused: Boolean = false,
) {
    if (strength <= 0.002f) return
    val d = density
    val radius = cornerPx ?: (EdgeGlowStyle.FALLBACK_CORNER_DP * d)
    val inset0 = EdgeGlowStyle.INSET_DP * d
    if (paused) {
        ring(SolidColor(EdgeGlowStyle.PAUSED.copy(alpha = 0.55f)), 3f * d, strength.coerceAtMost(1f), inset0, radius)
        return
    }
    val brush = EdgeGlowStyle.brush(center, turn)
    for ((width, alpha) in EdgeGlowStyle.LAYERS) {
        ring(brush, width * d * spread, (alpha * strength).coerceAtMost(1f), inset0, radius)
    }
}

private fun DrawScope.ring(brush: Brush, width: Float, alpha: Float, inset0: Float, radius: Float) {
    if (width <= 0f || alpha <= 0f) return
    val inset = inset0 + width / 2f
    drawRoundRect(
        brush,
        topLeft = Offset(inset, inset),
        size = Size(size.width - inset * 2, size.height - inset * 2),
        cornerRadius = CornerRadius((radius - inset).coerceAtLeast(0f)),
        alpha = alpha,
        style = Stroke(width),
    )
}
