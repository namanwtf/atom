package atom.app.ui.chat

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import atom.app.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Decodes [path] off the main thread, downsampled so its long side is about [maxPx]. Null until ready or on failure. */
@Composable
internal fun rememberLocalImage(path: String, maxPx: Int): ImageBitmap? {
    val image by produceState<ImageBitmap?>(null, path, maxPx) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    return image
}

/** A chat image thumbnail; tapping it opens [ChatImageViewer]. */
@Composable
internal fun ChatImageThumb(path: String, width: Dp, height: Dp, corner: Dp, onOpen: () -> Unit) {
    val bitmap = rememberLocalImage(path, 480)
    val shape = RoundedCornerShape(corner)
    Box(
        Modifier
            .size(width, height)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClickLabel = stringResource(R.string.image_view), onClick = onOpen),
    ) {
        if (bitmap != null) Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

/**
 * Full-screen viewer for a chat image: Save (a copy in Pictures/Atom), Share, and "Continue editing", which puts
 * the picture back into the composer so the user can describe changes. [onContinueEditing] null hides it.
 */
@Composable
internal fun ChatImageViewer(path: String, onDismiss: () -> Unit, onContinueEditing: (() -> Unit)?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bitmap = rememberLocalImage(path, 2048)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (bitmap != null) {
                Image(bitmap, null, Modifier.fillMaxSize().padding(vertical = 64.dp), contentScale = ContentScale.Fit)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.statusBarsPadding().padding(8.dp).align(Alignment.TopStart)) {
                Icon(Icons.Filled.Close, stringResource(R.string.action_close), tint = Color.White)
            }
            Row(
                Modifier.fillMaxWidth().align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            ) {
                ViewerButton(stringResource(R.string.image_save)) {
                    scope.launch {
                        // Closing the viewer cancels this scope: that is not a failed save, so no "Could not save".
                        val ok = try {
                            atom.web.image.ImageSaver.toPictures(context, File(path))
                            true
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            false
                        }
                        Toast.makeText(context, if (ok) R.string.image_saved else R.string.image_save_failed, Toast.LENGTH_SHORT).show()
                    }
                }
                ViewerButton(stringResource(R.string.image_share)) {
                    runCatching { atom.web.files.Share.share(context, listOf(File(path))) }
                        .onFailure { Toast.makeText(context, R.string.image_share_failed, Toast.LENGTH_SHORT).show() }
                }
                if (onContinueEditing != null) {
                    ViewerButton(stringResource(R.string.image_continue_editing), primary = true) {
                        onContinueEditing()
                        onDismiss()
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerButton(text: String, primary: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (primary) colors.primary else Color.White.copy(alpha = 0.14f),
        contentColor = if (primary) colors.onPrimary else Color.White,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
    }
}
