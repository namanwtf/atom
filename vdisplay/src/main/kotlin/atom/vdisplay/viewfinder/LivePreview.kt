package atom.vdisplay.viewfinder

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import atom.vdisplay.R
import atom.vdisplay.geom.Coords
import atom.vdisplay.geom.Size
import atom.vdisplay.route.DisplayRouter
import atom.vdisplay.route.LeaseState
import kotlin.math.roundToInt

/**
 * The hidden display's live picture, aspect-fit and letterboxed on black. Frames flow only while this is on screen
 * and its window is resumed (a hidden overlay window pauses its lifecycle), at most [fps] per second.
 */
@Composable
internal fun LivePreview(fps: Int, modifier: Modifier = Modifier) {
    val state by DisplayRouter.state.collectAsStateWithLifecycle()
    val lease = (state as? LeaseState.Active)?.lease?.takeIf { !it.main }
    val display = lease?.let { DisplayRouter.hiddenDisplay() }
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (display == null) {
            if (state is LeaseState.Lost) Hint(stringResource(R.string.vd_display_unavailable))
            return@Box
        }
        LifecycleResumeEffect(display, fps) {
            display.addViewer(fps)
            onPauseOrDispose { display.removeViewer() }
        }
        val frame by display.preview.collectAsStateWithLifecycle()
        val bmp = frame
        if (bmp == null) {
            Hint(stringResource(R.string.vd_waiting_frame))
            return@Box
        }
        val image = bmp.asImageBitmap()
        Canvas(Modifier.fillMaxSize()) {
            val fit = Coords.fit(Size(image.width, image.height), Size(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1)))
            drawImage(
                image,
                dstOffset = IntOffset(fit.offsetX.roundToInt(), fit.offsetY.roundToInt()),
                dstSize = IntSize(fit.drawnWidth.roundToInt(), fit.drawnHeight.roundToInt()),
                filterQuality = FilterQuality.Low,
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
