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
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * ATOM's one light around the screen, in ATOM's black and silver: the run glow while the agent operates the phone,
 * the "Hey ATOM" overlay and the assistant's entry all draw it from here, so they look the same and hand over to each
 * other without a seam.
 *
 * A luminous ice-white core line, a cool-blue halo bleeding about 36 dp inward, and a deep blue keyline just inside
 * the core, so it reads as light over dark screens and as a clear blue glow over white ones. Two soft highlights sit on the
 * sweep; the "Hey ATOM" overlay turns them while it listens.
 */
internal object EdgeGlowStyle {
    /** Cool blue: the halo, saturated enough to read as light (not grey) over white. */
    val STEEL = Color(0xFF4F86D9)
    val ICE = Color(0xFFCFE3FF)
    val SILVER = Color(0xFFC4CDD7)

    /** The core's resting tone: near-white with a touch of ice blue. */
    val CORE = Color(0xFFDCEBFF)

    /** A deep blue line just inside the core: invisible on dark content, a clear edge on white. */
    val KEYLINE = Color(0xFF1C3A66)
    val PAUSED = Color(0xFFFF9F0A)

    /** The sweep's tones for effects that draw their own shapes (the assistant's entry), closing on the first. */
    val PALETTE = listOf(STEEL, ICE, SILVER, Color.White, STEEL)

    /**
     * The working strength: the light while a run operates the phone (the run glow) and "Hey ATOM" at rest. Listening
     * is 1, the strongest.
     */
    const val WORKING = 0.68f

    /** The halo: how far in it reaches at full strength (dp), its alpha at the edge, and the bands of its falloff. */
    const val HALO_DP = 36f
    const val HALO_ALPHA = 0.78f
    const val HALO_BANDS = 18
    const val CORE_DP = 3.5f

    /** The same light as a short band (the assistant's entry): halo to core, width in dp and alpha. */
    val BAND = listOf(21f to 0.1f, 11f to 0.2f, 2.5f to 0.9f)
    const val FALLBACK_CORNER_DP = 28f

    /** Where the highlights rest (a whole turn adds nothing: every resting light looks the same). */
    private const val REST = 0.12f
    private const val STOPS = 24

    /** Colour stops round the sweep: [base], brightening to [high] at two highlights half a turn apart. */
    fun stops(turn: Float, base: Color, high: Color, strength: Float): Array<Pair<Float, Color>> {
        val phase = REST + (turn - floor(turn))
        return Array(STOPS + 1) { k ->
            val t = k / STOPS.toFloat()
            val lift = max(bump(t, phase, 0.11f), 0.65f * bump(t, phase + 0.5f, 0.075f))
            t to lerp(base, high, (lift * strength).coerceIn(0f, 1f))
        }
    }

    private fun bump(t: Float, center: Float, width: Float): Float {
        val raw = abs(t - center) % 1f
        val d = min(raw, 1f - raw)
        return exp(-(d * d) / (2f * width * width))
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
 * Draws the light along the display outline. [strength] scales it (1 is the run glow; above 1 brightens and widens
 * a little, for a pulse), [voice] (0..1) widens the halo and the core with the user's voice, [turn] moves the
 * highlights round the edge; [paused] draws the still amber line instead.
 */
internal fun DrawScope.drawEdgeGlow(
    cornerPx: Float?,
    strength: Float = 1f,
    voice: Float = 0f,
    turn: Float = 0f,
    paused: Boolean = false,
) {
    if (strength <= 0.002f) return
    val d = density
    val radius = cornerPx ?: (EdgeGlowStyle.FALLBACK_CORNER_DP * d)
    if (paused) {
        ring(SolidColor(EdgeGlowStyle.PAUSED.copy(alpha = 0.55f)), 3f * d, 1f, 1.5f * d, radius)
        return
    }
    val full = strength.coerceAtMost(1f)
    val spread = 0.7f + 0.3f * full + 0.5f * voice + 0.25f * (strength - 1f).coerceAtLeast(0f)
    // A smooth falloff from the edge inward: thin bands that each fade a little more (no visible steps).
    val halo = Brush.sweepGradient(*EdgeGlowStyle.stops(turn, EdgeGlowStyle.STEEL, EdgeGlowStyle.ICE, 0.55f), center = center)
    val bands = EdgeGlowStyle.HALO_BANDS
    val step = EdgeGlowStyle.HALO_DP * d * spread / bands
    for (k in 0 until bands) {
        val fall = 1f - k / bands.toFloat()
        val alpha = EdgeGlowStyle.HALO_ALPHA * fall * fall * (0.85f + 0.15f * fall) * strength
        ring(halo, step + 0.5f, alpha.coerceAtMost(1f), k * step, radius)
    }
    val core = (EdgeGlowStyle.CORE_DP + 1.5f * voice) * d
    ring(SolidColor(EdgeGlowStyle.KEYLINE), 1.3f * d, (0.55f * full).coerceAtMost(1f), core, radius)
    val coreBrush = Brush.sweepGradient(*EdgeGlowStyle.stops(turn, EdgeGlowStyle.CORE, Color.White, 1f), center = center)
    ring(coreBrush, core, (0.6f + 0.4f * full).coerceAtMost(1f), 0f, radius)
}

/** A stroke following the display outline, [inset] px in from the edge. */
private fun DrawScope.ring(brush: Brush, width: Float, alpha: Float, inset: Float, radius: Float) {
    if (width <= 0f || alpha <= 0f) return
    val i = inset + width / 2f
    drawRoundRect(
        brush,
        topLeft = Offset(i, i),
        size = Size(size.width - i * 2, size.height - i * 2),
        cornerRadius = CornerRadius((radius - i).coerceAtLeast(0f)),
        alpha = alpha,
        style = Stroke(width),
    )
}
