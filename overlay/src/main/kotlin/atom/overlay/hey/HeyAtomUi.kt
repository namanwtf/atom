package atom.overlay.hey

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import atom.overlay.OverlayMessage
import atom.overlay.OverlayRuntime
import atom.overlay.R
import atom.overlay.run.CapsuleStatus
import atom.overlay.ui.ApprovalCard
import atom.overlay.ui.EdgeGlowStyle
import atom.overlay.ui.OverlayIcons
import atom.overlay.ui.drawComet
import atom.overlay.ui.drawEdgeGlow
import atom.overlay.ui.drawWave
import atom.overlay.ui.edgePath
import atom.overlay.ui.rememberDisplayCorner
import atom.ui.components.AtomGlass
import atom.ui.theme.AtomEmblem
import atom.ui.theme.AtomIcons
import atom.ui.theme.AtomTheme
import atom.ui.theme.ThemeMode
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

// ------------------------------------------------------------------ Edge light

/**
 * The light around the screen edges: the same light as the run glow ([drawEdgeGlow]), with listening on top.
 *
 * Live (listening, at most the capture's 10 s): two bright comets chase each other round the whole edge, one lap
 * every [HeyMotion.LAP_MS]; each syllable (a rise in loudness, or new words) sends a wave rippling inward from the
 * edge; the band breathes with the voice. Pulse: one bright breath ("Got it"). Calm: exactly the run glow, still,
 * so a run that takes the screen hands over to its glow without a seam. Faint: a still line while the answer stays
 * open. Frames run only while listening or while a wave finishes; every other state is drawn once.
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
    val voice = animateFloatAsState(if (live) HeyAtomOverlay.level else 0f, tween(110), label = "voice")
    val comets = remember { Animatable(0f) }
    LaunchedEffect(live) { comets.animateTo(if (live) 1f else 0f, tween(if (live) 300 else 420)) }

    // One frame clock for the comets and the waves, ticking only while listening or while a wave is still out.
    val waves = remember { HeyWaves() }
    var clock by remember { mutableLongStateOf(0L) }
    var started by remember { mutableLongStateOf(0L) }
    LaunchedEffect(live) {
        if (live) {
            withFrameMillis { clock = it }
            started = clock
            launch { snapshotFlow { HeyAtomOverlay.level }.collect { waves.onLevel(it, clock) } }
            launch { snapshotFlow { HeyAtomOverlay.wordsTick }.drop(1).collect { waves.onWords(clock) } }
        }
        while (live || waves.prune(clock)) withFrameMillis { clock = it }
    }

    Canvas(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val d = density
                val path = edgePath(size.width, size.height, cornerPx ?: (EdgeGlowStyle.FALLBACK_CORNER_DP * d), 2.2f * d)
                val measure = PathMeasure().apply { setPath(path, true) }
                onDrawBehind {
                    val shown = appear.value
                    val v = voice.value
                    drawEdgeGlow(cornerPx, strength = strength.value * shown * (1f + 0.3f * v), voice = v)
                    val now = clock
                    for (w in waves.waves) {
                        val p = HeyMotion.waveProgress(w, now) ?: continue
                        drawWave(cornerPx, p, w.strength * shown, HeyMotion.WAVE_DP)
                    }
                    val c = comets.value * shown
                    if (c > 0.01f) {
                        val elapsed = now - started
                        for (i in 0 until 2) drawComet(measure, HeyMotion.cometHead(elapsed, i), HeyMotion.COMET_SHARE, c, v)
                    }
                }
            },
    ) {}
}

/** Listening is the strongest; at rest the light is the run glow's working strength; with the answer, faint. */
private fun restStrength(mode: HeyGlow): Float = when (mode) {
    HeyGlow.Live -> 1f
    HeyGlow.Pulse, HeyGlow.Calm -> EdgeGlowStyle.WORKING
    HeyGlow.Faint -> 0.3f
    HeyGlow.None -> 0f
}

// ------------------------------------------------------------------ Scrim

/** The transparent layer under the card while it is modal: a tap anywhere on it closes the panel. */
@Composable
internal fun HeyScrim() {
    val close = stringResource(R.string.hey_atom_close)
    Box(
        Modifier
            .fillMaxSize()
            .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = close, onClick = HeyAtomOverlay::close),
    )
}

// ------------------------------------------------------------------ Card

/** The card's colours for a light or a dark glass. */
internal class HeyPalette(val dark: Boolean) {
    val words = if (dark) Color(0xFFF7F9FB) else Color(0xFF101318)
    val muted = if (dark) Color(0xFFA3ACB7) else Color(0xFF555E69)
    val label = if (dark) EdgeGlowStyle.SILVER else Color(0xFF39424D)
    val accent = if (dark) EdgeGlowStyle.ICE else Color(0xFF2E6BC6)
    val raised = if (dark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f)
    val hairline = if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.10f)
    val edge = if (dark) {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.24f), Color.White.copy(alpha = 0.05f)))
    } else {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.9f), Color.Black.copy(alpha = 0.08f)))
    }
    val send = words
    val onSend = if (dark) Color(0xFF0B0C0F) else Color.White
    val error = if (dark) Color(0xFFFF8E86) else Color(0xFFC5221F)
}

private val LocalHey = staticCompositionLocalOf { HeyPalette(dark = true) }

/** The card window's content: ATOM's glass (light or dark with the system) under the panel. */
@Composable
internal fun HeyCardWindow() {
    val dark = HeyAtomOverlay.dark
    AtomTheme(if (dark) ThemeMode.Dark else ThemeMode.Light) {
        CompositionLocalProvider(LocalHey provides remember(dark) { HeyPalette(dark) }) { HeyCard() }
    }
}

// The panel's type, in line with the chat: live words 17 sp, status 13 sp, result 15 sp, chip 12 sp. Sizes are in sp,
// so the system font scale applies once.
private val WordsStyle @Composable get() = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium)
private val BodyStyle @Composable get() = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp)
private val SmallStyle @Composable get() = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp)

@Composable
private fun HeyCard() {
    val s = HeyAtomOverlay.shown
    val motion = HeyAtomOverlay.motion
    val leaving = HeyAtomOverlay.leaving
    val h = LocalHey.current
    val shape = RoundedCornerShape(HeyGlass.CORNER_DP.dp)
    val enter = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(leaving, motion) {
        val target = if (leaving) 0f else 1f
        if (motion) enter.animateTo(target, tween(if (leaving) 220 else 300, easing = Emphasized)) else enter.snapTo(target)
    }
    // A swipe down closes the panel: the content follows the finger a little and fades.
    val density = LocalDensity.current
    val threshold = with(density) { 72.dp.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val pull = (drag / threshold).coerceIn(0f, 1f)
                translationY = pull * 18.dp.toPx() + (1f - enter.value) * 24.dp.toPx()
                alpha = enter.value * (1f - 0.6f * pull)
            }
            .clip(shape)
            .border(1.dp, h.edge, shape)
            .then(if (motion) Modifier.animateContentSize(tween(260, easing = Emphasized)) else Modifier)
            .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.fillMaxWidth().pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { if (drag >= threshold) HeyAtomOverlay.close() else drag = 0f },
                    onDragCancel = { drag = 0f },
                ) { change, dy ->
                    change.consume()
                    drag = (drag + dy).coerceAtLeast(0f)
                }
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.padding(bottom = 4.dp).size(width = 34.dp, height = 4.dp).clip(RoundedCornerShape(50)).background(h.muted.copy(alpha = 0.45f)))
            Header(s)
        }
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
    val h = LocalHey.current
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
        s is HeyStage.Done && !s.ok -> h.error
        s == HeyStage.Missed -> h.words
        else -> h.label
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
    val h = LocalHey.current
    val words = WordsStyle
    val body = BodyStyle
    val small = SmallStyle
    val center = TextAlign.Center
    val turns = HeyAtomOverlay.turns
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp).padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when (s) {
            is HeyStage.Listening -> {
                if (s.continueIn != null && turns.isNotEmpty()) Turns(turns)
                if (s.words.isEmpty()) {
                    Text(stringResource(R.string.hey_atom_listening_hint), style = words, color = h.muted, textAlign = center)
                } else {
                    // The newest words matter most: long speech keeps its end, starting with an ellipsis.
                    Text(HeyMachine.tail(s.words), style = words, color = h.words.copy(alpha = 0.86f), maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = center)
                }
                if (s.continueIn == null) ScreenChip()
            }
            is HeyStage.Heard -> {
                // The words settle: they brighten from the live grey to full strength.
                val settle = remember { Animatable(0.86f) }
                LaunchedEffect(Unit) { settle.animateTo(1f, tween(360)) }
                Text(HeyMachine.tail(s.words), style = words, color = h.words.copy(alpha = settle.value), maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = center)
                if (s.continueIn == null) ScreenChip()
            }
            is HeyStage.Working -> {
                if (turns.isNotEmpty()) Turns(turns + OverlayMessage(fromUser = true, text = s.words)) else {
                    Text(s.words, style = small, color = h.muted, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = center)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    s.step ?: stringResource(R.string.hey_atom_working),
                    style = body.copy(fontWeight = FontWeight.Medium),
                    color = h.words,
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
                Text(s.words, style = small, color = h.muted, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = center)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.hey_atom_opening), style = body, color = h.words, textAlign = center)
            }
            is HeyStage.Done -> {
                if (turns.isNotEmpty()) {
                    Turns(turns)
                } else {
                    Text(s.words, style = small, color = h.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = center)
                    Spacer(Modifier.height(6.dp))
                    Text(s.result.ifBlank { stringResource(R.string.hey_atom_done_empty) }, style = body, color = h.words, maxLines = 4, overflow = TextOverflow.Ellipsis, textAlign = center)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.hey_atom_open_full),
                    style = small.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    color = h.accent,
                    textAlign = center,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = HeyAtomOverlay::tapPanel).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            is HeyStage.Typing -> if (s.continueIn != null && turns.isNotEmpty()) {
                Turns(turns)
            } else {
                s.previous?.let { previous -> Text(previous, style = small, color = h.muted, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = center) }
            }
            HeyStage.Missed -> Text(stringResource(R.string.hey_atom_missed_hint), style = body, color = h.muted, textAlign = center)
            HeyStage.Hidden -> Unit
        }
    }
}

/**
 * The conversation's last turns, newest at the bottom: the user's words on the right in a soft bubble, ATOM's answers
 * on the left. Scrolls inside at most 45% of the screen, kept at the newest turn.
 */
@Composable
private fun Turns(turns: List<OverlayMessage>) {
    val h = LocalHey.current
    val density = LocalDensity.current
    val cap = with(density) { (HeyAtomOverlay.screenHeight * 0.45f).toDp() }.coerceAtLeast(160.dp)
    val scroll = rememberScrollState()
    LaunchedEffect(turns.size, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Column(
        Modifier.fillMaxWidth().heightIn(max = cap).verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (turn in turns) {
            if (turn.fromUser) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    Text(
                        turn.text,
                        style = SmallStyle.copy(fontSize = 14.sp, lineHeight = 19.sp),
                        color = h.words.copy(alpha = 0.82f),
                        modifier = Modifier
                            .widthIn(max = 300.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(h.raised)
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            } else {
                Text(turn.text, style = BodyStyle, color = h.words, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp))
            }
        }
    }
}

/**
 * The chat bar, like the app's composer: "Type to ATOM", Send, and a mic to speak instead. Before the user types it
 * is a pill that starts typing when tapped (listening stops and the keyboard opens); the mic listens again.
 */
@Composable
private fun ChatBar(s: HeyStage) {
    val h = LocalHey.current
    val typing = s is HeyStage.Typing
    val shape = RoundedCornerShape(24.dp)
    val listening = s is HeyStage.Listening
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                .clip(shape)
                .background(h.raised)
                .border(1.dp, h.hairline, shape)
                .then(if (typing) Modifier else Modifier.clickable(onClick = HeyAtomOverlay::startTyping))
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                if (s is HeyStage.Typing) {
                    TypingField(s)
                } else {
                    Text(stringResource(R.string.hey_atom_type_hint), fontSize = 15.sp, color = h.muted, maxLines = 1)
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
                    .background(if (canSend) h.send else h.raised)
                    .clickable(enabled = canSend) { HeyAtomOverlay.send() }
                    .semantics { contentDescription = send },
                contentAlignment = Alignment.Center,
            ) {
                Icon(OverlayIcons.ArrowUp, null, Modifier.size(20.dp), tint = if (canSend) h.onSend else h.muted)
            }
        }
    }
}

/** The text field while typing: focused at once with the cursor at the end of what speech had heard. */
@Composable
private fun TypingField(stage: HeyStage.Typing) {
    val h = LocalHey.current
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // A new typing stage (a follow-up after a command) starts from its own draft.
    var value by remember(stage) { mutableStateOf(TextFieldValue(HeyAtomOverlay.draft, TextRange(HeyAtomOverlay.draft.length))) }
    // The card becomes focusable as typing starts; the field takes focus and the keyboard opens once it has it.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused) {
        runCatching { focus.requestFocus() }
        if (windowFocused) keyboard?.show()
    }
    Box {
        if (value.text.isEmpty()) Text(stringResource(R.string.hey_atom_type_hint), fontSize = 15.sp, color = h.muted, maxLines = 1)
        BasicTextField(
            value = value,
            onValueChange = {
                value = it
                HeyAtomOverlay.draft = it.text
            },
            textStyle = TextStyle(color = h.words, fontSize = 15.sp, lineHeight = 21.sp),
            cursorBrush = SolidColor(h.accent),
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
    val h = LocalHey.current
    val label = stringResource(R.string.hey_atom_speak)
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (active) h.accent.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(enabled = enabled) { HeyAtomOverlay.listenAgain() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(AtomIcons.Mic, null, Modifier.size(18.dp), tint = if (active) h.accent else h.muted)
    }
}

/**
 * "Using what's on screen · Settings": the screen read at the wake word goes with this request. A tap leaves it out
 * (and a second tap takes it back) for this request only; Speech settings turns it off for good.
 */
@Composable
private fun ScreenChip() {
    val h = LocalHey.current
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
            .background(if (on) h.accent.copy(alpha = 0.12f) else h.raised)
            .border(1.dp, if (on) h.accent.copy(alpha = 0.3f) else h.hairline, shape)
            .clickable(onClick = HeyAtomOverlay::toggleScreen)
            .semantics { contentDescription = description }
            .padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScreenGlyph(if (on) h.accent else h.muted, crossed = !on)
        Spacer(Modifier.width(8.dp))
        Text(label, color = if (on) h.words else h.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    val h = LocalHey.current
    val motion = HeyAtomOverlay.motion
    val listening = s is HeyStage.Listening
    val voice = animateFloatAsState(if (listening && motion) HeyAtomOverlay.level else 0f, tween(120), label = "orb")
    val accent = if (s is HeyStage.Done && !s.ok) h.error else EdgeGlowStyle.ICE
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
    val h = LocalHey.current
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
            .background(h.raised)
            .clickable(onClick = HeyAtomOverlay::cancel)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Close, null, Modifier.size(18.dp), tint = h.words.copy(alpha = 0.85f))
    }
}

/** ATOM's glass for the card, from the app's one glass style ([AtomGlass]). */
internal object HeyGlass {
    const val CORNER_DP = 28f
    private const val DARK_BASE = 0xFF0B0C0F.toInt()
    private const val LIGHT_BASE = 0xFFF4F6F9.toInt()

    /** The card's tint (ARGB): translucent over a blur, nearly opaque without one. */
    fun tint(dark: Boolean, blurred: Boolean): Int {
        val base = if (dark) DARK_BASE else LIGHT_BASE
        val color = Color(base)
        val alpha = if (blurred) AtomGlass.tintAlpha(color) else AtomGlass.fallbackAlpha(color)
        return (base and 0x00FFFFFF) or ((alpha * 255f).toInt().coerceIn(0, 255) shl 24)
    }
}
