package atom.app.ui.models

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import atom.app.R
import atom.app.graph
import atom.app.ondevice.OnDevicePack
import atom.app.ondevice.PackOffer
import atom.app.ondevice.PackState
import atom.app.ui.common.formatBytes
import atom.ui.components.CompactButton
import atom.ui.components.Group
import atom.ui.components.GroupRow
import atom.ui.components.SectionLabel
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

/** The on-device AI pack's card at the top of On-device models: download, progress, ready, update, remove. */
internal fun LazyListScope.packItems() {
    item(key = "h-pack") { SectionLabel(stringResource(R.string.pack_section)) }
    item(key = "g-pack") { PackCard() }
}

@Composable
private fun PackCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by OnDevicePack.state.collectAsStateWithLifecycle()
    val offer by OnDevicePack.offer.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf(false) }

    // On demand only: the size of this version's pack (one GitHub request), and the engine names once installed.
    LaunchedEffect(state is PackState.Ready) {
        if (state is PackState.Ready) OnDevicePack.describe() else OnDevicePack.refreshOffer()
    }
    fun download() {
        scope.launch { OnDevicePack.download() }
    }

    Column {
        Group {
            when (val s = state) {
                PackState.Checking -> GroupRow(title = stringResource(R.string.pack_checking), enabled = false)
                PackState.Missing -> {
                    val size = (offer as? PackOffer.Available)?.bytes
                    GroupRow(
                        title = if (size != null) {
                            stringResource(R.string.pack_missing_title_size, formatBytes(size))
                        } else {
                            stringResource(R.string.pack_missing_title)
                        },
                        subtitle = if (offer == PackOffer.NotPublished) {
                            stringResource(R.string.pack_not_published)
                        } else {
                            stringResource(R.string.pack_missing_text)
                        },
                        icon = Icons.Filled.Download,
                        trailing = { CompactButton(stringResource(R.string.pack_download), onClick = ::download) },
                    )
                }
                is PackState.Downloading -> {
                    GroupRow(title = stringResource(R.string.pack_downloading, s.percent), icon = Icons.Filled.Download)
                    LinearProgressIndicator(
                        progress = { s.percent / 100f },
                        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 14.dp),
                    )
                }
                PackState.Verifying -> {
                    GroupRow(title = stringResource(R.string.pack_verifying), icon = Icons.Filled.Download)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 14.dp))
                }
                is PackState.Ready -> GroupRow(
                    title = stringResource(R.string.pack_ready),
                    subtitle = s.engines?.let { stringResource(R.string.pack_ready_text, it.joinToString(", "), formatBytes(s.bytes)) }
                        ?: stringResource(R.string.pack_loading_engines, formatBytes(s.bytes)),
                    icon = Icons.Filled.CheckCircle,
                    trailing = {
                        CompactButton(stringResource(R.string.pack_remove), onClick = { confirmRemove = true }, primary = false)
                    },
                )
                is PackState.Outdated -> GroupRow(
                    title = stringResource(R.string.pack_outdated),
                    subtitle = stringResource(R.string.pack_outdated_text),
                    icon = Icons.Filled.Refresh,
                    trailing = { CompactButton(stringResource(R.string.pack_update), onClick = ::download) },
                )
                is PackState.Failed -> GroupRow(
                    title = stringResource(R.string.pack_missing_title),
                    subtitle = stringResource(R.string.pack_failed, s.reason),
                    icon = Icons.Filled.Warning,
                    trailing = { CompactButton(stringResource(R.string.pack_retry), onClick = ::download) },
                )
            }
        }
        Text(
            stringResource(R.string.pack_source_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 8.dp),
        )
    }

    if (confirmRemove) {
        val bytes = (state as? PackState.Ready)?.bytes ?: 0L
        ConfirmDialog(
            title = stringResource(R.string.pack_remove_title),
            text = stringResource(R.string.pack_remove_text, formatBytes(bytes)),
            confirm = stringResource(R.string.pack_remove),
            onConfirm = {
                confirmRemove = false
                scope.launch {
                    // An idle on-device model holds the pack's code: let it go first.
                    context.graph.router.onTrimMemory()
                    OnDevicePack.remove()
                }
            },
            onDismiss = { confirmRemove = false },
        )
    }
}
