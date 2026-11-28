package atom.overlay.hey

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
import atom.overlay.ui.ApprovalCard
import atom.ui.theme.AtomEmblem
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

// ATOM's light: black and silver. A cool steel halo carries it on light screens, a white core on dark ones, and a
// thin graphite keyline keeps the edge readable over white content.
private val Steel = Color(0xFF8D9FB3)
private val Ice = Color(0xFFE4ECF6)
private val Silver = Color(0xFFC4CDD7)
private val Keyline = Color(0xFF0A0D11)
private val SoftRed = Color(0xFFFF8E86)
private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** Halo layers from the outside in: width in dp at full spread, and alpha at full intensity. */
private val HALO = listOf(46f to 0.045f, 32f to 0.065f, 21f to 0.09f, 13f to 0.13f, 7f to 0.2f)
private const val CORE_DP = 2.4f
private const val SHIMMER_MS = 4_800
private const val SPIN_MS = 1_300
private const val STOPS = 24

// ------------------------------------------------------------------ Edge light

/**
 * The light around the screen edges, following the display's rounded corners. Live: two soft highlights travel
 * slowly round the edge and the band widens and brightens with the voice. Pulse: one bright breath. Calm: a thin
 * still line. Everything it draws reads the animated values in the draw phase only, so a still light is drawn
 * once and requests no frames.
 */
@Composable
internal fun HeyEdgeLight() {
    val stage = HeyAtomOverlay.shown
    val motion = HeyAtomOverlay.motion
    val leaving = HeyAtomOverlay.leaving
    val mode = HeyMachine.glow(stage)
    val view = LocalView.current
    val cornerPx = remember(view) { roundedCorner(view) }

    val appear = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(leaving, motion) {
        val target = if (leaving) 0f else 1f
        if (motion) appear.animateTo(target, tween(if (leaving) 260 else 520, easing = Emphasized)) else appear.snapTo(target)
    }
    val intensity = remember { Animatable(0f) }
    LaunchedEffect(mode, motion) {
        val rest = restIntensity(mode)
        if (!motion) {
            intensity.snapTo(rest)
        } else if (mode == HeyGlow.Pulse) {
            intensity.animateTo(1.5f, tween(220, easing = Emphasized))
            intensity.animateTo(rest, tween(900, easing = Emphasized))
        } else {
            intensity.animateTo(rest, tween(if (mode == HeyGlow.None) 420 else 640, easing = Emphasized))
        }
    }
    val live = motion && mode == HeyGlow.Live
    val voice = animateFloatAsState(if (live) HeyAtomOverlay.level else 0f, tween(140), label = "voice")
    val phase = travelling(live)
    Canvas(Modifier.fillMaxSize()) {
        val strength = intensity.value * appear.value
        if (strength > 0.002f) edgeLight(cornerPx ?: 28.dp.toPx(), strength, voice.value, phase.value)
    }
}

private fun restIntensity(mode: HeyGlow): Float = when (mode) {
    HeyGlow.Live -> 1f
    HeyGlow.Pulse -> 0.55f
    HeyGlow.Calm -> 0.42f
    HeyGlow.None -> 0f
}

/** The highlights' position round the edge (0..1); it moves only while [active]. */
@Composable
private fun travelling(active: Boolean): State<Float> {
    if (!active) return remember { mutableFloatStateOf(0.12f) }
    val t = rememberInfiniteTransition(label = "shimmer")
    return t.animateFloat(0.12f, 1.12f, infiniteRepeatable(tween(SHIMMER_MS, easing = LinearEasing)), label = "phase")
}

private fun roundedCorner(view: View): Float? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat()?.takeIf { it > 0f }
    } else {
        null
    }

private fun DrawScope.edgeLight(radius: Float, strength: Float, voice: Float, phase: Float) {
    val dp = density
    val full = strength.coerceAtMost(1f)
    val spread = 0.3f + 0.7f * full + 0.6f * voice + 0.25f * (strength - 1f).coerceAtLeast(0f)
    val halo = Brush.sweepGradient(*stops(phase, Steel, Ice, 0.65f), center = center)
    for ((width, alpha) in HALO) ring(halo, width * dp * spread, (alpha * strength).coerceAtMost(1f), 0f, radius)
    val core = (CORE_DP + 1.6f * voice) * dp * (0.75f + 0.25f * full)
    // The keyline sits just inside the core: invisible on dark content, a crisp edge on white.
    ring(SolidColor(Keyline), 1f * dp, 0.24f * full, core, radius)
    ring(Brush.sweepGradient(*stops(phase, Silver, Color.White, 1f), center = center), core, (0.95f * strength).coerceAtMost(1f), 0f, radius)
}

/** A stroke following the display outline, [inset] px in from the edge. */
private fun DrawScope.ring(brush: Brush, width: Float, alpha: Float, inset: Float, radius: Float) {
    if (width <= 0f || alpha <= 0f) return
    val i = inset + width / 2f
    drawRoundRect(
        brush,
        topLeft = Offset(i, i),
        size = Size(size.width - i * 2, size.height - i * 2),
        cornerRadius = CornerRadius((radius - i).coerceAtLeast(0f)),
        alpha = alpha,
        style = Stroke(width),
    )
}

/** Colour stops round the edge: [base], brightening to [high] at two soft highlights half a turn apart. */
private fun stops(phase: Float, base: Color, high: Color, strength: Float): Array<Pair<Float, Color>> = Array(STOPS + 1) { k ->
    val t = k / STOPS.toFloat()
    val lift = max(bump(t, phase, 0.11f), 0.65f * bump(t, phase + 0.5f, 0.075f))
    t to lerp(base, high, (lift * strength).coerceIn(0f, 1f))
}

private fun bump(t: Float, center: Float, width: Float): Float {
    val raw = abs(t - center) % 1f
    val d = min(raw, 1f - raw)
    return exp(-(d * d) / (2f * width * width))
}

// ------------------------------------------------------------------ Panel

/** The panel's window: modal (a tap outside cancels) while listening, otherwise just the panel. */
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
    if (mode == HeyPanel.Modal) {
        val inset = with(LocalDensity.current) { HeyAtomOverlay.bottomInset.toDp() }
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

private val PanelFill = Brush.verticalGradient(listOf(Color(0xF0191C21), Color(0xF20B0C0F)))
private val PanelEdge = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.24f), Color.White.copy(alpha = 0.05f)))
private val Words = Color(0xFFF7F9FB)
private val Muted = Color(0xFFA3ACB7)

@Composable
private fun HeyCard(enter: Animatable<Float, AnimationVector1D>) {
    val s = HeyAtomOverlay.shown
    val motion = HeyAtomOverlay.motion
    val shape = RoundedCornerShape(30.dp)
    Column(
        Modifier
            .padding(horizontal = 12.dp, vertical = 12.dp)
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
            .padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 18.dp),
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
    }
}

@Composable
private fun Header(s: HeyStage) {
    val label = when (s) {
        is HeyStage.Listening -> stringResource(R.string.hey_atom_listening)
        is HeyStage.Heard, is HeyStage.Opening -> stringResource(R.string.hey_atom_got_it)
        is HeyStage.Working -> statusLabel(s.status)
        is HeyStage.Done -> stringResource(if (s.ok) R.string.hey_atom_done else R.string.hey_atom_failed)
        HeyStage.Missed -> stringResource(R.string.hey_atom_missed_title)
        HeyStage.Hidden -> ""
    }
    val color = when {
        s is HeyStage.Done && !s.ok -> SoftRed
        s == HeyStage.Missed -> Words
        else -> Silver
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Orb(s)
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            color = color,
            fontSize = if (s == HeyStage.Missed) 17.sp else 13.sp,
            fontWeight = if (s == HeyStage.Missed) FontWeight.SemiBold else FontWeight.Medium,
            letterSpacing = if (s == HeyStage.Missed) 0.sp else 0.3.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
        )
        CloseButton(s)
    }
}

@Composable
private fun Body(s: HeyStage) {
    val big = MaterialTheme.typography.bodyLarge.copy(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium)
    val small = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 19.sp)
    Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 12.dp)) {
        when (s) {
            is HeyStage.Listening -> {
                if (s.words.isEmpty()) {
                    Text(stringResource(R.string.hey_atom_listening_hint), style = big, color = Muted)
                } else {
                    Text(HeyMachine.tail(s.words), style = big, color = Words.copy(alpha = 0.86f), maxLines = 4)
                }
                ScreenChip()
            }
            is HeyStage.Heard -> {
                // The words settle: they brighten from the live grey to full white.
                val settle = remember { Animatable(Words.copy(alpha = 0.86f)) }
                LaunchedEffect(Unit) { settle.animateTo(Words, tween(360)) }
                Text(HeyMachine.tail(s.words), style = big, color = settle.value, maxLines = 4)
                ScreenChip()
            }
            is HeyStage.Working -> {
                Text(s.words, style = small, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Text(
                    s.step ?: stringResource(R.string.hey_atom_working),
                    style = small.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
                    color = Words,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                s.pending?.let { approval ->
                    Spacer(Modifier.height(12.dp))
                    ApprovalCard(approval, onAnswer = HeyAtomOverlay::answer)
                }
            }
            is HeyStage.Opening -> {
                Text(s.words, style = small, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.hey_atom_opening), style = small.copy(fontSize = 16.sp), color = Words)
            }
            is HeyStage.Done -> {
                Text(s.words, style = small, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Text(
                    s.result.ifBlank { stringResource(R.string.hey_atom_done_empty) },
                    style = small.copy(fontSize = 17.sp, lineHeight = 24.sp),
                    color = Words,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.hey_atom_open_hint), style = small.copy(fontSize = 12.sp), color = Muted)
            }
            HeyStage.Missed -> Text(stringResource(R.string.hey_atom_missed_hint), style = small.copy(fontSize = 15.sp), color = Muted)
            HeyStage.Hidden -> Unit
        }
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
            .padding(top = 14.dp)
            .clip(shape)
            .background(if (on) Ice.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.05f))
            .border(1.dp, if (on) Ice.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.10f), shape)
            .clickable(onClick = HeyAtomOverlay::toggleScreen)
            .semantics { contentDescription = description }
            .padding(start = 10.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScreenGlyph(if (on) Ice else Muted, crossed = !on)
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            color = if (on) Words else Muted,
            fontSize = 13.sp,
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

/** ATOM's emblem in a small dark disc. It glows with the voice while listening and has a turning arc while working. */
@Composable
private fun Orb(s: HeyStage) {
    val motion = HeyAtomOverlay.motion
    val listening = s is HeyStage.Listening
    val voice by animateFloatAsState(if (listening && motion) HeyAtomOverlay.level else 0f, tween(120), label = "orb")
    val turn = spinner(motion && s is HeyStage.Working && !s.handedOff)
    val accent = if (s is HeyStage.Done && !s.ok) SoftRed else Ice
    val working = s is HeyStage.Working
    Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(38.dp)) {
            val r = size.minDimension / 2f
            val glowAlpha = if (listening) 0.30f + 0.5f * voice else 0.22f
            drawCircle(
                Brush.radialGradient(listOf(accent.copy(alpha = glowAlpha), accent.copy(alpha = 0f)), center, r * (0.8f + 0.2f * voice)),
                radius = r,
            )
            val disc = r * 0.7f
            drawCircle(Color(0xFF14171C), radius = disc)
            drawCircle(accent.copy(alpha = 0.42f), radius = disc, style = Stroke(1.dp.toPx()))
            if (working) {
                val stroke = 1.8.dp.toPx()
                val box = disc * 2f + stroke * 2.4f
                rotate(turn.value) {
                    drawArc(
                        Brush.sweepGradient(listOf(accent.copy(alpha = 0f), accent), center = center),
                        0f,
                        290f,
                        false,
                        Offset(center.x - box / 2f, center.y - box / 2f),
                        Size(box, box),
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
        }
        AtomEmblem(16.dp, tint = Color.White)
    }
}

@Composable
private fun spinner(active: Boolean): State<Float> {
    if (!active) return remember { mutableFloatStateOf(0f) }
    val t = rememberInfiniteTransition(label = "spin")
    return t.animateFloat(0f, 360f, infiniteRepeatable(tween(SPIN_MS, easing = LinearEasing)), label = "turn")
}

@Composable
private fun CloseButton(s: HeyStage) {
    val label = stringResource(
        when (s) {
            is HeyStage.Listening, is HeyStage.Heard -> R.string.hey_atom_cancel
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
