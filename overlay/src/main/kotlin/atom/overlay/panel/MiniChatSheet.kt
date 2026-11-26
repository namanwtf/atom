package atom.overlay.panel

import android.graphics.Rect
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import atom.overlay.Overlay
import atom.overlay.OverlayMessage
import atom.overlay.OverlayRuntime
import atom.overlay.R
import atom.overlay.ui.ApprovalCard
import atom.overlay.ui.Glyph
import atom.overlay.ui.GlyphIcon
import atom.overlay.ui.MarkdownText
import atom.overlay.ui.StepsFold
import atom.overlay.ui.Warm
import atom.overlay.ui.drawGlyph
import atom.overlay.window.Windows
import atom.ui.feature.HostRun
import atom.ui.feature.RunSource
import atom.ui.theme.AtomMark
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.max

/**
 * Full-height sheet between the status and navigation bars. It is top-anchored and shrinks (never slides) so the
 * composer sits on the keyboard, then grows back when the keyboard closes.
 */
@Composable
internal fun MiniChatSheet() {
    val density = LocalDensity.current
    val context = LocalContext.current
    val view = LocalView.current
    val runsFlow = remember { OverlayRuntime.host()?.runs ?: MutableStateFlow(emptyList()) }
    val runs by runsFlow.collectAsState()
    val log by MiniChat.log.collectAsState()
    val conversation = MiniChat.conversationId
    val run = runs.firstOrNull { it.conversationId == conversation }
    val live = run?.takeIf { it.phase.active }

    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(200, easing = FastOutSlowInEasing)) }

    // Keyboard. Sources: the accessibility service's keyboard window, IME insets, the visible display frame and, as a
    // last resort, an estimate. Polled quickly around focus and inset changes, then slowly once readings are stable.
    val focusManager = LocalFocusManager.current
    val configuration = LocalConfiguration.current
    val bars = remember(context, configuration) { Windows.area(context) }
    var focused by remember { mutableStateOf(false) }
    // The estimate waits for focus to settle, so a keyboard that is still animating in does not make the panel jump.
    var focusSettled by remember { mutableStateOf(false) }
    var insetsSeen by remember { mutableStateOf(false) }
    var serviceTop by remember { mutableStateOf<Int?>(null) }
    var serviceSaw by remember { mutableStateOf(false) }
    var frameKeyboard by remember { mutableStateOf<Int?>(null) }
    var windowTop by remember { mutableIntStateOf(0) }
    var seenThisFocus by remember { mutableStateOf(false) }
    val ime = WindowInsets.ime.getBottom(density)
    val currentIme by rememberUpdatedState(ime)
    SideEffect {
        if (ime > 0 && !insetsSeen) insetsSeen = true
        if (ime > 0 && focused && !seenThisFocus) seenThisFocus = true
    }
    LaunchedEffect(focused) {
        focusSettled = false
        if (focused) seenThisFocus = false
        if (focused) {
            delay(ESTIMATE_AFTER_MS)
            focusSettled = true
        }
    }
    LaunchedEffect(focused, ime > 0, bars) {
        val threshold = with(density) { FRAME_THRESHOLD.roundToPx() }
        var frameSeen = frameKeyboard != null
        var last: List<Int?>? = null
        var stable = 0
        while (true) {
            val probe = Windows.keyboardProbe()
            val at = IntArray(2)
            view.getLocationOnScreen(at)
            val frame = Rect()
            view.getWindowVisibleDisplayFrame(frame)
            val windowBottom = at[1] + view.height
            val fromFrame = KeyboardFit.frameKeyboard(windowBottom, frame.bottom, bars.bottom.toInt(), threshold, frameSeen)
            if (fromFrame != null && fromFrame > 0 || probe?.top != null) seenThisFocus = true
            if (fromFrame != null && fromFrame > 0) frameSeen = true
            if (probe?.top != null) serviceSaw = true
            serviceTop = probe?.top
            frameKeyboard = fromFrame
            windowTop = at[1]
            val reading = listOf(probe?.top, fromFrame, at[1])
            stable = if (reading == last) stable + 1 else 0
            last = reading
            if (stable == STABLE_READS && focused && seenThisFocus) {
                // A keyboard closed with Back leaves the field focused: drop focus so the panel grows back and the
                // next tap on the field starts measuring again.
                val signals = KeyboardSignals(focused, serviceTop, serviceSaw, currentIme, insetsSeen, frameKeyboard, windowTop, view.height, bars.height.toInt())
                if (KeyboardFit.closedEverywhere(signals)) {
                    Log.i(LOG_TAG, "keyboard closed with the field focused; dropping focus ($signals)")
                    focusManager.clearFocus()
                }
            }
            if (!focused && stable >= STABLE_READS) break
            delay(if (stable >= STABLE_READS) SLOW_POLL_MS else POLL_MS)
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = appear.value }
            .background(Color.Black.copy(alpha = 0.45f))
            .pointerInput(Unit) { detectTapGestures { MiniChat.close() } },
    ) {
        val keyboard = KeyboardFit.keyboard(
            KeyboardSignals(
                focused = focused && focusSettled,
                serviceTop = serviceTop,
                serviceSawKeyboard = serviceSaw,
                imeInset = ime,
                insetsWork = insetsSeen,
                frameKeyboard = frameKeyboard,
                windowTop = windowTop,
                windowHeight = constraints.maxHeight,
                displayHeight = bars.height.toInt(),
            ),
        )
        val margin = with(density) { 6.dp.roundToPx() }
        val fit = PanelFit.fit(
            windowHeight = constraints.maxHeight,
            statusTop = max(WindowInsets.statusBars.getTop(density), bars.top.toInt()),
            navBottom = max(WindowInsets.navigationBars.getBottom(density), bars.bottom.toInt()),
            imeBottom = keyboard.height,
            margin = margin,
            minHeight = with(density) { 280.dp.roundToPx() },
        )
        Column(
            Modifier
                .offset { IntOffset(0, fit.top) }
                .fillMaxWidth()
                .height(with(density) { fit.height.toDp() })
                .padding(horizontal = 6.dp)
                .graphicsLayer { translationY = (1f - appear.value) * 24.dp.toPx() }
                .clip(RoundedCornerShape(24.dp))
                .background(Warm.Background)
                .border(1.dp, Warm.Outline, RoundedCornerShape(24.dp))
                // A tap on the sheet outside the composer hands focus back (and with it any keyboard estimate).
                .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
        ) {
            Header()
            Spacer(Modifier.height(8.dp))
            ModelChip()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (log.isEmpty() && live == null) EmptyState() else Messages(log, live)
            }
            run?.pending?.let { approval ->
                ApprovalCard(approval, onAnswer = { MiniChat.answer(approval.id, it) }, modifier = Modifier.padding(vertical = 6.dp))
            }
            Notices()
            Spacer(Modifier.height(8.dp))
            Composer(running = live != null, onFocus = { focused = it })
        }
    }
}

private const val LOG_TAG = "AtomOverlay"
private const val POLL_MS = 150L
private const val SLOW_POLL_MS = 1_000L

/** About a second of identical readings counts as stable. */
private const val STABLE_READS = 6
private const val ESTIMATE_AFTER_MS = 450L

/** The visible frame must lose this much more than the navigation bar to count as a keyboard. */
private val FRAME_THRESHOLD = 80.dp

@Composable
private fun Header() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.overlay_title), color = Warm.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            HeaderButton(Glyph.Plus, stringResource(R.string.overlay_new_chat), MiniChat::newChat)
            HeaderButton(Glyph.Expand, stringResource(R.string.overlay_open_app), MiniChat::expand)
            HeaderButton(Glyph.Close, stringResource(R.string.overlay_close), MiniChat::close)
        }
    }
}

@Composable
private fun HeaderButton(glyph: Glyph, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, Color.White.copy(alpha = 0.85f), 16.dp, 1.8.dp)
    }
}

@Composable
private fun ModelChip() {
    val context = LocalContext.current
    val hooks = Overlay.hooks
    // Collected, so the chip follows a model change made while the panel is open (or since it was last shown).
    val chipFlow = remember(hooks) { hooks.modelChip(RunSource.Bubble) }
    val chip = chipFlow?.collectAsState()?.value
    val label = chip?.label?.ifBlank { null }
        ?: hooks.modelLabel(RunSource.Bubble) ?: hooks.modelLabel(RunSource.Chat) ?: stringResource(R.string.overlay_no_model)
    val pickable = remember(hooks) { hooks.modelPickerIntent(context, RunSource.Bubble) != null }
    Text(
        label,
        color = if (chip?.ready == false) Warm.Muted else Warm.Text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .widthIn(max = 260.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Warm.Card2)
            .clickable(enabled = pickable, onClick = MiniChat::openModelPicker)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(56.dp).clip(CircleShape), contentAlignment = Alignment.Center) { AtomMark(Modifier.fillMaxSize()) }
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.overlay_empty_title), color = Warm.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.overlay_empty_hint),
            color = Warm.Muted,
            fontSize = 13.sp,
            lineHeight = 18.sp,
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
        contentPadding = PaddingValues(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
    ) {
        if (live != null) {
            item(key = "live") {
                Column(Modifier.padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StepsFold(live.steps, working = live.text.isEmpty())
                    if (live.text.isNotEmpty()) MarkdownText(live.text, color = Warm.Text)
                }
            }
        }
        items(log.asReversed()) { message ->
            if (message.fromUser) UserMessage(message.text) else MarkdownText(message.text, Modifier.padding(horizontal = 2.dp), color = Warm.Text)
        }
    }
}

@Composable
private fun UserMessage(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            text,
            color = Warm.Text.copy(alpha = 0.95f),
            fontSize = 15.sp,
            lineHeight = 24.sp,
            modifier = Modifier
                .widthIn(max = 260.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Warm.UserBubble)
                .padding(horizontal = 12.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun Notices() {
    val voice = MiniChat.voice
    val error = voice.error
    val notice = MiniChat.notice
    if (error == null && notice == null) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(error ?: notice.orEmpty(), color = Warm.HideRed, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f, fill = false))
        if (error != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.overlay_voice_settings),
                color = Warm.Bolt,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = MiniChat::openVoiceSettings).padding(6.dp),
            )
        }
    }
}

/** Pill with the text field and the mic inside it, and the round send (or stop) button outside. */
@Composable
private fun Composer(running: Boolean, onFocus: (Boolean) -> Unit) {
    val context = LocalContext.current
    val voice = MiniChat.voice
    val text = if (voice.listening && voice.partial.isNotEmpty()) voice.partial else MiniChat.draft
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 46.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(Warm.Card)
                .border(1.dp, Warm.Outline, RoundedCornerShape(26.dp))
                .padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 8.dp)) {
                if (text.isEmpty()) {
                    Text(
                        stringResource(if (voice.listening) R.string.overlay_listening else R.string.overlay_composer_hint),
                        color = Warm.Muted,
                        fontSize = 14.sp,
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { MiniChat.draft = it },
                    textStyle = TextStyle(color = Warm.Text, fontSize = 14.sp, lineHeight = 20.sp),
                    cursorBrush = SolidColor(Warm.Bolt),
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth().onFocusChanged { onFocus(it.isFocused) },
                )
            }
            if (voice.available) {
                MicButton(listening = voice.listening, level = voice.level, onClick = { MiniChat.listen(context, send = false) })
            }
        }
        val canSend = text.isNotBlank()
        val stop = running && !canSend
        SendButton(canSend = canSend, stop = stop, onClick = { if (stop) MiniChat.stop() else MiniChat.send(MiniChat.draft) })
    }
}

@Composable
private fun MicButton(listening: Boolean, level: Float, onClick: () -> Unit) {
    val label = stringResource(R.string.overlay_mic)
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .drawBehind {
                if (listening) {
                    drawCircle(Warm.Bolt.copy(alpha = 0.18f + 0.3f * level.coerceIn(0f, 1f)))
                }
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(Glyph.Mic, if (listening) Warm.Bolt else Warm.Muted, 18.dp, 1.7.dp)
    }
}

@Composable
private fun SendButton(canSend: Boolean, stop: Boolean, onClick: () -> Unit, size: Dp = 46.dp) {
    val label = stringResource(if (stop) R.string.overlay_stop else R.string.overlay_send)
    val active = canSend || stop
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (canSend) Warm.Bolt else Warm.Card2)
            .clickable(enabled = active, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .drawBehind {
                if (stop) {
                    val s = 14.dp.toPx()
                    drawRoundRect(Warm.Text, Offset(center.x - s / 2f, center.y - s / 2f), Size(s, s), CornerRadius(3.dp.toPx()))
                } else {
                    val g = 18.dp.toPx()
                    drawGlyph(Glyph.ArrowUp, if (canSend) Color.White else Warm.Muted, 2.2.dp.toPx(), Offset(center.x - g / 2f, center.y - g / 2f), g)
                }
            },
    )
}
