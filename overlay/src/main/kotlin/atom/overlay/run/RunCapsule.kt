package atom.overlay.run

import android.os.Build
import android.view.RoundedCorner
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import atom.overlay.Overlay
import atom.overlay.R
import atom.overlay.bubble.Orb
import atom.overlay.ui.ApprovalCard
import atom.overlay.ui.Glass
import atom.overlay.ui.OverlayIcons
import atom.overlay.ui.PillButton
import atom.overlay.ui.RoundIcon
import atom.overlay.ui.glass
import atom.ui.feature.RunPhase
import atom.ui.feature.RunSource
import atom.ui.theme.AtomIcons
import atom.ui.theme.AtomMark
import kotlinx.coroutines.delay

/** Status pill → controls → result card, in one small window at the top of the screen. */
@Composable
internal fun RunCapsule() {
    val view = RunOverlays.view
    val result = RunOverlays.result
    val mode = when {
        view != null && (RunOverlays.expanded || view.pending != null) -> Mode.Controls
        view != null -> Mode.Pill
        result != null -> Mode.Result
        else -> Mode.None
    }
    if (mode == Mode.Controls && !RunOverlays.steering) {
        LaunchedEffect(view?.conversationId, RunOverlays.expanded) {
            delay(AUTO_COLLAPSE_MS)
            if (!RunOverlays.steering && RunOverlays.view?.pending == null) RunOverlays.expanded = false
        }
    }
    AnimatedContent(
        targetState = mode,
        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) using SizeTransform(clip = false) { _, _ -> tween(0) } },
        label = "capsule",
    ) { m ->
        Box(Modifier.padding(6.dp)) {
            when (m) {
                Mode.Pill -> view?.let { Pill(it) }
                Mode.Controls -> view?.let { Controls(it) }
                Mode.Result -> result?.let { Result(it) }
                Mode.None -> Unit
            }
        }
    }
}

private enum class Mode { None, Pill, Controls, Result }

private const val AUTO_COLLAPSE_MS = 8_000L

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
private fun Pill(v: CapsuleView) {
    Row(
        Modifier
            .heightIn(min = 40.dp)
            .widthIn(max = 320.dp)
            .glass(RoundedCornerShape(50), blurred = false)
            .clickable { RunOverlays.expanded = true }
            .padding(start = 6.dp, end = 14.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Orb(v.mood, size = 28.dp)
        Spacer(Modifier.width(10.dp))
        Text(phaseLabel(v), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        if (v.step != null && v.phase == RunPhase.Running) {
            Text(
                "  ·  " + v.step,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Controls(v: CapsuleView) {
    Column(
        Modifier
            .width(340.dp)
            .glass(RoundedCornerShape(24.dp), blurred = false)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Orb(v.mood, size = 30.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(phaseLabel(v), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                val detail = when {
                    v.phase == RunPhase.Paused -> stringResource(R.string.overlay_paused_detail)
                    v.queued > 0 -> stringResource(R.string.overlay_queued, v.queued)
                    else -> v.step
                }
                if (detail != null) {
                    Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            RoundIcon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.overlay_collapse), { RunOverlays.expanded = false }, size = 34.dp)
        }
        if (v.viewfinder) {
            val run = RunOverlays.runFor(v.conversationId)
            val slot = Overlay.viewfinder
            if (run != null && slot != null) slot.Viewfinder(run, Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(16.dp)))
        }
        v.pending?.let { approval -> ApprovalCard(approval, onAnswer = RunOverlays::answer) }
        if (RunOverlays.steering) {
            SteerField()
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (v.phase == RunPhase.Paused) {
                    PillButton(stringResource(R.string.overlay_resume), onClick = { RunOverlays.resume() }, primary = true, icon = Icons.Filled.PlayArrow)
                } else {
                    PillButton(stringResource(R.string.overlay_pause), onClick = { RunOverlays.pause() }, primary = false, icon = OverlayIcons.Pause)
                }
                PillButton(stringResource(R.string.overlay_add_input), onClick = RunOverlays::beginSteer, primary = false, icon = OverlayIcons.Steer)
                PillButton(stringResource(R.string.overlay_stop), onClick = RunOverlays::stop, primary = false, icon = AtomIcons.Stop)
            }
        }
    }
}

@Composable
private fun SteerField() {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { focus.requestFocus() }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Glass.raised)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (RunOverlays.steerText.isEmpty()) {
                Text(stringResource(R.string.overlay_steer_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BasicTextField(
                value = RunOverlays.steerText,
                onValueChange = { RunOverlays.steerText = it },
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { RunOverlays.sendSteer() }),
                maxLines = 4,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        RoundIcon(
            OverlayIcons.ArrowUp,
            stringResource(R.string.overlay_send),
            RunOverlays::sendSteer,
            size = 44.dp,
            filled = RunOverlays.steerText.isNotBlank(),
            enabled = RunOverlays.steerText.isNotBlank(),
        )
    }
}

@Composable
private fun Result(r: ResultView) {
    Column(
        Modifier
            .width(340.dp)
            .glass(RoundedCornerShape(24.dp), blurred = false)
            .clickable(onClick = RunOverlays::openResult)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AtomMark(Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(if (r.ok) R.string.overlay_result_ready else R.string.overlay_result_failed),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (r.preview.isNotBlank()) {
            Text(r.preview, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (r.source == RunSource.Bubble) {
                PillButton(stringResource(R.string.overlay_open_chat), onClick = RunOverlays::openResult, primary = true)
            } else {
                PillButton(stringResource(R.string.overlay_open_atom), onClick = RunOverlays::openApp, primary = true)
            }
            PillButton(stringResource(R.string.overlay_done), onClick = RunOverlays::dismissResult, primary = false)
        }
    }
}

/** A thin white light around the display edge while the agent operates the screen. Drawn once; never animates after fading in. */
@Composable
internal fun EdgeGlow() {
    val view = LocalView.current
    val fade = remember { Animatable(0f) }
    LaunchedEffect(Unit) { fade.animateTo(1f, tween(220)) }
    val cornerPx = remember(view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat()
        } else {
            null
        }
    }
    Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }) {
        val radius = cornerPx ?: 28.dp.toPx()
        val layers = listOf(16.dp.toPx() to 0.07f, 9.dp.toPx() to 0.12f, 4.dp.toPx() to 0.3f, 1.5.dp.toPx() to 0.75f)
        for ((width, alpha) in layers) {
            val inset = width / 2f
            drawRoundRect(
                Color.White.copy(alpha = alpha),
                topLeft = Offset(inset, inset),
                size = Size(size.width - width, size.height - width),
                cornerRadius = CornerRadius((radius - inset).coerceAtLeast(0f)),
                style = Stroke(width),
            )
        }
    }
}
