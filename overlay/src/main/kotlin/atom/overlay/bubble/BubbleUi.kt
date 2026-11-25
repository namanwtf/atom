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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import atom.overlay.R
import atom.overlay.ui.OverlayIcons
import atom.ui.theme.AtomIcons
import atom.ui.theme.AtomMark
import kotlin.math.roundToInt

internal val ORB = 54.dp
internal val BUBBLE_WINDOW = 70.dp

/**
 * The orb. Idle draws once and never again: the disc, the emblem and nothing else. Rings only exist (and only
 * animate) while a run is thinking or working.
 */
@Composable
internal fun Orb(mood: BubbleMood, modifier: Modifier = Modifier, size: Dp = ORB) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind { drawDisc() },
        )
        Box(Modifier.fillMaxSize(0.7f).clip(CircleShape), contentAlignment = Alignment.Center) {
            AtomMark(Modifier.fillMaxSize())
        }
        when (mood) {
            BubbleMood.Thinking, BubbleMood.Working -> ActivityRing(working = mood == BubbleMood.Working)
            BubbleMood.Paused -> Canvas(Modifier.fillMaxSize()) { drawStaticRing(alpha = 0.45f, dashed = true) }
            BubbleMood.Waiting -> Canvas(Modifier.fillMaxSize()) {
                drawStaticRing(alpha = 0.8f, dashed = false)
                drawCircle(Color.White, radius = this.size.minDimension * 0.09f, center = Offset(this.size.width * 0.84f, this.size.height * 0.16f))
            }
            BubbleMood.Idle -> Unit
        }
    }
}

private fun DrawScope.drawDisc() {
    val r = size.minDimension / 2f
    drawCircle(
        Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent), center = center, radius = r * 1.25f),
        radius = r * 1.25f,
    )
    drawCircle(
        Brush.radialGradient(
            listOf(Color(0xFF26262B), Color(0xFF111114), Color(0xFF060607)),
            center = Offset(center.x - r * 0.25f, center.y - r * 0.3f),
            radius = r * 1.4f,
        ),
        radius = r,
    )
    drawCircle(Color.White.copy(alpha = 0.16f), radius = r - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
}

private fun DrawScope.drawStaticRing(alpha: Float, dashed: Boolean) {
    val stroke = 2.dp.toPx()
    val inset = stroke / 2f + 1.dp.toPx()
    val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
    if (dashed) {
        for (i in 0 until 3) {
            drawArc(Color.White.copy(alpha = alpha), -90f + i * 120f + 15f, 90f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        }
    } else {
        drawArc(Color.White.copy(alpha = alpha), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
    }
}

/** Three white arcs: spinning while working, breathing slowly while thinking. */
@Composable
private fun ActivityRing(working: Boolean) {
    val transition = rememberInfiniteTransition(label = "orb")
    val turn by transition.animateFloat(
        0f,
        360f,
        infiniteRepeatable(tween(if (working) 1600 else 2800, easing = LinearEasing)),
        label = "turn",
    )
    val breath by transition.animateFloat(
        0.35f,
        0.95f,
        infiniteRepeatable(tween(if (working) 700 else 1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )
    Canvas(Modifier.fillMaxSize()) {
        val stroke = 2.dp.toPx()
        val inset = stroke / 2f + 1.dp.toPx()
        val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
        rotate(turn) {
            for (i in 0 until 3) {
                drawArc(
                    Color.White.copy(alpha = if (working) 0.95f else breath),
                    i * 120f,
                    if (working) 62f + 20f * breath else 70f,
                    false,
                    Offset(inset, inset),
                    arcSize,
                    style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                )
            }
        }
    }
}

/** Content of the small bubble window. */
@Composable
internal fun BubbleContent(mood: BubbleMood, pressed: Boolean) {
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, tween(150), label = "press")
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Orb(mood, Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        })
    }
}

// ------------------------------------------------------------------ Drag layer

/** Shared between the touch handler (writer) and the drag layer (reader). Positions are window top-left, in px. */
internal class DragState(val target: Pt) {
    var pos by mutableStateOf(Offset.Zero)
    var proximity by mutableFloatStateOf(0f)
    var captured by mutableStateOf(false)
    var settleTo by mutableStateOf<Offset?>(null)
    var dismissing by mutableStateOf(false)
    var onReady: () -> Unit = {}
    var onSettled: () -> Unit = {}
    var onDismissed: () -> Unit = {}
}

/**
 * Full-screen, touch-through layer shown only while dragging: the bubble follows the finger here (no window moves
 * per frame), with the X target and a beam that strengthens as the bubble gets closer.
 */
@Composable
internal fun DragLayer(state: DragState, mood: BubbleMood) {
    val density = LocalDensity.current
    val window = with(density) { BUBBLE_WINDOW.toPx() }
    val targetRadius = with(density) { 30.dp.toPx() }
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
                pos.animateTo(goal, tween(220, easing = FastOutSlowInEasing))
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
    val proximity by animateFloatAsState(if (state.settleTo != null) 0f else state.proximity, tween(90), label = "proximity")
    val braced by animateFloatAsState(if (state.captured) 1f else 0f, tween(160), label = "brace")
    val swell by animateFloatAsState(if (state.settleTo != null) 1f else if (state.captured) 0.86f else 1.12f, tween(160), label = "swell")
    val chrome by animateFloatAsState(if (state.settleTo != null) 0f else 1f, tween(180), label = "chrome")

    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = chrome }) {
            val t = Offset(state.target.x, state.target.y)
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f)), startY = size.height * 0.6f, endY = size.height),
                topLeft = Offset(0f, size.height * 0.6f),
                size = Size(size.width, size.height * 0.4f),
            )
            if (proximity > 0.01f) {
                val top = (pos.value.y + window / 2f).coerceAtMost(t.y - targetRadius)
                val narrow = 6.dp.toPx() + 10.dp.toPx() * proximity
                val wide = targetRadius * (1.2f + 0.8f * proximity)
                val beam = Path().apply {
                    moveTo(t.x - narrow / 2f, top)
                    lineTo(t.x + narrow / 2f, top)
                    lineTo(t.x + wide / 2f, t.y)
                    lineTo(t.x - wide / 2f, t.y)
                    close()
                }
                drawPath(
                    beam,
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.06f + 0.3f * proximity)),
                        startY = top,
                        endY = t.y,
                    ),
                )
            }
            drawCircle(
                Brush.radialGradient(listOf(Color.White.copy(alpha = 0.05f + 0.3f * proximity), Color.Transparent), center = t, radius = targetRadius * 3.2f),
                radius = targetRadius * 3.2f,
                center = t,
            )
            val r = targetRadius * (1f + 0.22f * braced)
            drawCircle(Color(0xFF101013).copy(alpha = 0.9f), radius = r, center = t)
            drawCircle(Color.White.copy(alpha = 0.25f + 0.65f * proximity), radius = r, center = t, style = Stroke(1.5.dp.toPx()))
        }
        val iconSize = with(density) { 22.dp.toPx() }
        Icon(
            Icons.Filled.Close,
            stringResource(R.string.overlay_dismiss_target),
            tint = Color.White,
            modifier = Modifier
                .offset { IntOffset((state.target.x - iconSize / 2f).roundToInt(), (state.target.y - iconSize / 2f).roundToInt()) }
                .size(22.dp)
                .graphicsLayer { alpha = chrome * (1f - 0.6f * braced) },
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
            Orb(mood)
        }
    }
}

// ------------------------------------------------------------------ Hold menu

internal enum class MenuAction { Hide, OpenChat, Talk }

internal class MenuState(val centers: List<Pt>) {
    var hover by mutableIntStateOf(-1)
}

internal val MENU_BUTTON = 52.dp

@Composable
internal fun HoldMenu(state: MenuState, onAction: (MenuAction) -> Unit, onDismiss: () -> Unit) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(180, easing = FastOutSlowInEasing)) }
    val density = LocalDensity.current
    val button = with(density) { MENU_BUTTON.toPx() }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = appear.value }
            .background(Color.Black.copy(alpha = 0.28f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    ) {
        val items = listOf(
            Triple(MenuAction.Hide, Icons.Filled.Close, R.string.overlay_hide),
            Triple(MenuAction.OpenChat, OverlayIcons.Chat, R.string.overlay_open_chat),
            Triple(MenuAction.Talk, AtomIcons.Mic, R.string.overlay_talk),
        )
        items.forEachIndexed { i, (action, icon, label) ->
            val c = state.centers.getOrNull(i) ?: return@forEachIndexed
            MenuButton(
                icon = icon,
                label = stringResource(label),
                hovered = state.hover == i,
                modifier = Modifier
                    .offset { IntOffset((c.x - button / 2f).roundToInt(), (c.y - button / 2f).roundToInt()) }
                    .graphicsLayer {
                        val s = 0.6f + 0.4f * appear.value
                        scaleX = s
                        scaleY = s
                    },
                onClick = { onAction(action) },
            )
        }
    }
}

@Composable
private fun MenuButton(icon: ImageVector, label: String, hovered: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val lift by animateFloatAsState(if (hovered) 1.12f else 1f, tween(150), label = "hover")
    Box(
        modifier
            .size(MENU_BUTTON)
            .graphicsLayer {
                scaleX *= lift
                scaleY *= lift
            }
            .clip(CircleShape)
            .background(if (hovered) Color.White else Color(0xFF141417).copy(alpha = 0.96f))
            .drawBehind { drawCircle(Color.White.copy(alpha = 0.14f), style = Stroke(1.dp.toPx())) }
            .pointerInput(Unit) { detectTapGestures { onClick() } },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(22.dp), tint = if (hovered) Color.Black else Color.White)
    }
}
