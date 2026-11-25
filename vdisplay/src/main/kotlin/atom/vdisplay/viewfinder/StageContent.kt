package atom.vdisplay.viewfinder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import atom.ui.components.CircleIconButton
import atom.ui.components.CompactButton
import atom.vdisplay.R
import atom.vdisplay.geom.Coords
import atom.vdisplay.geom.Size
import atom.vdisplay.io.Commands
import atom.vdisplay.route.DisplayLease
import atom.vdisplay.route.DisplayRouter
import atom.vdisplay.route.DisplayUnavailable
import atom.vdisplay.route.LeaseState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val STAGE_FPS = 10

/** Stage layout: a floating card, or the whole screen in fullscreen. Monochrome on black. */
@Composable
internal fun StageContent() {
    val state by DisplayRouter.state.collectAsStateWithLifecycle()
    val lease = (state as? LeaseState.Active)?.lease?.takeIf { !it.main } ?: return
    if (Stage.fullscreen) FullStage(lease) else CardStage(lease)
}

@Composable
private fun CardStage(lease: DisplayLease) {
    val screenH = LocalConfiguration.current.screenHeightDp
    Box(Modifier.fillMaxWidth().wrapContentHeight().padding(16.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(28.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header()
            PreviewSurface(
                lease,
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = (screenH * 0.58f).dp)
                    .aspectRatio(lease.width.toFloat() / lease.height, matchHeightConstraintsFirst = true)
                    .align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(18.dp)),
            )
            Controls()
        }
    }
}

@Composable
private fun FullStage(lease: DisplayLease) {
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        PreviewSurface(lease, Modifier.weight(1f).fillMaxWidth())
        Box(Modifier.fillMaxWidth().padding(12.dp)) { Controls() }
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.vd_stage_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(
                stringResource(if (Stage.controlling) R.string.vd_stage_user else R.string.vd_stage_agent),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        CircleIconButton(Icons.Filled.KeyboardArrowDown, stringResource(R.string.vd_collapse), Stage::collapse, size = 38.dp)
    }
}

@Composable
private fun Controls() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (Stage.controlling) {
                CompactButton(stringResource(R.string.vd_continue), Stage::handBack, Modifier.weight(1f), icon = Icons.Filled.PlayArrow)
                CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.vd_back), { StageInput.back() }, size = 40.dp)
            } else {
                CompactButton(stringResource(R.string.vd_take_over), Stage::takeOver, Modifier.weight(1f), icon = VdIcons.TouchApp)
            }
            CircleIconButton(
                if (Stage.fullscreen) VdIcons.FullscreenExit else VdIcons.Fullscreen,
                stringResource(if (Stage.fullscreen) R.string.vd_exit_fullscreen else R.string.vd_fullscreen),
                Stage::toggleFullscreen,
                size = 40.dp,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactButton(stringResource(R.string.vd_present), Stage::present, Modifier.weight(1f), primary = false, enabled = !Stage.busy, icon = VdIcons.Present)
            if (Stage.fullscreen) {
                CompactButton(stringResource(R.string.vd_collapse), Stage::collapse, primary = false, icon = Icons.Filled.KeyboardArrowDown)
            }
        }
    }
}

/** Live preview; while the user has taken over, gestures on it are replayed on the hidden display. */
@Composable
private fun PreviewSurface(lease: DisplayLease, modifier: Modifier) {
    var view by remember { mutableStateOf(IntSize.Zero) }
    val slop = LocalViewConfiguration.current.touchSlop
    val input = if (Stage.controlling) {
        Modifier.pointerInput(lease.generation, view) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val start = down.position
                var last = start
                var moved = false
                var end = down.uptimeMillis
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    end = change.uptimeMillis
                    if (change.positionChange() != androidx.compose.ui.geometry.Offset.Zero) {
                        last = change.position
                        if ((last - start).getDistance() > slop) moved = true
                        change.consume()
                    }
                    if (!change.pressed) break
                }
                val ms = (end - down.uptimeMillis).coerceAtLeast(0)
                if (view.width <= 0 || view.height <= 0) return@awaitEachGesture
                val fit = Coords.fit(Size(lease.width, lease.height), Size(view.width, view.height))
                val a = Coords.viewToContent(fit, start.x, start.y) ?: return@awaitEachGesture
                val b = Coords.viewToContent(fit, last.x, last.y) ?: a
                StageInput.send(lease, a, b, moved, ms)
            }
        }
    } else {
        Modifier
    }
    Box(modifier.onSizeChanged { view = it }.then(input)) {
        LivePreview(STAGE_FPS, Modifier.fillMaxSize())
        if (Stage.controlling) {
            Box(Modifier.fillMaxSize().border(2.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(18.dp)))
        }
    }
}

/** Replays the user's gestures on the hidden display (gesture-level: tap, long press, swipe). */
internal object StageInput {
    fun send(lease: DisplayLease, a: atom.vdisplay.geom.Point, b: atom.vdisplay.geom.Point, moved: Boolean, durationMs: Long) {
        scope.launch {
            try {
                when {
                    moved -> DisplayRouter.swipe(lease, a.x, a.y, b.x, b.y, durationMs.coerceIn(80, 2_000))
                    durationMs >= 450 -> DisplayRouter.tap(lease, a.x, a.y, long = true)
                    else -> DisplayRouter.tap(lease, a.x, a.y)
                }
            } catch (e: DisplayUnavailable) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { Stage.collapse() }
            }
        }
    }

    private val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    fun back() {
        val lease = DisplayRouter.current?.takeIf { !it.main } ?: return
        scope.launch {
            runCatching { DisplayRouter.key(lease, Commands.KEY_BACK) }
        }
    }
}
