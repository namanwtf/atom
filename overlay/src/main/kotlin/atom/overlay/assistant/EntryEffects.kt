package atom.overlay.assistant

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/** Effects are skipped when the user turned animations off or the window cannot render them. */
@Composable
internal fun effectsEnabled(): Boolean {
    val view = LocalView.current
    return remember(view) { ValueAnimator.areAnimatorsEnabled() && view.isHardwareAccelerated }
}

/**
 * A band of light that runs from the top centre down both screen edges and fades, once per invocation. Nothing is
 * drawn (and no frames are requested) once it has finished.
 */
@Composable
internal fun EntryHalo(key: Int) {
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { progress.animateTo(1f, tween(HALO_MS, easing = FastOutSlowInEasing)) }
    if (progress.value >= 1f) return
    Canvas(Modifier.fillMaxSize()) { drawHalo(progress.value) }
}

private fun DrawScope.drawHalo(p: Float) {
    val inset = 2.dp.toPx()
    val r = 30.dp.toPx()
    val w = size.width
    val h = size.height
    val edge = Path().apply {
        moveTo(w / 2f, inset)
        lineTo(w - inset - r, inset)
        quadraticTo(w - inset, inset, w - inset, inset + r)
        lineTo(w - inset, h - inset - r)
        quadraticTo(w - inset, h - inset, w - inset - r, h - inset)
        lineTo(inset + r, h - inset)
        quadraticTo(inset, h - inset, inset, h - inset - r)
        lineTo(inset, inset + r)
        quadraticTo(inset, inset, inset + r, inset)
        close()
    }
    val measure = PathMeasure().apply { setPath(edge, false) }
    val length = measure.length
    val head = p * length / 2f
    val band = length * 0.16f
    val fade = if (p < 0.7f) 1f else (1f - p) / 0.3f
    val right = Path()
    measure.getSegment((head - band).coerceAtLeast(0f), head, right, true)
    val left = Path()
    measure.getSegment(length - head, (length - head + band).coerceAtMost(length), left, true)
    for ((width, alpha) in listOf(18.dp.toPx() to 0.08f, 8.dp.toPx() to 0.2f, 2.5.dp.toPx() to 0.85f)) {
        val style = Stroke(width, cap = StrokeCap.Round)
        drawPath(right, Color.White.copy(alpha = alpha * fade), style = style)
        drawPath(left, Color.White.copy(alpha = alpha * fade), style = style)
    }
}

/** Soft white waves along the bottom edge whose height follows the voice level. Composed only while listening. */
@Composable
internal fun VoiceWave(level: Float, modifier: Modifier = Modifier) {
    val smooth by animateFloatAsState(level.coerceIn(0f, 1f), tween(120), label = "level")
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameMillis { now ->
                if (last != 0L) phase += (now - last) / 1000f * 2.4f
                last = now
            }
        }
    }
    Canvas(modifier) {
        val base = size.height
        val layers = listOf(Triple(0.55f, 0.07f, 1.0f), Triple(0.8f, 0.11f, 1.6f), Triple(1f, 0.18f, 2.3f))
        for ((amp, alpha, speed) in layers) {
            val height = (10.dp.toPx() + smooth * 64.dp.toPx()) * amp
            val path = Path().apply {
                moveTo(0f, base)
                val steps = 48
                for (i in 0..steps) {
                    val x = size.width * i / steps
                    val t = i / steps.toFloat()
                    val envelope = sin(PI * t).toFloat()
                    val y = base - height * envelope * (0.6f + 0.4f * sin(phase * speed + t * 2f * PI.toFloat() * 1.5f))
                    lineTo(x, y)
                }
                lineTo(size.width, base)
                close()
            }
            drawPath(
                path,
                Brush.verticalGradient(listOf(Color.White.copy(alpha = alpha * 1.6f), Color.White.copy(alpha = alpha * 0.2f)), startY = base - height, endY = base),
            )
        }
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.06f + 0.1f * smooth)), startY = 0f, endY = base),
            topLeft = Offset.Zero,
        )
    }
}

private const val HALO_MS = 900
