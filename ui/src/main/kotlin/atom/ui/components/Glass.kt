package atom.ui.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import atom.ui.theme.glassBlurEnabled
import dev.chrisbanes.haze.HazeInput
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
            blurRadius(26.dp)
            noiseFactor(0f)
            backgroundColor(base)
            colorEffects(listOf(HazeColorEffect.tint(base.copy(alpha = 0.62f))))
            fallbackColorEffect(HazeColorEffect.tint(base.copy(alpha = 0.9f)))
        }
    }
    return this
        .hazeBlur(HazeInput.Sources(state), style)
        .drawBehind {
            val y = if (edge == GlassEdge.Top) size.height else 0f
            drawLine(hairline, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
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
