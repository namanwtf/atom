package atom.web.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import atom.ui.components.CircleIconButton
import atom.ui.components.LargeTitleScreen
import atom.ui.feature.FeatureNav
import atom.web.files.Sizes
import atom.web.image.GalleryItem
import atom.web.image.ImageGallery
import kotlinx.coroutines.launch

@Composable
internal fun GalleryScreen(nav: FeatureNav) {
    val context = LocalContext.current
    val items by ImageGallery.items.collectAsState()
    val scope = rememberCoroutineScope()
    var viewing by remember { mutableStateOf<GalleryItem?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { ImageGallery.load(context) }

    LargeTitleScreen(
        title = "Generated images",
        onBack = nav::back,
        actions = {
            if (items.isNotEmpty()) CircleIconButton(Icons.Filled.Delete, "Clear all", { confirmClear = true }, modifier = Modifier.padding(end = 8.dp))
        },
    ) {
        item {
            val total = items.sumOf { it.bytes }
            Text(
                if (items.isEmpty()) "No images yet. Turn on AI image generation and ask for a picture in any chat."
                else "${items.size} image${if (items.size == 1) "" else "s"} · ${Sizes.human(total)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, bottom = 14.dp),
            )
        }
        items(items.chunked(3).size) { row ->
            val chunk = items.chunked(3)[row]
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chunk.forEach { item ->
                    val bmp = rememberThumb(item.file, 360)
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer).clickable { viewing = item },
                    ) {
                        if (bmp != null) Image(bmp, item.prompt, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
                repeat(3 - chunk.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    viewing?.let { item ->
        ImageViewer(item, onClose = { viewing = null }, allowDelete = true, onDelete = { scope.launch { ImageGallery.delete(context, item) } })
    }
    if (confirmClear) {
        ConfirmDialog(
            "Clear all images?", "Deletes ${items.size} generated images from Atom. Copies saved to Pictures stay.", "Clear all",
            onConfirm = { scope.launch { ImageGallery.clear(context) } }, onDismiss = { confirmClear = false },
        )
    }
}
