package atom.web.ui

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import atom.ui.components.CircleIconButton
import atom.ui.components.CompactButton
import atom.web.Web
import atom.web.files.Share
import atom.web.image.GalleryItem
import atom.web.image.ImageGallery
import atom.web.image.ImageJob
import atom.web.image.ImageJobs
import atom.web.image.ImageSaver
import atom.web.image.JobState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * The chat card for a generate_image / edit_image result. Pass the tool result text; it finds the live job
 * (with an elapsed timer while it runs) or, after a restart, the saved images listed in the text.
 */
@Composable
fun ImageResultCard(toolText: String, modifier: Modifier = Modifier) {
    val jobs by ImageJobs.jobs.collectAsState()
    // While the call is still running the chat passes a live spec; show the job as soon as the generator starts it.
    val spec = remember(toolText) { ImageJobs.parseLive(toolText) }
    if (spec != null) {
        val job = ImageJobs.liveJob(spec, jobs.values)
        ImageJobCard(job ?: remember(spec) { ImageJobs.placeholder(spec) }, modifier)
        return
    }
    val id = remember(toolText) { ImageJobs.parseId(toolText) }
    val live = id?.let { jobs[it] }
    val job = live ?: remember(toolText) { fromText(toolText) }
    ImageJobCard(job, modifier)
}

/** Card for a job that is still starting (before any tool text exists), e.g. while the call is in flight. */
@Composable
fun ImageJobCard(job: ImageJob, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var elapsed by remember { mutableLongStateOf(0L) }
    var showPrompt by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<GalleryItem?>(null) }
    if (job.state == JobState.Running) {
        LaunchedEffect(job.id) {
            while (true) {
                elapsed = (System.currentTimeMillis() - job.startedAt) / 1000
                delay(1000)
            }
        }
    }
    val c = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surfaceContainer).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(job.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            val secs = if (job.state == JobState.Running) elapsed else ((job.finishedAt ?: job.startedAt) - job.startedAt) / 1000
            if (job.startedAt > 0) Text("${secs}s", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
        }
        when (job.state) {
            JobState.Running -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(job.count.coerceIn(1, 4)) {
                    Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(c.surfaceContainerHigh))
                }
            }
            JobState.Failed -> Text(job.error ?: "Failed", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            JobState.Done -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    job.images.take(4).forEach { item ->
                        val exists = remember(item.file) { File(item.file).isFile }
                        val bmp = if (exists) rememberThumb(item.file, 512) else null
                        Box(
                            Modifier.weight(1f).aspectRatio(if (job.images.size == 1 && item.width > 0) item.width.toFloat() / item.height.coerceAtLeast(1) else 1f)
                                .clip(RoundedCornerShape(14.dp)).background(c.surfaceContainerHigh)
                                .then(if (exists) Modifier.clickable { viewing = item } else Modifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            if (!exists) {
                                Text("Local image was cleared", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                            }
                        }
                    }
                }
                // The prompt sits under the thumbnails, as a sibling in the card's Column. Inside the thumbnails'
                // Row a full-width child would be measured first and leave the weighted thumbnails no width.
                if (job.prompt.isNotBlank()) {
                    Column(Modifier.fillMaxWidth().clickable { showPrompt = true }) {
                        Text("Prompt", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                        Text(job.prompt, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val files = job.images.map { File(it.file) }.filter { it.isFile }
                    CompactButton(if (files.size > 1) "Save all" else "Save", onClick = {
                        scope.launch {
                            val ok = runCatching { files.forEach { ImageSaver.toPictures(context, it) } }.isSuccess
                            Toast.makeText(context, if (ok) "Saved to Pictures/Atom" else "Could not save", Toast.LENGTH_SHORT).show()
                        }
                    }, primary = false)
                    CompactButton("Share", onClick = { runCatching { Share.share(context, files) } }, primary = false, icon = Icons.Filled.Share)
                    CompactButton("Edit", onClick = {
                        files.firstOrNull()?.let {
                            Web.host.attachToComposer(context, Share.uriFor(context, it))
                            Toast.makeText(context, ATTACHED_TO_INPUT, Toast.LENGTH_SHORT).show()
                        }
                    }, primary = false, icon = Icons.Filled.Edit)
                }
            }
        }
    }
    if (showPrompt) {
        AlertDialog(
            onDismissRequest = { showPrompt = false },
            containerColor = c.surfaceContainerHigh,
            title = { Text("Prompt") },
            text = { Text(job.prompt + if (job.model.isNotBlank()) "\n\n${job.model}" else "", modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { showPrompt = false }) { Text("Close", color = c.onSurface) } },
        )
    }
    viewing?.let { item -> ImageViewer(item, onClose = { viewing = null }, allowDelete = false) }
}

private const val ATTACHED_TO_INPUT = "Attached to input. Describe the changes you want."

/** The warning before deleting one generated image. */
internal const val DELETE_WARNING =
    "Once deleted, this local original image can no longer be loaded in chat history. Copies saved to Pictures stay."

private fun fromText(text: String): ImageJob {
    val paths = Regex("^- (/\\S+) \\(", RegexOption.MULTILINE).findAll(text).map { it.groupValues[1] }.toList()
    val items = paths.map { p -> ImageGallery.find(p) ?: GalleryItem(p, "", "", "", 0) }
    val failed = paths.isEmpty()
    return ImageJob(
        id = ImageJobs.parseId(text) ?: "saved",
        conversationId = null,
        edit = text.contains("Edited"),
        count = items.size.coerceAtLeast(1),
        prompt = items.firstOrNull()?.prompt.orEmpty(),
        model = items.firstOrNull()?.model.orEmpty(),
        startedAt = 0,
        state = if (failed) JobState.Failed else JobState.Done,
        images = items,
        error = if (failed) text.lineSequence().drop(1).joinToString("\n").ifBlank { "Failed" } else null,
    )
}

/** Full-screen view of one image with save / share / edit / delete. */
@Composable
internal fun ImageViewer(item: GalleryItem, onClose: () -> Unit, allowDelete: Boolean, onDelete: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircleIconButton(Icons.Filled.Close, "Close", onClose)
                Text(
                    item.model.ifBlank { item.name }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                if (allowDelete) CircleIconButton(Icons.Filled.Delete, "Delete", { confirmDelete = true })
            }
            val bmp = rememberThumb(item.file, 2048)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (bmp != null) Image(bmp, item.prompt, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            if (item.prompt.isNotBlank()) {
                Text(
                    item.prompt, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
                CompactButton("Save", onClick = {
                    scope.launch {
                        val ok = runCatching { ImageSaver.toPictures(context, File(item.file)) }.isSuccess
                        Toast.makeText(context, if (ok) "Saved to Pictures/Atom" else "Could not save", Toast.LENGTH_SHORT).show()
                    }
                }, icon = WebIcons.Save)
                CompactButton("Share", onClick = { runCatching { Share.share(context, listOf(File(item.file))) } }, primary = false, icon = Icons.Filled.Share)
                CompactButton("Edit", onClick = {
                    Web.host.attachToComposer(context, Share.uriFor(context, File(item.file)))
                    Toast.makeText(context, ATTACHED_TO_INPUT, Toast.LENGTH_SHORT).show()
                    onClose()
                }, primary = false, icon = Icons.Filled.Edit)
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog("Delete image?", DELETE_WARNING, "Delete", onConfirm = {
            onDelete()
            onClose()
        }, onDismiss = { confirmDelete = false })
    }
}

/** Small preview of an agent browser step for the chat card; tap to take over. */
@Composable
fun BrowserPreviewCard(toolText: String, onTakeOver: (() -> Unit)?, modifier: Modifier = Modifier) {
    val path = remember(toolText) { atom.web.browser.BrowserCards.previewPath(toolText) } ?: return
    val bmp = rememberThumb(path, 540)
    val c = MaterialTheme.colorScheme
    Box(
        modifier.fillMaxWidth().aspectRatio(9f / 12f).clip(RoundedCornerShape(16.dp)).background(c.surfaceContainerHigh)
            .then(if (onTakeOver != null) Modifier.clickable(onClick = onTakeOver) else Modifier),
    ) {
        if (bmp != null) Image(bmp, "Browser page", Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
        if (onTakeOver != null) {
            Text(
                "Take over", style = MaterialTheme.typography.labelLarge, color = c.onPrimary,
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp).clip(RoundedCornerShape(50)).background(c.primary).padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

