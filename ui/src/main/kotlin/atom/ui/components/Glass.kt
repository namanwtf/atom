package atom.ui.components

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import atom.ui.theme.LocalAppearance
import atom.ui.theme.TopBarBlur
import atom.ui.theme.glassBlurEnabled
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

/** Shared state between the content that scrolls underneath and the glass bars drawn over it. */
@Composable
fun rememberGlassState(): HazeState = rememberHazeState()

/** Marks the content the glass bars blur. */
fun Modifier.glassSource(state: HazeState): Modifier = hazeSource(state)

enum class GlassEdge { Top, Bottom }

/**
 * Frosted translucent bar over pitch black. The blur runs on the GPU and only re-renders when the content behind
 * it changes; with battery saver on it drops to a plain translucent tint, which costs nothing extra.
 */
@Composable
fun Modifier.glassBar(state: HazeState, edge: GlassEdge): Modifier {
    val base = MaterialTheme.colorScheme.background
    val hairline = MaterialTheme.colorScheme.outlineVariant
    val powerSave = rememberPowerSaveMode()
    // Appearance > Blur off: a plain translucent tint, like battery saver.
    val blur = glassBlurEnabled() && !powerSave
    val style = remember(base, blur) {
        HazeBlurStyle {
            blurEnabled(blur)
            blurRadius(AtomGlass.BlurRadius)
            noiseFactor(AtomGlass.Noise)
            backgroundColor(base)
            colorEffects(listOf(HazeColorEffect.tint(base.copy(alpha = AtomGlass.tintAlpha(base)))))
            fallbackColorEffect(HazeColorEffect.tint(base.copy(alpha = AtomGlass.fallbackAlpha(base))))
        }
    }
    return this
        .hazeBlur(HazeInput.Sources(state), style)
        .drawBehind {
            val y = if (edge == GlassEdge.Top) size.height else 0f
            drawLine(hairline, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
}

/**
 * How the glass bands draw right now, resolved from Appearance and the device:
 *
 * - [blur]: real backdrop blur. Needs Appearance > Blur, Android 12+, battery saver off and a device that is not
 *   flagged low-RAM; otherwise every band is a plain gradient of the page colour (opaque at the outer edge).
 * - [progressive]: Appearance > Progressive blur. The blur radius ramps down across the band's fading edge;
 *   off, a uniform blur is faded out with an alpha mask instead (one blur pass, cheaper).
 * - [bandFirst]: Appearance > Top bar blur style. "Gradient band" keeps a strong page-colour band over a light
 *   blur; "Progressive" lets a stronger blur carry the look under a thinner tint.
 */
@Immutable
class GlassChrome(val blur: Boolean, val progressive: Boolean, val bandFirst: Boolean)

@Composable
fun rememberGlassChrome(): GlassChrome {
    val appearance = LocalAppearance.current
    val context = LocalContext.current
    val powerSave = rememberPowerSaveMode()
    val lowRam = remember(context) { context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true }
    val blur = appearance.blur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !powerSave && !lowRam
    val progressive = blur && appearance.progressiveBlur
    val bandFirst = appearance.topBarBlur == TopBarBlur.Gradient
    return remember(blur, progressive, bandFirst) { GlassChrome(blur, progressive, bandFirst) }
}

/**
 * The frosted band behind a top bar ([GlassEdge.Top]) or a bottom dock ([GlassEdge.Bottom]): the page colour,
 * opaque at the outer screen edge, fading to transparent over the last [fade] towards the content, so text that
 * scrolls under the bar melts away instead of colliding with it.
 *
 * With blur available, what scrolls under the band is also blurred (sources marked with [glassSource] on
 * [state]). The blur is attached only while [covered] says content actually sits under the band, so an idle or
 * empty screen renders nothing extra, and nothing animates on its own. Without blur the band is drawn more
 * opaque, which keeps titles over it legible on its own.
 */
@Composable
fun Modifier.glassBand(state: HazeState, edge: GlassEdge, covered: Boolean, fade: Dp, color: Color = MaterialTheme.colorScheme.background): Modifier {
    val chrome = rememberGlassChrome()
    val page = MaterialTheme.colorScheme.background
    val fadePx = with(LocalDensity.current) { fade.toPx() }
    val height = remember { mutableFloatStateOf(0f) }
    val blurring = chrome.blur && covered
    // Band alpha at the outer edge and where the fade starts.
    val outer: Float
    val inner: Float
    when {
        !blurring -> { outer = 1f; inner = 0.97f }
        chrome.bandFirst -> { outer = 0.92f; inner = 0.74f }
        else -> { outer = 0.78f; inner = 0.46f }
    }
    val style = remember(blurring, chrome, page, edge, fadePx) {
        if (!blurring) {
            null
        } else {
            HazeBlurStyle {
                blurEnabled(true)
                // Band-first keeps a stronger band over a lighter blur; Progressive lets the blur carry the look.
                blurRadius(if (chrome.bandFirst) AtomGlass.BlurRadius * 0.85f else AtomGlass.BlurRadius * 1.15f)
                noiseFactor(AtomGlass.Noise)
                // Opaque under the blur, so the sharp text never shows through where the blur is.
                backgroundColor(page)
                fallbackColorEffect(HazeColorEffect.tint(page.copy(alpha = 0.94f)))
                // Read here so a new height resolves the style again without recomposition.
                val h = height.floatValue
                if (edge == GlassEdge.Top) {
                    val start = (h - fadePx * 1.5f).coerceAtLeast(0f)
                    if (chrome.progressive) {
                        progressive(HazeProgressive.verticalGradient(LinearEasing, startY = start, startIntensity = 1f, endY = h, endIntensity = 0f))
                    } else {
                        mask(Brush.verticalGradient(0f to Color.Black, 1f to Color.Transparent, startY = h - fadePx, endY = h))
                    }
                } else {
                    if (chrome.progressive) {
                        progressive(HazeProgressive.verticalGradient(LinearEasing, startY = 0f, startIntensity = 0f, endY = fadePx * 1.5f, endIntensity = 1f))
                    } else {
                        mask(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = 0f, endY = fadePx))
                    }
                }
            }
        }
    }
    return this
        .onSizeChanged { height.floatValue = it.height.toFloat() }
        .then(if (style != null) Modifier.hazeBlur(HazeInput.Sources(state), style) else Modifier)
        .drawBehind {
            val h = size.height
            if (h <= 0f) return@drawBehind
            val f = (fadePx / h).coerceIn(0f, 1f)
            val stops = if (edge == GlassEdge.Top) {
                arrayOf(
                    0f to color.copy(alpha = outer),
                    (1f - f) to color.copy(alpha = inner),
                    (1f - f * 0.5f) to color.copy(alpha = inner * 0.42f),
                    1f to color.copy(alpha = 0f),
                )
            } else {
                arrayOf(
                    0f to color.copy(alpha = 0f),
                    (f * 0.5f) to color.copy(alpha = inner * 0.42f),
                    f to color.copy(alpha = inner),
                    1f to color.copy(alpha = outer),
                )
            }
            drawRect(Brush.verticalGradient(*stops))
        }
}

/**
 * A side pane of frosted glass over [state]'s sources: [color] tinted per [AtomGlass] over a blur of what is behind.
 * The same modifier and style in every frame (sliding, open, closing): only the pane's position changes, so the
 * blur never pops in or swaps. Keep the sources registered while the screen is visible, so the first frame of a
 * slide is already blurred. Without blur (Appearance, battery saver, older Android, low-RAM devices) it is the same
 * colour as a translucent tint.
 */
@Composable
fun Modifier.glassPane(state: HazeState, color: Color, glass: Boolean = rememberGlassChrome().blur): Modifier {
    if (!glass) return background(color.copy(alpha = AtomGlass.fallbackAlpha(color)))
    val page = MaterialTheme.colorScheme.background
    val style = remember(page, color) {
        HazeBlurStyle {
            blurEnabled(true)
            blurRadius(AtomGlass.BlurRadius)
            noiseFactor(AtomGlass.Noise)
            backgroundColor(page)
            colorEffects(listOf(HazeColorEffect.tint(color.copy(alpha = AtomGlass.tintAlpha(color)))))
            fallbackColorEffect(HazeColorEffect.tint(color.copy(alpha = AtomGlass.fallbackAlpha(color))))
        }
    }
    return hazeBlur(HazeInput.Sources(state), style)
}

/** Battery saver state, observed only while the calling UI is on screen. */
@Composable
fun rememberPowerSaveMode(): Boolean {
    val context = LocalContext.current
    val power = remember { context.getSystemService(PowerManager::class.java) }
    var saving by remember { mutableStateOf(power.isPowerSaveMode) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                saving = power.isPowerSaveMode
            }
        }
        context.registerReceiver(receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }
    return saving
}
