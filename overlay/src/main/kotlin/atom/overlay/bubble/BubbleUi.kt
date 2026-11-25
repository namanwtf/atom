package atom.overlay.bubble

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import atom.overlay.R
import atom.overlay.ui.Glyph
import atom.overlay.ui.Warm
import atom.overlay.ui.drawGlyph
import atom.ui.theme.AtomMark
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** The dark face disc. */
internal val FACE = 60.dp

/** The owner's octopus emblem inside the face. */
internal val EMBLEM = 44.dp

/** The orbit box the accent arcs run in. */
internal val ORBIT = 74.dp

/** The bubble window: the orbit box plus 4dp all round, so the drag swell and press never clip. */
internal val BUBBLE_WINDOW = 82.dp

/**
 * The bubble face: a dark disc holding the emblem. Idle (and finished) it is one static image: no transition, no
 * frame callback. Only a thinking or working run starts the breathing and the three accent arcs.
 */
@Composable
internal fun BubbleFace(mood: BubbleMood, modifier: Modifier = Modifier) {
    Box(modifier.size(ORBIT), contentAlignment = Alignment.Center) {
        if (mood.animates) {
            RunningFace()
        } else {
            Disc(Modifier.graphicsLayer { alpha = if (mood == BubbleMood.Paused) 0.55f else 1f })
            if (mood == BubbleMood.Waiting) Canvas(Modifier.fillMaxSize()) { drawWaitingRing() }
        }
    }
}

@Composable
private fun Disc(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(FACE)
            .clip(CircleShape)
            .background(Warm.Card),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(EMBLEM).clip(CircleShape), contentAlignment = Alignment.Center) {
            AtomMark(Modifier.fillMaxSize())
        }
    }
}

/** A run waits for the user's OK: a still accent ring with a spark dot, nothing animating. */
private fun DrawScope.drawWaitingRing() {
    val stroke = 2.5.dp.toPx()
    val inset = 1.5.dp.toPx() + stroke / 2f
    drawArc(Warm.Bolt.copy(alpha = 0.8f), 0f, 360f, false, Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset), style = Stroke(stroke))
    drawCircle(Warm.Spark, radius = 4.dp.toPx(), center = Offset(size.width * 0.85f, size.height * 0.15f))
}

/** Three Bolt arcs, each at its own speed and direction, around a face that breathes 1.0 → 0.9. */
@Composable
private fun RunningFace() {
    val breath by rememberInfiniteTransition(label = "breath").animateFloat(
        1f,
        0.9f,
        infiniteRepeatable(tween(750, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (true) withFrameMillis { elapsed = it - start }
    }
    Disc(
        Modifier.graphicsLayer {
            scaleX = breath
            scaleY = breath
        },
    )
    // Reads the clock in the draw phase only: each frame redraws the arcs without recomposing.
    Box(Modifier.fillMaxSize().drawBehind { drawArcs(elapsed) })
}

private class ArcSpec(val speed: Float, val sweep: Float, val alpha: Float, val start: Float)

private val ARCS = listOf(
    ArcSpec(1f, 95f, 0.92f, 0f),
    ArcSpec(-0.73f, 70f, 0.66f, 120f),
    ArcSpec(1.27f, 55f, 0.46f, 240f),
)

private const val TURN_MS = 2600f

private fun DrawScope.drawArcs(elapsedMs: Long) {
    val stroke = 2.5.dp.toPx()
    val inset = 1.5.dp.toPx() + stroke / 2f
    val box = Size(size.width - 2 * inset, size.height - 2 * inset)
    val base = (elapsedMs % (TURN_MS.toLong() * 100)) / TURN_MS * 360f
    for (arc in ARCS) {
        drawArc(
            Warm.Bolt.copy(alpha = arc.alpha),
            (arc.start + base * arc.speed) % 360f,
            arc.sweep,
            false,
            Offset(inset, inset),
            box,
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

/** Content of the small bubble window. */
@Composable
internal fun BubbleContent(mood: BubbleMood, pressed: Boolean) {
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, tween(150), label = "press")
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        BubbleFace(
            mood,
            Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        )
    }
}

// ------------------------------------------------------------------ Status line

/** The status pill beside the face, in its own small window so the bubble window never resizes. */
@Composable
internal fun StatusPill(text: String, paused: Boolean) {
    Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
        Text(
            text,
            color = Warm.Text,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = 250.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Warm.Card)
                .border(1.dp, if (paused) Warm.Muted else Warm.Bolt.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

// ------------------------------------------------------------------ Drag layer

/** Shared between the touch handler (writer) and the drag layer (reader). Positions are window top-left, in px. */
internal class DragState(val target: Pt) {
    var pos by mutableStateOf(Offset.Zero)
    var closeness by mutableFloatStateOf(0f)
    var captured by mutableStateOf(false)
    var settleTo by mutableStateOf<Offset?>(null)
    var dismissing by mutableStateOf(false)
    var onReady: () -> Unit = {}
    var onSettled: () -> Unit = {}
    var onDismissed: () -> Unit = {}
}

internal val DISMISS_TARGET = 64.dp

/**
 * Full-screen, touch-through layer shown only while dragging: the bubble follows the finger here (no window moves
 * per frame), with the X target near the bottom and a charged beam that strengthens as the bubble closes in.
 */
@Composable
internal fun DragLayer(state: DragState, mood: BubbleMood) {
    val density = LocalDensity.current
    val window = with(density) { BUBBLE_WINDOW.toPx() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        withFrameNanos { }
        state.onReady()
    }

    val magnet = Offset(state.target.x - window / 2f, state.target.y - window / 2f)
    val goal = state.settleTo ?: if (state.captured || state.dismissing) magnet else state.pos
    val pos = remember { Animatable(state.pos, Offset.VectorConverter) }
    LaunchedEffect(goal, state.settleTo != null, state.captured) {
        when {
            state.settleTo != null -> {
                pos.animateTo(goal, tween(200, easing = FastOutSlowInEasing))
                state.onSettled()
            }
            state.captured || state.dismissing -> pos.animateTo(goal, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium))
            else -> pos.snapTo(goal)
        }
    }
    val vanish = remember { Animatable(1f) }
    LaunchedEffect(state.dismissing) {
        if (state.dismissing) {
            vanish.animateTo(0f, tween(180))
            state.onDismissed()
        }
    }
    val closeness by animateFloatAsState(
        when {
            state.settleTo != null -> 0f
            state.captured -> 1f
            else -> state.closeness
        },
        tween(90),
        label = "closeness",
    )
    val fused by animateFloatAsState(if (state.captured) 1f else 0f, tween(140), label = "fused")
    val swell by animateFloatAsState(if (state.settleTo != null) 1f else if (state.captured) 0.86f else 1.14f, tween(160), label = "swell")
    val chrome by animateFloatAsState(if (state.settleTo != null) 0f else 1f, tween(180), label = "chrome")
    val pulse by rememberInfiniteTransition(label = "beam").animateFloat(
        0f,
        1f,
        infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val flow by rememberInfiniteTransition(label = "current").animateFloat(
        0f,
        1f,
        infiniteRepeatable(tween(700, easing = LinearEasing)),
        label = "flow",
    )
    val description = stringResource(R.string.overlay_dismiss_target)

    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = chrome }) {
            val t = Offset(state.target.x, state.target.y)
            val b = Offset(pos.value.x + window / 2f, pos.value.y + window / 2f)
            drawDismissHalo(t, closeness)
            if (closeness > 0.02f) drawBeam(b, t, closeness, fused, pulse, flow)
            drawDismissTarget(t, fused)
        }
        Box(
            Modifier
                .offset {
                    val r = DISMISS_TARGET.roundToPx() / 2
                    IntOffset(state.target.x.roundToInt() - r, state.target.y.roundToInt() - r)
                }
                .size(DISMISS_TARGET)
                .semantics { contentDescription = description },
        )
        Box(
            Modifier
                .offset { IntOffset(pos.value.x.roundToInt(), pos.value.y.roundToInt()) }
                .size(BUBBLE_WINDOW)
                .graphicsLayer {
                    scaleX = swell * vanish.value
                    scaleY = swell * vanish.value
                    alpha = vanish.value
                },
            contentAlignment = Alignment.Center,
        ) {
            BubbleFace(mood)
        }
    }
}

private fun DrawScope.drawDismissHalo(t: Offset, closeness: Float) {
    val r = 120.dp.toPx()
    drawCircle(
        Brush.radialGradient(listOf(Warm.Bolt.copy(alpha = 0.3f * (0.4f + closeness).coerceAtMost(1.4f)), Color.Transparent), center = t, radius = r),
        radius = r,
        center = t,
    )
}

/**
 * The beam: a soft glow, a Bolt → Spark core and, once the bubble is close, two thin live currents that wind
 * around the core and tighten as it nears. All three only exist while dragging.
 */
private fun DrawScope.drawBeam(from: Offset, to: Offset, closeness: Float, fused: Float, pulse: Float, flow: Float) {
    val length = hypot(to.x - from.x, to.y - from.y)
    if (length < 1f) return
    val brush = Brush.linearGradient(listOf(Warm.Bolt, Warm.Spark), start = from, end = to)
    val width = (1.8f + (3.2f - 1.8f) * fused + pulse * 0.6f).dp.toPx()
    val alpha = (0.2f + 0.55f * closeness) * (1f - fused) + 0.95f * fused
    drawLine(brush, from, to, strokeWidth = width * 4f, cap = StrokeCap.Round, alpha = alpha * 0.18f)
    drawLine(brush, from, to, strokeWidth = width, cap = StrokeCap.Round, alpha = alpha)

    val charge = ((closeness - 0.35f) / 0.65f).coerceIn(0f, 1f).coerceAtLeast(fused)
    if (charge <= 0f) return
    val angle = atan2(to.y - from.y, to.x - from.x)
    val nx = -sin(angle)
    val ny = cos(angle)
    val amplitude = (2.5f + 4.5f * (1f - closeness)).dp.toPx() * (0.6f + 0.4f * pulse)
    val waves = (length / 36.dp.toPx()).coerceAtLeast(2f)
    for (strand in 0..1) {
        val path = Path()
        val steps = 40
        for (i in 0..steps) {
            val f = i / steps.toFloat()
            // Pinned at both ends; the wave runs from the bubble toward the target.
            val envelope = sin(PI * f).toFloat()
            val phase = 2f * PI.toFloat() * (f * waves - flow + strand * 0.5f)
            val off = amplitude * envelope * sin(phase)
            val x = from.x + (to.x - from.x) * f + nx * off
            val y = from.y + (to.y - from.y) * f + ny * off
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, brush, alpha = 0.55f * charge, style = Stroke(1.dp.toPx(), cap = StrokeCap.Round))
    }
}

private fun DrawScope.drawDismissTarget(t: Offset, fused: Float) {
    val r = DISMISS_TARGET.toPx() / 2f
    val fill = lerp(Color(0xFF121614).copy(alpha = 0.94f), Color(0xFF14203C), fused)
    drawCircle(fill, radius = r, center = t)
    drawCircle(lerp(Warm.Bolt.copy(alpha = 0.95f), Color.White, fused), radius = r - 1.dp.toPx(), center = t, style = Stroke(2.dp.toPx()))
    val glyph = 22.dp.toPx()
    drawGlyph(Glyph.Close, lerp(Warm.Text, Color.White, fused), 2.4.dp.toPx(), Offset(t.x - glyph / 2f, t.y - glyph / 2f), glyph)
}

private fun lerp(a: Color, b: Color, f: Float): Color = androidx.compose.ui.graphics.lerp(a, b, f.coerceIn(0f, 1f))

// ------------------------------------------------------------------ Hold menu

internal enum class MenuAction { Hide, OpenChat, Talk }

/** [centers] are the buttons in the menu window's own px; [bubble] is the bubble's real centre in the same space. */
internal class MenuState(val bubble: Offset, val centers: List<Offset>) {
    var hover by mutableIntStateOf(-1)
}

internal val MENU_BUTTON = 52.dp
internal val MENU_WINDOW = 344.dp
internal val MENU_RADIUS = 132.dp

/** The halo around the bubble and the three icon-only actions on their arc. Taps anywhere else close it. */
@Composable
internal fun HoldMenu(state: MenuState, onAction: (MenuAction) -> Unit, onDismiss: () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 380f)) }
    val ring by rememberInfiniteTransition(label = "ring").animateFloat(
        0f,
        1f,
        infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "pulse",
    )
    val density = LocalDensity.current
    val button = with(density) { MENU_BUTTON.toPx() }
    Box(
        Modifier
            .fillMaxSize()
            .onTap(onDismiss),
    ) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) }) {
            val c = state.bubble
            val halo = 84.dp.toPx()
            drawCircle(Brush.radialGradient(listOf(Warm.Bolt.copy(alpha = 0.22f), Color.Transparent), center = c, radius = halo), radius = halo, center = c)
            drawCircle(
                Warm.Bolt.copy(alpha = 0.45f * (1f - ring)),
                radius = 38.dp.toPx() + ring * 70.dp.toPx(),
                center = c,
                style = Stroke(2.dp.toPx()),
            )
        }
        val items = listOf(
            Triple(MenuAction.Hide, Glyph.Close, R.string.overlay_hide),
            Triple(MenuAction.OpenChat, Glyph.Chat, R.string.overlay_open_chat),
            Triple(MenuAction.Talk, Glyph.Mic, R.string.overlay_talk),
        )
        items.forEachIndexed { i, (action, glyph, label) ->
            val c = state.centers.getOrNull(i) ?: return@forEachIndexed
            MenuButton(
                glyph = glyph,
                tint = if (action == MenuAction.Hide) Warm.HideRed else Warm.Bolt,
                label = stringResource(label),
                hovered = state.hover == i,
                modifier = Modifier
                    .offset { IntOffset((c.x - button / 2f).roundToInt(), (c.y - button / 2f).roundToInt()) }
                    .graphicsLayer {
                        val a = (progress.value * 1.4f - i * 0.15f).coerceIn(0f, 1f)
                        alpha = a
                        scaleX = 0.6f + 0.4f * a
                        scaleY = 0.6f + 0.4f * a
                    },
                onClick = { onAction(action) },
            )
        }
    }
}

private fun Modifier.onTap(action: () -> Unit): Modifier = pointerInput(Unit) { detectTapGestures { action() } }

@Composable
private fun MenuButton(glyph: Glyph, tint: Color, label: String, hovered: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val lift by animateFloatAsState(if (hovered) 1.12f else 1f, tween(150), label = "hover")
    Box(
        modifier
            .size(MENU_BUTTON)
            .graphicsLayer {
                scaleX *= lift
                scaleY *= lift
            }
            .semantics {
                contentDescription = label
                role = Role.Button
            }
            .onTap(onClick),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            drawCircle(if (hovered) lerp(Warm.MenuFill, tint, 0.28f) else Warm.MenuFill.copy(alpha = 0.94f), radius = r)
            drawCircle(tint.copy(alpha = 0.8f), radius = r - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
            val g = 24.dp.toPx()
            drawGlyph(glyph, tint, 1.8.dp.toPx(), Offset(center.x - g / 2f, center.y - g / 2f), g)
        }
    }
}
