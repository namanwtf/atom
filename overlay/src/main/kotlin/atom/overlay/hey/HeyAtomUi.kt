package atom.overlay.hey

import android.graphics.Rect
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import atom.overlay.ui.OverlayIcons
import atom.ui.theme.AtomIcons
import android.os.Build
import android.view.RoundedCorner
import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.Animatable
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import atom.overlay.R
import atom.overlay.run.CapsuleStatus
import atom.overlay.OverlayRuntime
import atom.overlay.ui.ApprovalCard
import atom.overlay.ui.EdgeGlowStyle
import atom.overlay.ui.drawEdgeGlow
import atom.overlay.ui.rememberDisplayCorner
import atom.ui.theme.AtomEmblem
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

// The panel's own tones come from ATOM's one edge light (atom.overlay.ui.EdgeGlow).
private val Ice = EdgeGlowStyle.ICE
private val Silver = EdgeGlowStyle.SILVER
private val SoftRed = Color(0xFFFF8E86)
private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

private const val SHIMMER_MS = 4_800

// ------------------------------------------------------------------ Edge light

/**
 * The light around the screen edges: the same light as the run glow ([drawEdgeGlow]), with listening on top. Live:
 * the sweep turns slowly round the edge and the band widens with the voice. Pulse: one bright breath. Calm: exactly
 * the run glow (still, resting sweep), so a run that takes the screen hands over to its glow without a seam. Every
 * animated value is read in the draw phase only: a still light is drawn once and requests no frames.
 */
@Composable
internal fun HeyEdgeLight() {
    val stage = HeyAtomOverlay.shown
    val motion = HeyAtomOverlay.motion
    val leaving = HeyAtomOverlay.leaving
    val glowOn by OverlayRuntime.prefs.edgeGlow.collectAsState()
    val mode = HeyMachine.glow(stage, workingGlow = glowOn)
    val cornerPx = rememberDisplayCorner()

    val appear = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(leaving, motion) {
        val target = if (leaving) 0f else 1f
        if (motion) appear.animateTo(target, tween(if (leaving) 260 else 420, easing = Emphasized)) else appear.snapTo(target)
    }
    val strength = remember { Animatable(0f) }
    LaunchedEffect(mode, motion) {
        val rest = restStrength(mode)
        if (!motion) {
            strength.snapTo(rest)
        } else if (mode == HeyGlow.Pulse) {
            strength.animateTo(1.3f, tween(220, easing = Emphasized))
            strength.animateTo(rest, tween(900, easing = Emphasized))
        } else {
            strength.animateTo(rest, tween(if (mode == HeyGlow.None) 420 else 640, easing = Emphasized))
        }
    }
    val live = motion && mode == HeyGlow.Live
    // The sweep turns while listening and then comes to rest on a whole turn: the run glow's own position.
    val turn = remember { Animatable(0f) }
    LaunchedEffect(live) {
        if (live) {
            while (true) turn.animateTo(turn.value + 1f, tween(SHIMMER_MS, easing = LinearEasing))
        } else {
            val rest = kotlin.math.ceil(turn.value)
            if (motion) turn.animateTo(rest, tween(700, easing = Emphasized)) else turn.snapTo(rest)
        }
    }
    val voice = animateFloatAsState(if (live) HeyAtomOverlay.level else 0f, tween(140), label = "voice")
    Canvas(Modifier.fillMaxSize()) {
        drawEdgeGlow(cornerPx, strength = strength.value * appear.value, voice = voice.value, turn = turn.value)
    }
}

/** Listening is the strongest; at rest the light is the run glow's working strength. */
private fun restStrength(mode: HeyGlow): Float = when (mode) {
    HeyGlow.Live -> 1f
    HeyGlow.Pulse, HeyGlow.Calm -> EdgeGlowStyle.WORKING
    HeyGlow.None -> 0f
}

// ------------------------------------------------------------------ Panel

/** The panel's window: modal (a tap outside cancels) while listening or typing, otherwise just the panel. */
@Composable
internal fun HeyPanelWindow() {
    val mode = HeyAtomOverlay.panelMode
    val leaving = HeyAtomOverlay.leaving
    val motion = HeyAtomOverlay.motion
    val enter = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(leaving, motion) {
        val target = if (leaving) 0f else 1f
        if (motion) enter.animateTo(target, tween(if (leaving) 220 else 340, easing = Emphasized)) else enter.snapTo(target)
    }
    if (mode == HeyPanel.Modal || mode == HeyPanel.Typing) {
        val density = LocalDensity.current
        // While typing the panel sits on the keyboard; otherwise above the navigation bar.
        val keyboard = if (mode == HeyPanel.Typing) keyboardInset() else 0
        val inset = with(density) { max(HeyAtomOverlay.bottomInset, keyboard.toFloat()).toDp() }
        val cancel = stringResource(R.string.hey_atom_cancel)
        Box(
            Modifier
                .fillMaxSize()
                .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = cancel, onClick = HeyAtomOverlay::cancel)
                .padding(bottom = inset),
            contentAlignment = Alignment.BottomCenter,
        ) { HeyCard(enter) }
    } else {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) { HeyCard(enter) }
    }
}

/**
 * The keyboard's height in px: the larger of the window's IME inset and what the visible display frame leaves out,
 * read on layout changes only (no polling).
 */
@Composable
private fun keyboardInset(): Int {
    val ime = WindowInsets.ime.getBottom(LocalDensity.current)
    val view = LocalView.current
    var frame by remember { mutableIntStateOf(0) }
    DisposableEffect(view) {
        val r = Rect()
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            view.getWindowVisibleDisplayFrame(r)
            frame = (view.rootView.height - r.bottom).coerceAtLeast(0)
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    return max(ime, frame)
}

private val PanelFill = Brush.verticalGradient(listOf(Color(0xFA191C21), Color(0xFC0B0C0F)))
private val PanelEdge = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.24f), Color.White.copy(alpha = 0.05f)))
private val Words = Color(0xFFF7F9FB)
private val Muted = Color(0xFFA3ACB7)

// The panel's type, in line with the chat: live words 17 sp, status 13 sp, result 15 sp, chip 12 sp. Sizes are in sp,
// so the system font scale applies once.
private val WordsStyle @Composable get() = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium)
private val BodyStyle @Composable get() = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp)
private val SmallStyle @Composable get() = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp)

@Composable
private fun HeyCard(enter: Animatable<Float, AnimationVector1D>) {
    val s = HeyAtomOverlay.shown
    val motion = HeyAtomOverlay.motion
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .graphicsLayer {
                val e = enter.value
                translationY = (1f - e) * 40.dp.toPx()
                alpha = e
            }
            .shadow(20.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(PanelFill)
            .border(1.dp, PanelEdge, shape)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = HeyAtomOverlay::tapPanel)
            .then(if (motion) Modifier.animateContentSize(tween(260, easing = Emphasized)) else Modifier)
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Header(s)
        AnimatedContent(
            targetState = s,
            contentKey = { it::class },
            transitionSpec = {
                if (motion) fadeIn(tween(240, delayMillis = 60)) togetherWith fadeOut(tween(140)) else EnterTransition.None togetherWith ExitTransition.None
            },
            label = "body",
        ) { stage -> Body(stage) }
        if (HeyMachine.showsBar(s)) {
            Spacer(Modifier.height(10.dp))
            ChatBar(s)
        }
    }
}

/** ATOM's coin at the top centre with the status under it; the close button stays at the top right. */
@Composable
private fun Header(s: HeyStage) {
    val label = when (s) {
        is HeyStage.Listening -> stringResource(R.string.hey_atom_listening)
        is HeyStage.Heard, is HeyStage.Opening -> stringResource(R.string.hey_atom_got_it)
        is HeyStage.Working -> statusLabel(s.status)
        is HeyStage.Done -> stringResource(if (s.ok) R.string.hey_atom_done else R.string.hey_atom_failed)
        is HeyStage.Typing -> stringResource(R.string.hey_atom_typing)
        HeyStage.Missed -> stringResource(R.string.hey_atom_missed_title)
        HeyStage.Hidden -> ""
    }
    val color = when {
        s is HeyStage.Done && !s.ok -> SoftRed
        s == HeyStage.Missed -> Words
        else -> Silver
    }
    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.align(Alignment.TopCenter).padding(horizontal = 44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Orb(s)
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                color = color,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                fontWeight = if (s == HeyStage.Missed) FontWeight.SemiBold else FontWeight.Medium,
                letterSpacing = 0.2.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Box(Modifier.align(Alignment.TopEnd)) { CloseButton(s) }
    }
}

@Composable
private fun Body(s: HeyStage) {
    val words = WordsStyle
    val body = BodyStyle
    val small = SmallStyle
    val center = TextAlign.Center
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp).padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when (s) {
            is HeyStage.Listening -> {
                if (s.words.isEmpty()) {
                    Text(stringResource(R.string.hey_atom_listening_hint), style = words, color = Muted, textAlign = center)
                } else {
                    // The newest words matter most: long speech keeps its end, starting with an ellipsis.
                    Text(HeyMachine.tail(s.words), style = words, color = Words.copy(alpha = 0.86f), maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = center)
                }
                ScreenChip()
            }
            is HeyStage.Heard -> {
                // The words settle: they brighten from the live grey to full white.
                val settle = remember { Animatable(Words.copy(alpha = 0.86f)) }
                LaunchedEffect(Unit) { settle.animateTo(Words, tween(360)) }
                Text(HeyMachine.tail(s.words), style = words, color = settle.value, maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = center)
                ScreenChip()
            }
            is HeyStage.Working -> {
                Text(s.words, style = small, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = center)
                Spacer(Modifier.height(6.dp))
                Text(
                    s.step ?: stringResource(R.string.hey_atom_working),
                    style = body.copy(fontWeight = FontWeight.Medium),
                    color = Words,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = center,
                )
                s.pending?.let { approval ->
                    Spacer(Modifier.height(10.dp))
                    ApprovalCard(approval, onAnswer = HeyAtomOverlay::answer)
                }
            }
            is HeyStage.Opening -> {
                Text(s.words, style = small, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = center)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.hey_atom_opening), style = body, color = Words, textAlign = center)
            }
            is HeyStage.Done -> {
                Text(s.words, style = small, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = center)
                Spacer(Modifier.height(6.dp))
                Text(
                    s.result.ifBlank { stringResource(R.string.hey_atom_done_empty) },
                    style = body,
                    color = Words,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = center,
                )
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.hey_atom_open_hint), style = small.copy(fontSize = 12.sp), color = Muted, textAlign = center)
            }
            is HeyStage.Typing -> s.previous?.let { previous ->
                Text(previous, style = small, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = center)
            }
            HeyStage.Missed -> Text(stringResource(R.string.hey_atom_missed_hint), style = body, color = Muted, textAlign = center)
            HeyStage.Hidden -> Unit
        }
    }
}

/**
 * The chat bar, like the app's composer: "Type to ATOM", Send, and a mic to speak instead. Before the user types it
 * is a pill that starts typing when tapped (listening stops and the keyboard opens); the mic listens again.
 */
@Composable
private fun ChatBar(s: HeyStage) {
    val typing = s is HeyStage.Typing
    val shape = RoundedCornerShape(24.dp)
    val listening = s is HeyStage.Listening
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                .clip(shape)
                .background(Color.White.copy(alpha = 0.07f))
                .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
                .then(if (typing) Modifier else Modifier.clickable(onClick = HeyAtomOverlay::startTyping))
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                if (s is HeyStage.Typing) {
                    TypingField(s)
                } else {
                    Text(stringResource(R.string.hey_atom_type_hint), fontSize = 15.sp, color = Muted, maxLines = 1)
                }
            }
            BarMic(active = listening, enabled = !listening)
        }
        if (typing) {
            val canSend = HeyAtomOverlay.draft.isNotBlank()
            val send = stringResource(R.string.overlay_send)
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (canSend) Words else Color.White.copy(alpha = 0.10f))
                    .clickable(enabled = canSend) { HeyAtomOverlay.send() }
                    .semantics { contentDescription = send },
                contentAlignment = Alignment.Center,
            ) {
                Icon(OverlayIcons.ArrowUp, null, Modifier.size(20.dp), tint = if (canSend) Color(0xFF0B0C0F) else Muted)
            }
        }
    }
}

/** The text field while typing: focused at once with the cursor at the end of what speech had heard. */
@Composable
private fun TypingField(stage: HeyStage.Typing) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // A new typing stage (a follow-up after a command) starts from its own draft.
    var value by remember(stage) { mutableStateOf(TextFieldValue(HeyAtomOverlay.draft, TextRange(HeyAtomOverlay.draft.length))) }
    // The window becomes focusable as typing starts; the field takes focus and the keyboard opens once it has it.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused) {
        runCatching { focus.requestFocus() }
        if (windowFocused) keyboard?.show()
    }
    Box {
        if (value.text.isEmpty()) Text(stringResource(R.string.hey_atom_type_hint), fontSize = 15.sp, color = Muted, maxLines = 1)
        BasicTextField(
            value = value,
            onValueChange = {
                value = it
                HeyAtomOverlay.draft = it.text
            },
            textStyle = TextStyle(color = Words, fontSize = 15.sp, lineHeight = 21.sp),
            cursorBrush = SolidColor(Ice),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { HeyAtomOverlay.send() }),
            maxLines = 4,
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
    }
}

/** The bar's mic: lit while listening; otherwise it starts listening again. */
@Composable
private fun BarMic(active: Boolean, enabled: Boolean) {
    val label = stringResource(R.string.hey_atom_speak)
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (active) Ice.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(enabled = enabled) { HeyAtomOverlay.listenAgain() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(AtomIcons.Mic, null, Modifier.size(18.dp), tint = if (active) Ice else Muted)
    }
}


/**
 * "Using what's on screen · Settings": the screen read at the wake word goes with this request. A tap leaves it out
 * (and a second tap takes it back) for this request only; Speech settings turns it off for good.
 */
@Composable
private fun ScreenChip() {
    val screen = HeyAtomOverlay.screen ?: return
    val on = HeyAtomOverlay.screenUse
    val label = when {
        !on -> stringResource(R.string.hey_atom_screen_chip_off)
        screen.app != null -> stringResource(R.string.hey_atom_screen_chip_app, screen.app)
        else -> stringResource(R.string.hey_atom_screen_chip)
    }
    val description = stringResource(if (on) R.string.hey_atom_screen_chip_cd_on else R.string.hey_atom_screen_chip_cd_off)
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .padding(top = 10.dp)
            .clip(shape)
            .background(if (on) Ice.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.05f))
            .border(1.dp, if (on) Ice.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.10f), shape)
            .clickable(onClick = HeyAtomOverlay::toggleScreen)
            .semantics { contentDescription = description }
            .padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScreenGlyph(if (on) Ice else Muted, crossed = !on)
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            color = if (on) Words else Muted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A small phone outline with a scan line; struck through when the screen is left out. */
@Composable
private fun ScreenGlyph(tint: Color, crossed: Boolean) {
    Canvas(Modifier.size(width = 12.dp, height = 16.dp)) {
        val stroke = 1.4.dp.toPx()
        drawRoundRect(tint, topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke), cornerRadius = CornerRadius(2.5.dp.toPx()), style = Stroke(stroke))
        val y = size.height * 0.45f
        drawLine(tint.copy(alpha = 0.8f), Offset(size.width * 0.28f, y), Offset(size.width * 0.72f, y), strokeWidth = stroke, cap = StrokeCap.Round)
        if (crossed) drawLine(tint, Offset(0f, size.height), Offset(size.width, 0f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
private fun statusLabel(status: CapsuleStatus): String = stringResource(
    when (status) {
        CapsuleStatus.Starting -> R.string.overlay_phase_starting
        CapsuleStatus.Reasoning -> R.string.overlay_phase_reasoning
        CapsuleStatus.Working -> R.string.overlay_phase_working
        CapsuleStatus.GeneratingAnswer -> R.string.overlay_phase_answering
        CapsuleStatus.Stopping -> R.string.overlay_phase_stopping
        CapsuleStatus.Paused -> R.string.overlay_phase_paused
        CapsuleStatus.WaitingForControl -> R.string.overlay_phase_waiting_control
        CapsuleStatus.WaitingForUnlock -> R.string.overlay_phase_waiting_unlock
        CapsuleStatus.NeedsApproval -> R.string.overlay_phase_approval
        CapsuleStatus.Continuing -> R.string.overlay_phase_continuing
        CapsuleStatus.InputReceived -> R.string.overlay_input_received
        CapsuleStatus.FinishingUp -> R.string.overlay_finishing_up
        CapsuleStatus.InputUnsupported -> R.string.overlay_input_unsupported
    },
)

/**
 * ATOM's coin in a small dark disc at the top of the panel. While listening it glows and gently swells with the voice
 * (the level the recognizer reports); otherwise it is still.
 */
@Composable
private fun Orb(s: HeyStage) {
    val motion = HeyAtomOverlay.motion
    val listening = s is HeyStage.Listening
    val voice = animateFloatAsState(if (listening && motion) HeyAtomOverlay.level else 0f, tween(120), label = "orb")
    val accent = if (s is HeyStage.Done && !s.ok) SoftRed else Ice
    Box(
        Modifier.size(40.dp).graphicsLayer {
            val v = voice.value
            scaleX = 1f + 0.12f * v
            scaleY = 1f + 0.12f * v
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(40.dp)) {
            val r = size.minDimension / 2f
            val v = voice.value
            val glowAlpha = if (listening) 0.30f + 0.5f * v else 0.2f
            drawCircle(
                Brush.radialGradient(listOf(accent.copy(alpha = glowAlpha), accent.copy(alpha = 0f)), center, r * (0.8f + 0.2f * v)),
                radius = r,
            )
            val disc = r * 0.72f
            drawCircle(Color(0xFF14171C), radius = disc)
            drawCircle(accent.copy(alpha = 0.45f), radius = disc, style = Stroke(1.dp.toPx()))
        }
        AtomEmblem(17.dp, tint = Color.White)
    }
}

@Composable
private fun CloseButton(s: HeyStage) {
    val label = stringResource(
        when (s) {
            is HeyStage.Listening, is HeyStage.Heard, is HeyStage.Typing -> R.string.hey_atom_cancel
            is HeyStage.Working -> R.string.hey_atom_stop
            else -> R.string.hey_atom_close
        },
    )
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.09f))
            .clickable(onClick = HeyAtomOverlay::cancel)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Close, null, Modifier.size(18.dp), tint = Words.copy(alpha = 0.85f))
    }
}
