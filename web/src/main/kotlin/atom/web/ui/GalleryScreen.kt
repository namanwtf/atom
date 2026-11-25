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
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
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
            if (items.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(top = 48.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No generated images yet", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "Enable AI image generation and ask Atom to draw something in any chat. The images are kept here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            } else {
                Text(
                    "${items.size} image${if (items.size == 1) "" else "s"} · ${Sizes.human(total)} (tap to view, save, or share)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 14.dp),
                )
            }
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
            "Clear all images?", "Deletes ${items.size} generated images from Atom. They can no longer be loaded in chat history. Copies saved to Pictures stay.", "Clear all",
            onConfirm = { scope.launch { ImageGallery.clear(context) } }, onDismiss = { confirmClear = false },
        )
    }
}
