package atom.overlay.panel

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import atom.overlay.Overlay
import atom.overlay.OverlayMessage
import atom.overlay.OverlayRuntime
import atom.overlay.R
import atom.overlay.ui.ApprovalCard
import atom.overlay.ui.Glass
import atom.overlay.ui.MarkdownText
import atom.overlay.ui.OverlayIcons
import atom.overlay.ui.PillComposer
import atom.overlay.ui.RoundIcon
import atom.overlay.ui.StepsFold
import atom.overlay.ui.UserBubble
import atom.overlay.ui.glass
import atom.ui.feature.HostRun
import atom.ui.feature.RunSource
import atom.ui.theme.AtomMark
import atom.ui.components.ScreenIcons
import kotlinx.coroutines.flow.MutableStateFlow

/** Full-height glass sheet between the system bars; it shrinks to sit on the keyboard and grows back. */
@Composable
internal fun MiniChatSheet() {
    val density = LocalDensity.current
    val runsFlow = remember { OverlayRuntime.host()?.runs ?: MutableStateFlow(emptyList()) }
    val runs by runsFlow.collectAsState()
    val log by MiniChat.log.collectAsState()
    val conversation = MiniChat.conversationId
    val run = runs.firstOrNull { it.conversationId == conversation }
    val live = run?.takeIf { it.phase.active }

    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(200, easing = FastOutSlowInEasing)) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = appear.value }
            .background(Color.Black.copy(alpha = if (MiniChat.blurred) 0.18f else 0.45f))
            .pointerInput(Unit) { detectTapGestures { MiniChat.close() } },
    ) {
        val margin = with(density) { 8.dp.roundToPx() }
        val fit = PanelFit.fit(
            windowHeight = constraints.maxHeight,
            statusTop = WindowInsets.statusBars.getTop(density),
            navBottom = WindowInsets.navigationBars.getBottom(density),
            imeBottom = WindowInsets.ime.getBottom(density),
            margin = margin,
            minHeight = with(density) { 220.dp.roundToPx() },
        )
        Column(
            Modifier
                .offset { IntOffset(0, fit.top) }
                .fillMaxWidth()
                .height(with(density) { fit.height.toDp() })
                .padding(horizontal = 8.dp)
                .graphicsLayer { translationY = (1f - appear.value) * 24.dp.toPx() }
                .glass(RoundedCornerShape(28.dp), MiniChat.blurred)
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Header()
            ModelChip()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (log.isEmpty() && live == null) EmptyState() else Messages(log, live)
            }
            run?.pending?.let { approval ->
                ApprovalCard(approval, onAnswer = { MiniChat.answer(approval.id, it) }, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
            Notices()
            val context = LocalContext.current
            val voice = MiniChat.voice
            PillComposer(
                text = if (voice.listening && voice.partial.isNotEmpty()) voice.partial else MiniChat.draft,
                onText = { MiniChat.draft = it },
                onSend = { MiniChat.send(MiniChat.draft) },
                onStop = MiniChat::stop,
                running = live != null,
                listening = voice.listening,
                level = voice.level,
                micAvailable = voice.available,
                onMic = { MiniChat.listen(context, send = false) },
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun Header() {
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AtomMark(Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.overlay_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RoundIcon(Icons.Filled.Add, stringResource(R.string.overlay_new_chat), MiniChat::newChat, size = 38.dp)
            RoundIcon(OverlayIcons.Expand, stringResource(R.string.overlay_open_app), MiniChat::expand, size = 38.dp)
            RoundIcon(Icons.Filled.Close, stringResource(R.string.overlay_close), MiniChat::close, size = 38.dp)
        }
    }
}

@Composable
private fun ModelChip() {
    val context = LocalContext.current
    val label = remember { Overlay.hooks.modelLabel(RunSource.Bubble) } ?: stringResource(R.string.overlay_default_model)
    val pickable = remember { Overlay.hooks.modelPickerIntent(context, RunSource.Bubble) != null }
    Row(
        Modifier
            .padding(start = 14.dp, top = 2.dp, bottom = 6.dp)
            .clip(RoundedCornerShape(50))
            .background(Glass.raised)
            .clickable(enabled = pickable, onClick = MiniChat::openModelPicker)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.8f)))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (pickable) {
            Spacer(Modifier.width(4.dp))
            androidx.compose.material3.Icon(ScreenIcons.ChevronDown, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AtomMark(Modifier.size(64.dp))
        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.overlay_empty_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.overlay_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Newest at the bottom; the list is reversed so a streaming answer stays anchored above the composer. */
@Composable
private fun Messages(log: List<OverlayMessage>, live: HostRun?) {
    LazyColumn(
        Modifier.fillMaxSize(),
        reverseLayout = true,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
    ) {
        if (live != null) {
            item(key = "live") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StepsFold(live.steps, working = live.text.isEmpty())
                    if (live.text.isNotEmpty()) MarkdownText(live.text)
                }
            }
        }
        items(log.asReversed()) { message ->
            if (message.fromUser) UserBubble(message.text) else MarkdownText(message.text)
        }
    }
}

@Composable
private fun Notices() {
    val voice = MiniChat.voice
    val error = voice.error
    val notice = MiniChat.notice
    if (error == null && notice == null) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            error ?: notice.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (error != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.overlay_voice_settings),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = MiniChat::openVoiceSettings).padding(6.dp),
            )
        }
    }
}
