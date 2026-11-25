package atom.overlay.run

import android.os.Build
import android.view.RoundedCorner
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import atom.overlay.Overlay
import atom.overlay.R
import atom.overlay.ui.ApprovalCard
import atom.overlay.ui.OverlayIcons
import atom.overlay.ui.PillButton
import atom.ui.feature.RunPhase
import atom.ui.feature.RunSource
import atom.ui.theme.AtomIcons
import kotlinx.coroutines.delay

private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val Amber = Color(0xFFFF9F0A)
private val Green = Color(0xFF34C759)

/** The status capsule at the top centre while a run uses the screen; tap it for the controls. */
@Composable
internal fun RunCapsule() {
    val view = RunOverlays.view ?: return
    if (RunOverlays.expanded && !RunOverlays.steering) {
        LaunchedEffect(view.conversationId, RunOverlays.expanded) {
            delay(AUTO_COLLAPSE_MS)
            if (!RunOverlays.steering && RunOverlays.view?.pending == null) RunOverlays.expanded = false
        }
    }
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(260, easing = Emphasized)) }
    val expanded = RunOverlays.expanded || view.pending != null
    val stage = expanded && view.viewfinder
    val width by animateDpAsState(
        when {
            stage -> 296.dp
            view.viewfinder -> 264.dp
            else -> 248.dp
        },
        tween(260, easing = Emphasized),
        label = "width",
    )
    val corner = if (stage) 28.dp else 22.dp
    val accent = phaseAccent(view)
    val shape = RoundedCornerShape(corner)
    Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(
            Modifier
                .width(width)
                .graphicsLayer {
                    translationY = (1f - enter.value) * -48.dp.toPx()
                    alpha = (enter.value * 1.45f).coerceAtMost(1f)
                }
                .shadow(10.dp, shape, ambientColor = accent, spotColor = accent)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f))
                .clickable(enabled = !expanded) { RunOverlays.expanded = true },
        ) {
            CollapsedRow(view, accent, expanded)
            AnimatedVisibility(
                expanded,
                enter = expandVertically(tween(260, easing = Emphasized)) + fadeIn(tween(260, easing = Emphasized)),
                exit = shrinkVertically(tween(200, easing = Emphasized)) + fadeOut(tween(160)),
            ) {
                Controls(view, stage)
            }
        }
    }
}

private const val AUTO_COLLAPSE_MS = 8_000L

@Composable
private fun phaseAccent(v: CapsuleView): Color = when {
    v.phase == RunPhase.Paused -> Amber
    else -> MaterialTheme.colorScheme.primary
}

@Composable
private fun phaseLabel(v: CapsuleView): String = stringResource(
    when {
        v.pending != null -> R.string.overlay_phase_approval
        v.phase == RunPhase.Starting -> R.string.overlay_phase_starting
        v.phase == RunPhase.Paused -> R.string.overlay_phase_paused
        v.phase == RunPhase.WaitingForControl -> R.string.overlay_phase_waiting_control
        v.phase == RunPhase.WaitingForApproval -> R.string.overlay_phase_approval
        else -> R.string.overlay_phase_working
    },
)

@Composable
private fun CollapsedRow(v: CapsuleView, accent: Color, expanded: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(start = 13.dp, end = 16.dp, top = 9.5.dp, bottom = 9.5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhaseIndicator(
            when {
                v.phase == RunPhase.Paused -> Phase.Paused
                v.pending != null -> Phase.Waiting
                else -> Phase.Running
            },
            accent,
        )
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            AnimatedContent(
                targetState = phaseLabel(v),
                transitionSpec = {
                    (slideInVertically(tween(220)) { it / 2 } + fadeIn(tween(220))) togetherWith
                        (slideOutVertically(tween(180)) { -it / 2 } + fadeOut(tween(180)))
                },
                label = "status",
            ) { label ->
                Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
            val thought = if (v.phase == RunPhase.Paused) stringResource(R.string.overlay_paused_detail) else v.step
            AnimatedContent(targetState = thought, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(220)) }, label = "thought") { t ->
                if (t != null) {
                    Text(
                        t,
                        color = Color(0xFF9A9A9E),
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.StartEllipsis,
                    )
                }
            }
        }
        if (v.viewfinder && !expanded) {
            val run = RunOverlays.runFor(v.conversationId)
            val slot = Overlay.viewfinder
            if (run != null && slot != null) {
                Spacer(Modifier.width(10.dp))
                slot.Viewfinder(run, Modifier.size(width = 18.dp, height = 32.dp).clip(RoundedCornerShape(4.dp)))
            }
        }
    }
}

private enum class Phase { Running, Paused, Waiting, Finished, Failed }

/** 14dp: a spinning sweep arc round a breathing dot while running; a ring when paused; a dot when finished. */
@Composable
private fun PhaseIndicator(phase: Phase, accent: Color) {
    val error = MaterialTheme.colorScheme.error
    when (phase) {
        Phase.Running -> {
            val t = rememberInfiniteTransition(label = "phase")
            val turn by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "turn")
            val dot by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dot")
            Canvas(Modifier.size(14.dp)) {
                drawCircle(accent.copy(alpha = 0.18f))
                val stroke = 1.8.dp.toPx()
                rotate(turn) {
                    drawArc(
                        Brush.sweepGradient(listOf(accent.copy(alpha = 0f), accent), center = center),
                        0f,
                        300f,
                        false,
                        Offset(stroke / 2f, stroke / 2f),
                        Size(size.width - stroke, size.height - stroke),
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
                drawCircle(accent, radius = size.minDimension * 0.16f * dot)
            }
        }
        Phase.Paused, Phase.Waiting -> Canvas(Modifier.size(14.dp)) {
            val stroke = 2.dp.toPx()
            drawCircle(accent, radius = size.minDimension / 2f - stroke / 2f, style = Stroke(stroke))
            if (phase == Phase.Waiting) drawCircle(accent, radius = size.minDimension * 0.14f)
        }
        Phase.Finished -> Canvas(Modifier.size(14.dp)) { drawCircle(Green, radius = size.minDimension * 0.36f) }
        Phase.Failed -> Canvas(Modifier.size(14.dp)) { drawCircle(error, radius = size.minDimension * 0.36f) }
    }
}

@Composable
private fun Controls(v: CapsuleView, stage: Boolean) {
    Column(Modifier.fillMaxWidth().padding(start = 13.dp, end = 13.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (stage) {
            val run = RunOverlays.runFor(v.conversationId)
            val slot = Overlay.viewfinder
            if (run != null && slot != null) slot.Viewfinder(run, Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(16.dp)))
        }
        v.pending?.let { approval -> ApprovalCard(approval, onAnswer = RunOverlays::answer) }
        if (RunOverlays.steering) {
            SteerField()
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
                val c = MaterialTheme.colorScheme
                ControlButton(OverlayIcons.Steer, stringResource(R.string.overlay_add_input), c.onSurface, RunOverlays::beginSteer)
                if (v.phase == RunPhase.Paused) {
                    ControlButton(Icons.Filled.PlayArrow, stringResource(R.string.overlay_resume), c.primary) { RunOverlays.resume() }
                } else {
                    ControlButton(OverlayIcons.Pause, stringResource(R.string.overlay_pause), c.onSurface) { RunOverlays.pause() }
                }
                ControlButton(AtomIcons.Stop, stringResource(R.string.overlay_stop), c.error, RunOverlays::stop)
            }
        }
    }
}

@Composable
private fun ControlButton(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(width = 40.dp, height = 34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(17.dp), tint = tint)
    }
}

@Composable
private fun SteerField() {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { focus.requestFocus() }
    }
    val c = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp, max = 112.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(c.surfaceContainerHigh)
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            if (RunOverlays.steerText.isEmpty()) {
                Text(stringResource(R.string.overlay_steer_hint), fontSize = 14.sp, color = c.onSurfaceVariant)
            }
            BasicTextField(
                value = RunOverlays.steerText,
                onValueChange = { RunOverlays.steerText = it },
                textStyle = TextStyle(color = c.onSurface, fontSize = 14.sp),
                cursorBrush = SolidColor(c.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { RunOverlays.sendSteer() }),
                maxLines = 4,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextAction(stringResource(R.string.overlay_cancel), c.onSurfaceVariant, RunOverlays::endSteer)
            Spacer(Modifier.width(4.dp))
            TextAction(stringResource(R.string.overlay_send), if (RunOverlays.steerText.isNotBlank()) c.primary else c.onSurfaceVariant, RunOverlays::sendSteer)
        }
    }
}

@Composable
private fun TextAction(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text,
        color = color,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

// ------------------------------------------------------------------ Result card

/** After a run: rises from the bottom; swipe down, Done or the timeout dismiss it. */
@Composable
internal fun ResultCard() {
    val r = RunOverlays.result ?: return
    val locked = RunOverlays.locked
    val context = LocalContext.current
    val density = LocalDensity.current
    val rise = remember(r) { Animatable(0f) }
    LaunchedEffect(r) { rise.animateTo(1f, tween(260, easing = Emphasized)) }
    var drag by remember(r) { mutableFloatStateOf(0f) }
    val threshold = with(density) { 96.dp.toPx() }
    val c = MaterialTheme.colorScheme
    val accent = if (r.ok) Green else c.error
    Column(
        Modifier
            .padding(10.dp)
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .graphicsLayer {
                translationY = (1f - rise.value) * 120.dp.toPx() + drag
                alpha = rise.value
            }
            .shadow(10.dp, RoundedCornerShape(24.dp), ambientColor = accent, spotColor = accent)
            .clip(RoundedCornerShape(24.dp))
            .background(c.surfaceContainer)
            .pointerInput(r) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (drag >= threshold) RunOverlays.dismissResult() else drag = 0f
                    },
                    onDragCancel = { drag = 0f },
                ) { change, dy ->
                    change.consume()
                    drag = (drag + dy).coerceAtLeast(0f)
                }
            }
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(50)).background(c.onSurfaceVariant.copy(alpha = 0.4f)))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhaseIndicator(if (r.ok) Phase.Finished else Phase.Failed, accent)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(
                    when {
                        !r.ok -> R.string.overlay_result_failed
                        locked -> R.string.overlay_result_ready_locked
                        else -> R.string.overlay_result_ready
                    },
                ),
                color = accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(accent.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            if (r.text.isNotBlank() && !locked) RoundCopy(onClick = { RunOverlays.copy(context, r.text) })
        }
        // Over the lock screen only the outcome shows: no answer text, no Copy, nothing that opens the app.
        if (r.text.isNotBlank() && !locked) {
            Text(
                r.text,
                style = MaterialTheme.typography.bodyMedium,
                color = c.onSurface,
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(stringResource(R.string.overlay_done), onClick = RunOverlays::dismissResult, primary = false)
            if (locked) return@Row
            if (r.source == RunSource.Bubble) {
                PillButton(stringResource(R.string.overlay_open_chat), onClick = RunOverlays::openResult, primary = true)
            } else {
                PillButton(stringResource(R.string.overlay_open_atom), onClick = RunOverlays::openApp, primary = true)
            }
            if (r.usedScreen) {
                PillButton(stringResource(R.string.overlay_open_screen), onClick = RunOverlays::openScreen, primary = false)
            }
        }
    }
}

@Composable
private fun RoundCopy(onClick: () -> Unit, size: Dp = 44.dp) {
    val label = stringResource(R.string.overlay_copy)
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(OverlayIcons.Copy, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
    }
}

// ------------------------------------------------------------------ Edge glow

/**
 * A 3dp line plus a soft halo that follows the display's rounded corners while the agent operates the screen,
 * in a blue → violet → pink → amber sweep; a still amber outline while paused. Drawn once after fading in: it
 * requests no frames while the run goes on.
 */
@Composable
internal fun EdgeGlow(paused: Boolean) {
    val view = LocalView.current
    val fade = remember { Animatable(0f) }
    LaunchedEffect(Unit) { fade.animateTo(1f, tween(420)) }
    val cornerPx = remember(view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat()
        } else {
            null
        }
    }
    Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }) {
        val radius = cornerPx ?: 28.dp.toPx()
        val inset0 = 1.5.dp.toPx()
        val brush = if (paused) SolidColor(Amber.copy(alpha = 0.55f)) else Brush.sweepGradient(GLOW, center = center)
        val layers = if (paused) {
            listOf(3.dp.toPx() to 1f)
        } else {
            listOf(14.dp.toPx() to 0.12f, 8.dp.toPx() to 0.22f, 3.dp.toPx() to 0.95f)
        }
        for ((width, alpha) in layers) {
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
    }
}

private val GLOW = listOf(Color(0xFF7AB8FF), Color(0xFFB59CFF), Color(0xFFFF9EC7), Color(0xFFFFC98A), Color(0xFF7AB8FF))
