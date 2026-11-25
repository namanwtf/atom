package atom.overlay.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp

/** Fixed warm-dark palette of the bubble, hold menu, dismiss target and mini chat, whatever the app theme. */
internal object Warm {
    val Background = Color(0xFF121110)
    val Card = Color(0xFF1D1B19)
    val Card2 = Color(0xFF2A2724)
    val Text = Color(0xFFF3EFE8)
    val Muted = Color(0xFFA39D93)
    val Outline = Color(0xFF2E2A26)
    val UserBubble = Color(0xFF2B3A6B)
    val MenuFill = Color(0xFF181614)

    /** The overlay accent. */
    val Bolt = Color(0xFF4F8BFF)
    val Spark = Color(0xFF7DF9FF)
    val HideRed = Color(0xFFE58A7A)
}

/** Line glyphs drawn on a 24-unit grid, so one stroke width reads the same at every size. */
internal enum class Glyph { Close, Plus, Expand, Chat, Mic, ArrowUp }

/** A [Glyph] at [size] with a [stroke] measured in dp at that size. */
@Composable
internal fun GlyphIcon(glyph: Glyph, tint: Color, size: Dp, stroke: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { drawGlyph(glyph, tint, stroke.toPx()) }
}

internal fun DrawScope.drawGlyph(glyph: Glyph, tint: Color, strokePx: Float, origin: Offset = Offset.Zero, sizePx: Float = size.minDimension) {
    val u = sizePx / 24f
    fun p(x: Float, y: Float) = Offset(origin.x + x * u, origin.y + y * u)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(tint, p(x1, y1), p(x2, y2), strokePx, StrokeCap.Round)
    val style = Stroke(strokePx, cap = StrokeCap.Round)
    when (glyph) {
        Glyph.Close -> {
            line(6f, 6f, 18f, 18f)
            line(18f, 6f, 6f, 18f)
        }
        Glyph.Plus -> {
            line(12f, 4.5f, 12f, 19.5f)
            line(4.5f, 12f, 19.5f, 12f)
        }
        Glyph.Expand -> {
            // Two opposing corner brackets: top-left and bottom-right.
            line(4.5f, 4.5f, 10.5f, 4.5f)
            line(4.5f, 4.5f, 4.5f, 10.5f)
            line(19.5f, 19.5f, 13.5f, 19.5f)
            line(19.5f, 19.5f, 19.5f, 13.5f)
        }
        Glyph.Chat -> {
            drawRoundRect(tint, p(3.5f, 4.5f), Size(17f * u, 11.5f * u), CornerRadius(4f * u), style = style)
            line(7.5f, 16f, 7.5f, 20f)
        }
        Glyph.Mic -> {
            drawRoundRect(tint, p(9f, 3f), Size(6f * u, 11f * u), CornerRadius(3f * u), style = style)
            drawArc(tint, 0f, 180f, false, p(5.5f, 5.5f), Size(13f * u, 11.5f * u), style = style)
            line(12f, 17f, 12f, 20.5f)
        }
        Glyph.ArrowUp -> {
            line(12f, 19f, 12f, 5f)
            line(6f, 11f, 12f, 5f)
            line(18f, 11f, 12f, 5f)
        }
    }
}
