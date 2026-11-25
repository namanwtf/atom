package atom.app.ui.chat

import android.content.ClipData
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import atom.app.ui.common.formatDuration
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Copies plain text (Markdown and TeX source kept) to the system clipboard. */
@Composable
internal fun rememberCopier(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return remember(clipboard, scope) {
        { text -> scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Atom", text))) } }
    }
}

/** Motion budget for the chat: short, and nothing runs while idle. */
internal const val QUICK = 180

/** Graphite rounded dialog with a graphite secondary and a white primary button. */
@Composable
internal fun AtomDialog(
    onDismissRequest: () -> Unit,
    icon: ImageVector?,
    title: String,
    dismissLabel: String?,
    onDismiss: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
    destructive: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismissRequest) {
        Surface(shape = RoundedCornerShape(28.dp), color = colors.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 24.dp, bottom = 18.dp)) {
                if (icon != null) {
                    Box(Modifier.size(40.dp).background(colors.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(icon, null, Modifier.size(20.dp), tint = if (destructive) colors.error else colors.onSurface)
                    }
                    Spacer(Modifier.height(16.dp))
                }
                Text(title, style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
                Spacer(Modifier.height(10.dp))
                content()
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (dismissLabel != null) DialogButton(dismissLabel, primary = false, onClick = onDismiss, modifier = Modifier.weight(1f))
                    DialogButton(confirmLabel, primary = true, onClick = onConfirm, modifier = Modifier.weight(1f), enabled = confirmEnabled)
                }
            }
        }
    }
}

@Composable
private fun DialogButton(text: String, primary: Boolean, onClick: () -> Unit, modifier: Modifier, enabled: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        color = if (primary && enabled) colors.primary else colors.surfaceContainerHighest,
        contentColor = if (primary && enabled) colors.onPrimary else colors.onSurface,
        modifier = modifier.height(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** A single-line or multi-line text input on graphite, for dialogs. */
@Composable
internal fun DialogField(state: TextFieldState, placeholder: String, singleLine: Boolean = true, mono: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(
        Modifier
            .fillMaxWidth()
            .background(colors.surfaceContainer, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        val style = (if (mono) atom.ui.theme.LocalAtomExtras.current.mono else MaterialTheme.typography.bodyLarge).copy(color = colors.onSurface)
        if (state.text.isEmpty()) Text(placeholder, style = style.copy(color = colors.onSurfaceVariant))
        BasicTextField(
            state = state,
            textStyle = style,
            cursorBrush = SolidColor(colors.primary),
            lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
    }
}

@Composable
internal fun Dot(color: Color, description: String? = null) {
    Box(
        Modifier
            .size(6.dp)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .background(color, CircleShape),
    )
}

/** Breathing dot for something in flight. Only composed while it runs, so the animation stops with it. */
@Composable
internal fun RunningDot(color: Color = MaterialTheme.colorScheme.onSurface) {
    val transition = rememberInfiniteTransition(label = "running")
    val alpha = transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "runningAlpha",
    )
    Box(Modifier.size(6.dp).graphicsLayer { this.alpha = alpha.value }.background(color, CircleShape))
}

/** Three calm dots in a travelling wave. Composed only while a task runs; alpha is applied in the draw layer. */
@Composable
internal fun WorkingDots() {
    val transition = rememberInfiniteTransition(label = "working")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "workingPhase",
    )
    val color = MaterialTheme.colorScheme.onSurface
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            Box(
                Modifier
                    .size(6.dp)
                    .graphicsLayer {
                        val d = ((phase.value - i * 0.18f) % 1f + 1f) % 1f
                        alpha = 0.22f + 0.78f * (1f - abs(d * 2f - 1f))
                    }
                    .background(color, CircleShape),
            )
        }
    }
}

/** Elapsed time since [startedAt], shown only after [showAfterMs]; ticks once a second while composed. */
@Composable
internal fun LiveTimer(startedAt: Long, showAfterMs: Long = 3_000, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    var now by remember(startedAt) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val elapsed = now - startedAt
    if (elapsed >= showAfterMs) Text(formatDuration(elapsed - elapsed % 1000), style = MaterialTheme.typography.labelSmall, color = color)
}

/** Seconds until [untilMillis], ticking while composed. */
@Composable
internal fun rememberCountdown(untilMillis: Long): Long {
    var left by remember(untilMillis) { mutableLongStateOf(((untilMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(0)) }
    LaunchedEffect(untilMillis) {
        while (left > 0) {
            delay(1_000)
            left = ((untilMillis - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)
        }
    }
    return left
}

// ---------------------------------------------------------------- Glyphs (Material Design geometry, Apache-2.0)

internal object ChatGlyphs {
    val Sidebar: ImageVector by lazy { stroke("Sidebar", "M4,8.5h16M4,15.5h10") }
    val ArrowUp: ImageVector by lazy { stroke("ArrowUp", "M12,19V5.5M6.5,11L12,5.5L17.5,11", width = 2.2f) }
    val ArrowDown: ImageVector by lazy { stroke("ArrowDown", "M12,5V18.5M6.5,13L12,18.5L17.5,13", width = 2.2f) }
    val Copy: ImageVector by lazy {
        fill("Copy", "M16,1H4C2.9,1 2,1.9 2,3v14h2V3h12V1zM19,5H8C6.9,5 6,5.9 6,7v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2V7C21,5.9 20.1,5 19,5zM19,21H8V7h11V21z")
    }
    val Regenerate: ImageVector by lazy {
        fill("Regenerate", "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4L17.65,6.35z")
    }
    val Branch: ImageVector by lazy {
        stroke("Branch", "M6,3v12M18,9a3,3 0,1 0,0 -0.01M6,21a3,3 0,1 0,0 -0.01M18,9c0,5 -6,4 -12,9", width = 1.8f)
    }
    val Edit: ImageVector by lazy {
        fill("Edit", "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z")
    }
    val Speaker: ImageVector by lazy {
        fill("Speaker", "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z")
    }
    val Image: ImageVector by lazy {
        fill("Image", "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2zM8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z")
    }
    val File: ImageVector by lazy {
        fill("File", "M14,2H6c-1.1,0 -1.99,0.9 -1.99,2L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8l-6,-6zM13,9V3.5L18.5,9H13z")
    }
    val Folder: ImageVector by lazy {
        fill("Folder", "M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z")
    }
    val Path: ImageVector by lazy { stroke("Path", "M8,20L16,4M4,12h3M17,12h3", width = 2f) }
    val Brain: ImageVector by lazy {
        stroke(
            "Brain",
            "M12,5a3,3 0,0 0,-5.6 1.5A3,3 0,0 0,4.5 12a3,3 0,0 0,2 5.2A3,3 0,0 0,12 18.5zM12,5a3,3 0,0 1,5.6 1.5A3,3 0,0 1,19.5 12a3,3 0,0 1,-2 5.2A3,3 0,0 1,12 18.5zM12,5v13.5",
            width = 1.6f,
        )
    }
    val Pin: ImageVector by lazy { fill("Pin", "M16,9V4h1c0.55,0 1,-0.45 1,-1s-0.45,-1 -1,-1H7C6.45,2 6,2.45 6,3s0.45,1 1,1h1v5c0,1.66 -1.34,3 -3,3v2h5.97v7l1,1 1,-1v-7H19v-2c-1.66,0 -3,-1.34 -3,-3z") }
    val Search: ImageVector by lazy {
        fill("Search", "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z")
    }
    val Export: ImageVector by lazy { fill("Export", "M19,12v7H5v-7H3v7c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2v-7h-2zM13,12.67l2.59,-2.58L17,11.5l-5,5 -5,-5 1.41,-1.41L11,12.67V3h2z") }
    val Compress: ImageVector by lazy { stroke("Compress", "M5,9h14M5,15h14M12,2v4M9.5,4.5L12,7l2.5,-2.5M12,22v-4M9.5,19.5L12,17l2.5,2.5", width = 1.8f) }
    val Stats: ImageVector by lazy { fill("Stats", "M5,9.2h3V19H5V9.2zM10.6,5h2.8v14h-2.8V5zM16.2,13H19v6h-2.8V13z") }
    val Pause: ImageVector by lazy { fill("Pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z") }
    val Play: ImageVector by lazy { fill("Play", "M8,5v14l11,-7z") }
    val Chevron: ImageVector by lazy { stroke("Chevron", "M9,6l6,6 -6,6", width = 2f) }
    val Callout: ImageVector by lazy { fill("Callout", "M11,7h2v2h-2zM11,11h2v6h-2zM12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2z") }
    val Check: ImageVector by lazy { stroke("Check", "M5,12.5l4.5,4.5L19,7.5", width = 2.2f) }
    val Unchecked: ImageVector by lazy { stroke("Unchecked", "M5,5h14v14H5z", width = 1.8f) }

    private fun stroke(name: String, path: String, width: Float = 2f): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(
                pathData = addPathNodes(path),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()

    private fun fill(name: String, path: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
            .build()
}
