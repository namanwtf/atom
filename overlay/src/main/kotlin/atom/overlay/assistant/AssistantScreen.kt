package atom.overlay.assistant

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import atom.overlay.Overlay
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Edge-to-edge assistant over the current app: entry light, voice-first input and a reply card that grows. */
@Composable
internal fun AssistantScreen() {
    val c = AssistantController
    val effects = effectsEnabled()
    val runsFlow = remember { OverlayRuntime.host()?.runs ?: MutableStateFlow(emptyList()) }
    val runs by runsFlow.collectAsState()
    val run = runs.firstOrNull { it.conversationId == c.conversationId }
    val live = run?.takeIf { it.phase.active }

    val rise = remember(c.entry) { Animatable(if (effects) 0f else 1f) }
    LaunchedEffect(c.entry) { rise.animateTo(1f, tween(220, delayMillis = if (effects) 120 else 0, easing = FastOutSlowInEasing)) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxCard = maxHeight * 0.78f
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)), startY = 0f))
                .pointerInput(Unit) { detectTapGestures { c.dismiss() } },
        )
        if (effects) {
            EntryHalo(c.entry)
            // The coloured wave follows the voice; it fades out about a third of a second after listening stops.
            val wave by animateFloatAsState(if (c.voice.listening) 1f else 0f, tween(if (c.voice.listening) 240 else 320), label = "wave")
            if (wave > 0f) {
                VoiceWave(
                    c.voice.level,
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(150.dp)
                        .graphicsLayer { alpha = wave },
                )
            }
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
                .graphicsLayer {
                    alpha = rise.value
                    translationY = (1f - rise.value) * 32.dp.toPx()
                },
        ) {
            ReplyCard(maxCard, live)
        }
    }
}

@Composable
private fun ReplyCard(maxHeight: androidx.compose.ui.unit.Dp, live: atom.ui.feature.HostRun?) {
    val c = AssistantController
    val density = LocalDensity.current
    var pull by remember { mutableFloatStateOf(0f) }
    val threshold = with(density) { PULL_TO_OPEN.toPx() }
    val shownPull by animateFloatAsState(pull, tween(if (pull == 0f) 180 else 0), label = "pull")
    val scroll = rememberScrollState()
    LaunchedEffect(scroll) {
        var last = 0
        snapshotFlow { scroll.maxValue }.collect { max ->
            if (scroll.value >= last - 64) scroll.scrollTo(max)
            last = max
        }
    }
    val hasConversation = c.messages.isNotEmpty() || live != null
    val chips by OverlayRuntime.prefs.suggestionChips.collectAsState()

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .graphicsLayer { translationY = -shownPull * 0.45f }
            .glass(RoundedCornerShape(28.dp), blurred = false)
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        val armed = pull >= threshold
        val barWidth by animateDpAsState(if (armed) 52.dp else 36.dp, tween(160), label = "bar")
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 38.dp)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (pull >= threshold) c.openInApp()
                            pull = 0f
                        },
                        onDragCancel = { pull = 0f },
                    ) { change, dy ->
                        change.consume()
                        pull = (pull - dy).coerceAtLeast(0f)
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .width(barWidth)
                    .height(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (armed) Color.White.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.3f)),
            )
            if (shownPull > 4f || hasConversation) {
                Text(
                    stringResource(if (armed) R.string.overlay_release_to_open else R.string.overlay_pull_to_open),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (armed) 1f else 0.7f),
                    modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
                )
            } else {
                Spacer(Modifier.height(10.dp))
            }
        }
        if (hasConversation || c.screen != null) {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(scroll)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ContextChip()
                c.messages.forEach { m ->
                    if (m.fromUser) UserBubble(m.text) else Answer(m.text)
                }
                if (live != null) {
                    StepsFold(live.steps, working = live.text.isEmpty())
                    if (live.text.isNotEmpty()) MarkdownText(live.text)
                    live.pending?.let { approval -> ApprovalCard(approval, onAnswer = { c.answer(approval.id, it) }) }
                }
                Spacer(Modifier.height(2.dp))
            }
        }
        if (!hasConversation && chips) Chips()
        InputRow(running = live != null)
        Notice()
    }
}

@Composable
private fun ContextChip() {
    val c = AssistantController
    val screen = c.screen ?: return
    val time = remember(screen.capturedAt) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(Instant.ofEpochMilli(screen.capturedAt).atZone(ZoneId.systemDefault()))
    }
    val label = if (c.includeScreen) {
        stringResource(R.string.overlay_screen_context, screen.appLabel ?: stringResource(R.string.overlay_this_screen), time)
    } else {
        stringResource(R.string.overlay_screen_context_off)
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Glass.raised)
            .clickable { c.includeScreen = !c.includeScreen }
            .padding(start = 6.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shot = c.screenshot
        if (shot != null && c.includeScreen) {
            Image(
                shot,
                null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 20.dp, height = 32.dp).clip(RoundedCornerShape(5.dp)),
            )
            Spacer(Modifier.width(8.dp))
        } else {
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (c.includeScreen) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Filled.Close, stringResource(R.string.overlay_drop_context), Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Answer(text: String) {
    val context = LocalContext.current
    val speaking by Overlay.readAloud.speaking.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MarkdownText(text)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (Overlay.readAloud.available) {
                RoundIcon(
                    OverlayIcons.Speaker,
                    stringResource(if (speaking) R.string.overlay_stop_reading else R.string.overlay_read_aloud),
                    { AssistantController.readAloud(text) },
                    size = 32.dp,
                    filled = speaking,
                )
            }
            RoundIcon(OverlayIcons.Copy, stringResource(R.string.overlay_copy), { AssistantController.copy(context, text) }, size = 32.dp)
        }
    }
}

@Composable
private fun Chips() {
    val prompts = listOf(
        R.string.overlay_chip_summarize to R.string.overlay_chip_summarize_prompt,
        R.string.overlay_chip_explain to R.string.overlay_chip_explain_prompt,
        R.string.overlay_chip_extract to R.string.overlay_chip_extract_prompt,
    )
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        prompts.forEachIndexed { i, (label, prompt) ->
            // Each chip rises 10dp and fades in, a beat after the one before.
            val rise = remember { Animatable(0f) }
            LaunchedEffect(Unit) { rise.animateTo(1f, tween(220, delayMillis = 60 * i, easing = FastOutSlowInEasing)) }
            Box(
                Modifier
                    .height(36.dp)
                    .graphicsLayer {
                        alpha = rise.value
                        translationY = (1f - rise.value) * 10.dp.toPx()
                    }
                    .clip(RoundedCornerShape(50))
                    .background(Glass.raised)
                    .clickable { AssistantController.send(context.getString(prompt)) }
                    .padding(horizontal = 15.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            }
        }
    }
}

/** Finger travel on the handle that hands the conversation to the full app. */
private val PULL_TO_OPEN = 48.dp

@Composable
private fun InputRow(running: Boolean) {
    val c = AssistantController
    val voice = c.voice
    if (c.keyboard) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            delay(80)
            runCatching { focus.requestFocus() }
        }
        PillComposer(
            text = c.draft,
            onText = { c.draft = it },
            onSend = { c.send(c.draft) },
            onStop = c::stop,
            running = running,
            listening = false,
            level = 0f,
            micAvailable = voice.available,
            onMic = { c.useVoice() },
            placeholder = stringResource(R.string.overlay_assistant_hint),
            focusRequester = focus,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp),
        )
        return
    }
    var hold by remember { mutableStateOf(HoldState.None) }
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HoldToTalkMic(voice, onHold = { hold = it })
        Text(
            when {
                hold == HoldState.Holding -> stringResource(R.string.overlay_assistant_hold_hint)
                hold == HoldState.Cancelling -> stringResource(R.string.overlay_release_to_cancel)
                voice.partial.isNotEmpty() -> voice.partial
                voice.listening -> stringResource(R.string.overlay_listening)
                running -> stringResource(R.string.overlay_working_hint)
                else -> stringResource(R.string.overlay_tap_to_speak)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = if (voice.partial.isNotEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (running) {
            RoundIcon(atom.ui.theme.AtomIcons.Stop, stringResource(R.string.overlay_stop), c::stop, size = 40.dp)
        }
        RoundIcon(OverlayIcons.Keyboard, stringResource(R.string.overlay_use_keyboard), c::useKeyboard, size = 40.dp)
    }
}

@Composable
private fun Notice() {
    val c = AssistantController
    val error = c.voice.error
    val notice = c.notice
    if (error == null && notice == null) return
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
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
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = c::openVoiceSettings).padding(6.dp),
            )
        }
    }
}
