@file:OptIn(ExperimentalFoundationApi::class)

package atom.web.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import atom.ui.components.CircleIconButton
import atom.ui.components.LargeTitleScreen
import atom.ui.feature.FeatureNav
import atom.web.files.Sizes
import atom.web.image.GalleryItem
import atom.web.image.ImageGallery
import kotlinx.coroutines.launch

/**
 * Generated images. Tap opens the viewer (Delete sits in its top bar); long-press selects, and the top bar then
 * deletes the selection. Every delete asks first, with the app's usual confirmation dialog.
 */
@Composable
internal fun GalleryScreen(nav: FeatureNav) {
    val context = LocalContext.current
    val items by ImageGallery.items.collectAsState()
    val scope = rememberCoroutineScope()
    var viewing by remember { mutableStateOf<GalleryItem?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { ImageGallery.load(context) }
    // Forget selected images that are gone (deleted from the viewer, or cleared).
    LaunchedEffect(items) {
        val present = items.map { it.file }.toSet()
        if (!present.containsAll(selected)) selected = selected intersect present
    }
    val selecting = selected.isNotEmpty()
    BackHandler(enabled = selecting) { selected = emptySet() }

    LargeTitleScreen(
        title = if (selecting) "${selected.size} selected" else "Generated images",
        onBack = { if (selecting) selected = emptySet() else nav.back() },
        actions = {
            if (selecting) {
                CircleIconButton(Icons.Filled.Close, "Cancel selection", { selected = emptySet() }, modifier = Modifier.padding(end = 8.dp))
                CircleIconButton(Icons.Filled.Delete, "Delete selected", { confirmDeleteSelected = true }, modifier = Modifier.padding(end = 8.dp))
            } else if (items.isNotEmpty()) {
                CircleIconButton(WebIcons.DeleteSweep, "Clear all", { confirmClear = true }, modifier = Modifier.padding(end = 8.dp))
            }
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
                    "${items.size} image${if (items.size == 1) "" else "s"} · ${Sizes.human(total)} (tap to view, save or share; long-press to select)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 14.dp),
                )
            }
        }
        val rows = items.chunked(3)
        items(rows.size) { row ->
            val chunk = rows[row]
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chunk.forEach { item ->
                    val bmp = rememberThumb(item.file, 360)
                    val isSelected = item.file in selected
                    val shape = RoundedCornerShape(12.dp)
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).clip(shape)
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .then(if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
                            .combinedClickable(
                                onClick = {
                                    if (selecting) {
                                        selected = if (isSelected) selected - item.file else selected + item.file
                                    } else {
                                        viewing = item
                                    }
                                },
                                onLongClickLabel = "Select",
                                onLongClick = { selected = selected + item.file },
                            ),
                    ) {
                        if (bmp != null) Image(bmp, item.prompt, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        if (isSelected) {
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
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
    val count = selected.size
    ConfirmDialog(
        if (count == 1) "Delete image?" else "Delete $count images?",
        if (count == 1) DELETE_WARNING else DELETE_MANY_WARNING,
        "Delete",
        onConfirm = {
            val doomed = items.filter { it.file in selected }
            selected = emptySet()
            scope.launch { doomed.forEach { ImageGallery.delete(context, it) } }
        },
        onDismiss = { confirmDeleteSelected = false },
        show = confirmDeleteSelected,
    )
    ConfirmDialog(
        "Clear all images?",
        "Deletes ${items.size} generated images from Atom. They can no longer be loaded in chat history. Copies saved to Pictures stay.",
        "Clear all",
        onConfirm = { scope.launch { ImageGallery.clear(context) } },
        onDismiss = { confirmClear = false },
        show = confirmClear,
    )
}
