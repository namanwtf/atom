@file:OptIn(ExperimentalLayoutApi::class)

package atom.app.ui.models

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import atom.app.R
import atom.app.data.AppSettings
import atom.app.data.EngineSelection
import atom.ui.components.AtomSwitch
import atom.ui.components.BottomActionBar
import atom.ui.components.CircleIconButton
import atom.ui.components.Group
import atom.ui.components.GroupDivider
import atom.ui.components.GroupRow
import atom.ui.components.LargeTitleScreen
import atom.ui.components.Pill
import atom.ui.components.PrimaryButton
import atom.ui.components.RowChevron
import atom.ui.components.RowValue
import atom.ui.components.SecondaryButton
import atom.ui.components.SectionLabel
import atom.app.ui.common.formatBytes
import atom.app.ui.common.formatDuration
import atom.ui.components.AtomMenu
import atom.ui.components.AtomMenuItem
import atom.ui.components.CompactButton
import atom.ui.components.ScreenIcons
import atom.ui.components.SegmentedControl
import atom.ui.theme.AtomIcons
import atom.ui.theme.extras
import atom.llm.cloud.Provider
import atom.llm.cloud.Providers
import atom.models.CatalogModel
import atom.models.DownloadState
import atom.models.InstalledModel
import atom.models.ModelFormat

@Composable
fun ModelsScreen(vm: ModelsViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val hasHfToken by vm.hasHfToken.collectAsStateWithLifecycle()
    val linkModels by vm.linkModels.collectAsStateWithLifecycle()
    val linkError by vm.linkError.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val savedKeys by vm.savedKeys.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableIntStateOf(-1) }
    val selected = if (tab >= 0) tab else if (settings?.selection is EngineSelection.Cloud) 1 else 0
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    val cloud = cloudUi(vm, draft, savedKeys, settings)
    val segments = listOf(
        stringResource(R.string.tab_on_device) to AtomIcons.Chip,
        stringResource(R.string.tab_cloud) to AtomIcons.Cloud,
    )

    LargeTitleScreen(
        title = stringResource(R.string.screen_models),
        onBack = onBack,
        bottomBar = {
            if (selected == 1) {
                BottomActionBar {
                    PrimaryButton(
                        text = stringResource(if (cloud.active) R.string.model_active else R.string.action_use_model),
                        onClick = vm::useCloud,
                        enabled = !cloud.active && cloud.canUse,
                    )
                }
            }
        },
    ) {
        item(key = "segments") {
            SegmentedControl(segments, selected, onSelect = { tab = it }, modifier = Modifier.padding(top = 4.dp))
        }
        if (selected == 0) {
            localItems(
                vm = vm,
                settings = settings,
                installed = installed,
                hasHfToken = hasHfToken,
                linkModels = linkModels,
                linkError = linkError,
                onDelete = { confirmDelete = it },
            )
        } else {
            cloudItems(vm, draft, savedKeys, cloud)
        }
    }

    confirmDelete?.let { id ->
        val name = installed.firstOrNull { it.id == id }?.name ?: id
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.large,
            title = { Text(stringResource(R.string.delete_model_title)) },
            text = { Text(stringResource(R.string.delete_model_text, name), color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(id)
                    confirmDelete = null
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.onSurface)
                }
            },
        )
    }
}

// ================================================================ On-device

private fun LazyListScope.localItems(
    vm: ModelsViewModel,
    settings: AppSettings?,
    installed: List<InstalledModel>,
    hasHfToken: Boolean,
    linkModels: List<CatalogModel>,
    linkError: String?,
    onDelete: (String) -> Unit,
) {
    val activeLocalId = (settings?.selection as? EngineSelection.Local)?.installedModelId
    val catalogIds = vm.catalog.map { it.id }.toSet()
    val extraInstalled = installed.filter { it.id !in catalogIds && linkModels.none { l -> l.id == it.id } }

    item(key = "intro") {
        Text(
            stringResource(R.string.local_intro_text, vm.deviceRamGb),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp),
        )
    }

    if (extraInstalled.isNotEmpty()) {
        item(key = "h-installed") { SectionLabel(stringResource(R.string.section_installed)) }
        item(key = "g-installed") {
            Group {
                extraInstalled.forEachIndexed { i, model ->
                    if (i > 0) GroupDivider()
                    InstalledRow(
                        model,
                        active = model.id == activeLocalId,
                        onUse = { vm.use(model.id) },
                        onDelete = { onDelete(model.id) },
                    )
                }
            }
        }
    }

    item(key = "h-catalog") { SectionLabel(stringResource(R.string.section_catalog)) }
    items(vm.catalog, key = { "c-" + it.id }) { model ->
        ModelCard(
            vm = vm,
            model = model,
            installed = installed.firstOrNull { it.id == model.id },
            active = model.id == activeLocalId,
            hasHfToken = hasHfToken,
            onDelete = { onDelete(model.id) },
        )
    }

    if (linkModels.isNotEmpty()) {
        item(key = "h-links") { SectionLabel(stringResource(R.string.models_from_links)) }
        items(linkModels, key = { "l-" + it.id }) { model ->
            ModelCard(
                vm = vm,
                model = model,
                installed = installed.firstOrNull { it.id == model.id },
                active = model.id == activeLocalId,
                hasHfToken = hasHfToken,
                onDelete = { onDelete(model.id) },
            )
        }
    }

    item(key = "h-more") { SectionLabel(stringResource(R.string.models_more)) }
    item(key = "g-more") {
        Group(Modifier.animateContentSize(tween(200))) {
            AddFromLink(error = linkError, onAdd = vm::addFromLink)
            GroupDivider(inset = 0.dp)
            HfToken(hasToken = hasHfToken, onSave = vm::saveHfToken)
        }
    }

    item(key = "h-downloads") { SectionLabel(stringResource(R.string.models_downloads)) }
    item(key = "g-downloads") {
        val wifiOnly = settings?.wifiOnlyDownloads ?: true
        Group {
            GroupRow(
                title = stringResource(R.string.wifi_only),
                subtitle = stringResource(R.string.wifi_only_desc),
                icon = AtomIcons.Download,
                onClick = { vm.setWifiOnly(!wifiOnly) },
                trailing = { AtomSwitch(checked = wifiOnly, onCheckedChange = vm::setWifiOnly) },
            )
        }
    }
}

private data class CardActions(val state: DownloadState, val installed: Boolean, val active: Boolean, val needsToken: Boolean)

private enum class CardPhase { Running, Queued, Verifying, Installed, Active, Idle }

private fun CardActions.phase(): CardPhase = when {
    state is DownloadState.Running -> CardPhase.Running
    state is DownloadState.Queued -> CardPhase.Queued
    state is DownloadState.Verifying -> CardPhase.Verifying
    installed && active -> CardPhase.Active
    installed -> CardPhase.Installed
    else -> CardPhase.Idle
}

@Composable
private fun ModelCard(
    vm: ModelsViewModel,
    model: CatalogModel,
    installed: InstalledModel?,
    active: Boolean,
    hasHfToken: Boolean,
    onDelete: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val progressFlow = remember(model.id) { vm.progress(model.id) }
    val progress by progressFlow.collectAsStateWithLifecycle(initialValue = DownloadState.Idle)
    val tooBig = model.minRamGb > vm.deviceRamGb
    val needsToken = model.gated && !hasHfToken
    val shape = MaterialTheme.shapes.large

    Column(
        Modifier
            .padding(bottom = 10.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceContainer)
            .then(if (active) Modifier.border(1.dp, colors.onSurface.copy(alpha = 0.28f), shape) else Modifier)
            .animateContentSize(tween(200))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                model.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (model.summary.isNotBlank()) {
                Text(
                    model.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            QuietPill(formatLabel(model.format))
            QuietPill(formatBytes(model.sizeBytes))
            QuietPill(stringResource(R.string.min_ram, model.minRamGb))
            if (model.license.isNotBlank()) QuietPill(model.license)
            if (model.supportsVision) QuietPill(stringResource(R.string.badge_vision))
            if (model.gated) QuietPill(stringResource(R.string.badge_needs_hf))
        }
        if (tooBig) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, null, Modifier.size(16.dp), tint = MaterialTheme.extras.warning)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.ram_warning, model.minRamGb, vm.deviceRamGb),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.extras.warning,
                )
            }
        }
        AnimatedContent(
            targetState = CardActions(progress, installed != null, active, needsToken),
            contentKey = { it.phase() },
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) using null },
            label = "modelActions",
        ) { ui ->
            ModelActions(
                ui = ui,
                onDownload = { vm.download(model) },
                onCancel = { vm.cancel(model.id) },
                onUse = { vm.use(model.id) },
                onDelete = onDelete,
            )
        }
    }
}

@Composable
private fun ModelActions(
    ui: CardActions,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onUse: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val state = ui.state
    when (ui.phase()) {
        CardPhase.Running -> {
            val running = state as? DownloadState.Running
            val total = running?.totalBytes ?: 0L
            val done = running?.downloadedBytes ?: 0L
            val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                SlimProgress(fraction, Modifier.weight(1f))
                Spacer(Modifier.width(14.dp))
                Text(
                    if (fraction != null) {
                        stringResource(R.string.models_progress_short, (fraction * 100).toInt(), formatBytes(done))
                    } else {
                        formatBytes(done)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurface,
                    maxLines = 1,
                )
                Spacer(Modifier.width(12.dp))
                CompactButton(stringResource(R.string.action_cancel), onClick = onCancel, primary = false)
            }
        }
        CardPhase.Queued -> Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            SlimProgress(null, Modifier.weight(1f))
            Spacer(Modifier.width(14.dp))
            Text(stringResource(R.string.download_queued), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            CompactButton(stringResource(R.string.action_cancel), onClick = onCancel, primary = false)
        }
        CardPhase.Verifying -> Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            SlimProgress(null, Modifier.weight(1f))
            Spacer(Modifier.width(14.dp))
            Text(stringResource(R.string.download_verifying), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
        CardPhase.Active -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp).background(colors.primary, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, null, Modifier.size(15.dp), tint = colors.onPrimary)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.model_active),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            DeleteButton(onDelete)
        }
        CardPhase.Installed -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CompactButton(
                stringResource(R.string.action_use),
                onClick = onUse,
                icon = Icons.Filled.Check,
                modifier = Modifier.weight(1f).height(46.dp),
            )
            Spacer(Modifier.width(10.dp))
            DeleteButton(onDelete)
        }
        CardPhase.Idle -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state is DownloadState.Failed) {
                Text(stringResource(R.string.download_failed, state.message), style = MaterialTheme.typography.bodySmall, color = colors.error)
            }
            if (ui.needsToken) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, null, Modifier.size(15.dp), tint = colors.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.needs_hf_hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            val failed = state is DownloadState.Failed
            CompactButton(
                stringResource(if (failed) R.string.action_retry else R.string.action_download),
                onClick = onDownload,
                enabled = !ui.needsToken,
                icon = if (failed) Icons.Filled.Refresh else AtomIcons.Download,
                modifier = Modifier.fillMaxWidth().height(46.dp),
            )
        }
    }
}

@Composable
private fun DeleteButton(onDelete: () -> Unit) {
    CircleIconButton(
        Icons.Filled.Delete,
        stringResource(R.string.action_delete),
        onDelete,
        size = 42.dp,
        container = MaterialTheme.colorScheme.surfaceContainerHighest,
    )
}

/** 4dp white bar on a graphite track; indeterminate when [fraction] is null (only while a download is active). */
@Composable
private fun SlimProgress(fraction: Float?, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    if (fraction == null) {
        LinearProgressIndicator(
            modifier = modifier.height(4.dp),
            color = c.primary,
            trackColor = c.surfaceContainerHighest,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
        )
    } else {
        val shown by animateFloatAsState(fraction, tween(200), label = "progress")
        Box(modifier.height(4.dp).clip(RoundedCornerShape(50)).background(c.surfaceContainerHighest)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(shown).clip(RoundedCornerShape(50)).background(c.primary))
        }
    }
}

@Composable
private fun QuietPill(text: String) {
    Pill(
        text,
        MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun InstalledRow(model: InstalledModel, active: Boolean, onUse: () -> Unit, onDelete: () -> Unit) {
    GroupRow(
        title = model.name,
        subtitle = "${formatLabel(model.format)} · ${formatBytes(model.sizeBytes)}",
        icon = AtomIcons.Chip,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (active) {
                    Box(Modifier.size(24.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Check, stringResource(R.string.badge_in_use), Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    }
                } else {
                    CompactButton(stringResource(R.string.action_use), onClick = onUse, primary = false)
                }
                DeleteButton(onDelete)
            }
        },
    )
}

@Composable
private fun BlockHeader(icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(18.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge)
    }
    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun AddFromLink(error: String?, onAdd: (String) -> Boolean) {
    var url by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BlockHeader(AtomIcons.Link, stringResource(R.string.section_from_link), stringResource(R.string.link_desc))
        AtomTextField(
            value = url,
            onValueChange = { url = it },
            placeholder = stringResource(R.string.link_hint),
            isError = error != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
        )
        if (error != null) Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        CompactButton(
            stringResource(R.string.action_add_download),
            onClick = { if (onAdd(url)) url = "" },
            enabled = url.isNotBlank(),
            icon = AtomIcons.Download,
            modifier = Modifier.align(Alignment.End),
        )
    }
}

@Composable
private fun HfToken(hasToken: Boolean, onSave: (String?) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BlockHeader(ScreenIcons.Key, stringResource(R.string.section_hf), stringResource(R.string.hf_desc))
        if (hasToken && !editing) {
            SavedSecret(onReplace = { editing = true }, onRemove = { onSave(null) })
        } else {
            SecretField(value = token, onValueChange = { token = it }, placeholder = stringResource(R.string.hf_label))
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasToken) {
                    CompactButton(stringResource(R.string.action_cancel), primary = false, onClick = {
                        editing = false
                        token = ""
                    })
                }
                CompactButton(stringResource(R.string.action_save), enabled = token.isNotBlank(), onClick = {
                    onSave(token)
                    token = ""
                    editing = false
                })
            }
        }
    }
}

private fun formatLabel(format: ModelFormat) = when (format) {
    ModelFormat.LiteRtLm -> "LiteRT-LM"
    ModelFormat.Gguf -> "GGUF"
}

// ================================================================ Cloud

private data class CloudUi(
    val provider: Provider,
    val keySaved: Boolean,
    val isCustom: Boolean,
    val active: Boolean,
    val isFallback: Boolean,
    val canUse: Boolean,
)

private fun cloudUi(vm: ModelsViewModel, draft: CloudDraft, savedKeys: Set<String>, settings: AppSettings?): CloudUi {
    val provider = vm.providers.firstOrNull { it.id == draft.providerId } ?: vm.providers.first()
    val keySaved = provider.id in savedKeys
    val isCustom = provider.id == Providers.CUSTOM_ID
    val model = draft.model.trim()
    return CloudUi(
        provider = provider,
        keySaved = keySaved,
        isCustom = isCustom,
        active = (settings?.selection as? EngineSelection.Cloud)?.let { it.providerId == provider.id && it.model == model } == true,
        isFallback = settings?.cloudFallback?.let { it.providerId == provider.id && it.model == model } == true,
        canUse = draft.model.isNotBlank() && (keySaved || draft.keyInput.isNotBlank() || isCustom),
    )
}

private fun LazyListScope.cloudItems(vm: ModelsViewModel, draft: CloudDraft, savedKeys: Set<String>, ui: CloudUi) {
    val provider = ui.provider

    item(key = "h-provider") { SectionLabel(stringResource(R.string.cloud_provider)) }
    item(key = "g-provider") {
        Group {
            vm.providers.forEachIndexed { i, p ->
                if (i > 0) GroupDivider(inset = 64.dp)
                ProviderRow(
                    name = p.displayName,
                    keySaved = p.id in savedKeys,
                    selected = p.id == provider.id,
                    onClick = { vm.selectProvider(p.id) },
                )
            }
        }
    }

    if (ui.isCustom) {
        item(key = "h-base") { SectionLabel(stringResource(R.string.cloud_base_url)) }
        item(key = "g-base") {
            Group {
                Box(Modifier.padding(12.dp)) {
                    AtomTextField(
                        value = draft.baseUrl,
                        onValueChange = { v -> vm.editDraft { copy(baseUrl = v) } },
                        placeholder = "https://host/v1",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    )
                }
            }
        }
    }

    item(key = "h-key") { SectionLabel(stringResource(R.string.cloud_api_key)) }
    item(key = "g-key") {
        val uri = LocalUriHandler.current
        Group(Modifier.animateContentSize(tween(200))) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (ui.keySaved && !draft.editingKey) {
                    Box(Modifier.padding(4.dp)) {
                        SavedSecret(onReplace = { vm.editDraft { copy(editingKey = true) } }, onRemove = vm::removeKey)
                    }
                } else {
                    SecretField(
                        value = draft.keyInput,
                        onValueChange = { v -> vm.editDraft { copy(keyInput = v) } },
                        placeholder = stringResource(if (ui.isCustom) R.string.cloud_key_optional else R.string.cloud_key_label),
                    )
                    Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (ui.keySaved) {
                            CompactButton(stringResource(R.string.action_cancel), primary = false, onClick = {
                                vm.editDraft { copy(editingKey = false, keyInput = "") }
                            })
                        }
                        CompactButton(stringResource(R.string.action_save), onClick = vm::saveKey, enabled = draft.keyInput.isNotBlank())
                    }
                }
            }
            provider.keyHelpUrl?.let { url ->
                GroupDivider(inset = 0.dp)
                GroupRow(
                    title = stringResource(R.string.cloud_get_key),
                    icon = ScreenIcons.Key,
                    onClick = { runCatching { uri.openUri(url) } },
                    trailing = { RowChevron() },
                )
            }
        }
    }
    item(key = "n-key") { Note(stringResource(R.string.cloud_key_note)) }

    item(key = "h-model") { SectionLabel(stringResource(R.string.cloud_model)) }
    item(key = "g-model") {
        Group(Modifier.animateContentSize(tween(200))) {
            Box(Modifier.padding(12.dp)) {
                AtomTextField(
                    value = draft.model,
                    onValueChange = { v -> vm.editDraft { copy(model = v) } },
                    placeholder = stringResource(R.string.cloud_model_hint),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                )
            }
            if (draft.fetchedModels.isNotEmpty()) {
                GroupDivider(inset = 0.dp)
                ModelPickerRow(value = draft.model, options = draft.fetchedModels, onPick = { v -> vm.editDraft { copy(model = v) } })
            }
            if (provider.supportsModelList) {
                GroupDivider(inset = 0.dp)
                GroupRow(
                    title = stringResource(R.string.cloud_fetch_models),
                    icon = Icons.Filled.Refresh,
                    enabled = !draft.fetching && (ui.keySaved || draft.keyInput.isNotBlank() || ui.isCustom),
                    onClick = vm::fetchModels,
                    trailing = if (draft.fetching) {
                        { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    } else {
                        null
                    },
                )
                draft.fetchError?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 58.dp, end = 18.dp, bottom = 14.dp),
                    )
                }
            }
        }
    }

    item(key = "test") {
        Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton(
                stringResource(R.string.models_test_connection),
                onClick = vm::test,
                enabled = draft.test != TestState.Running && draft.model.isNotBlank(),
            )
            AnimatedContent(
                targetState = draft.test,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) using null },
                label = "testResult",
            ) { test -> TestResult(test) }
        }
    }

    item(key = "h-fallback") { SectionLabel(stringResource(R.string.settings_fallback)) }
    item(key = "g-fallback") {
        Group {
            GroupRow(
                title = stringResource(if (ui.isFallback) R.string.cloud_is_fallback else R.string.cloud_set_fallback),
                subtitle = stringResource(R.string.cloud_fallback_explain),
                icon = AtomIcons.Bolt,
                enabled = ui.isFallback || ui.canUse,
                onClick = if (ui.isFallback) null else vm::useAsFallback,
                trailing = if (ui.isFallback) {
                    { Icon(Icons.Filled.Check, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface) }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun ProviderRow(name: String, keySaved: Boolean, selected: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).background(c.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.Center) {
            Text(
                name.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = c.onSurface,
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (keySaved) {
                Text(stringResource(R.string.badge_key_saved), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
        }
        if (selected) {
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Filled.Check, null, Modifier.size(20.dp), tint = c.onSurface)
        }
    }
}

@Composable
private fun ModelPickerRow(value: String, options: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        GroupRow(
            title = stringResource(R.string.models_choose_from_list),
            icon = ScreenIcons.ChevronDown,
            onClick = { open = true },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RowValue(stringResource(R.string.cloud_models_found, options.size))
                    RowChevron()
                }
            },
        )
        AtomMenu(expanded = open, onDismiss = { open = false }) {
            options.take(200).forEach { option ->
                AtomMenuItem(text = option, checked = option == value.trim()) {
                    open = false
                    onPick(option)
                }
            }
        }
    }
}

@Composable
private fun TestResult(test: TestState) {
    val colors = MaterialTheme.colorScheme
    val pad = Modifier.padding(horizontal = 6.dp)
    when (test) {
        TestState.Idle -> Spacer(Modifier.height(0.dp))
        TestState.Running -> Row(pad, verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.test_running), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
        is TestState.Ok -> Row(pad, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp), tint = MaterialTheme.extras.success)
            Spacer(Modifier.width(10.dp))
            Text(
                if (test.reply.isEmpty()) stringResource(R.string.test_ok_empty, formatDuration(test.millis))
                else stringResource(R.string.test_ok, test.reply, formatDuration(test.millis)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.extras.success,
            )
        }
        is TestState.Failed -> Row(pad, verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Warning, null, Modifier.size(18.dp), tint = colors.error)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.test_failed, test.message), style = MaterialTheme.typography.bodyMedium, color = colors.error)
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 8.dp),
    )
}

// ================================================================ Fields

/** Graphite rounded field: no indicator line, no coloured focus, white cursor. */
@Composable
private fun FieldFrame(
    placeholder: String?,
    isEmpty: Boolean,
    isError: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    inner: @Composable () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(c.surfaceContainerHigh)
            .then(if (isError) Modifier.border(1.dp, c.error.copy(alpha = 0.7f), shape) else Modifier)
            .padding(start = 16.dp, end = if (trailing != null) 6.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
            if (isEmpty && placeholder != null) {
                Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant, maxLines = 1)
            }
            inner()
        }
        trailing?.invoke()
    }
}

@Composable
private fun AtomTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String?,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val c = MaterialTheme.colorScheme
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onSurface),
        cursorBrush = SolidColor(c.onSurface),
        keyboardOptions = keyboardOptions,
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner -> FieldFrame(placeholder, value.isEmpty(), isError) { inner() } },
    )
}

@Composable
private fun SecretField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    var visible by remember { mutableStateOf(false) }
    val state = rememberTextFieldState(value)
    val c = MaterialTheme.colorScheme
    // Mirror into the owner and accept external resets (e.g. cleared after saving).
    LaunchedEffect(state) { snapshotFlow { state.text.toString() }.collect { onValueChange(it) } }
    LaunchedEffect(value) { if (value != state.text.toString()) state.setTextAndPlaceCursorAtEnd(value) }
    BasicSecureTextField(
        state = state,
        textObfuscationMode = if (visible) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onSurface),
        cursorBrush = SolidColor(c.onSurface),
        modifier = Modifier.fillMaxWidth(),
        decorator = { inner ->
            FieldFrame(
                placeholder = placeholder,
                isEmpty = state.text.isEmpty(),
                trailing = {
                    Surface(
                        onClick = { visible = !visible },
                        shape = CircleShape,
                        color = c.surfaceContainerHigh,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (visible) AtomIcons.VisibilityOff else AtomIcons.Eye,
                                stringResource(if (visible) R.string.cd_hide_secret else R.string.cd_show_secret),
                                Modifier.size(20.dp),
                                tint = c.onSurfaceVariant,
                            )
                        }
                    }
                },
            ) { inner() }
        },
    )
}

@Composable
private fun SavedSecret(onReplace: () -> Unit, onRemove: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, null, Modifier.size(16.dp), tint = MaterialTheme.extras.success)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.secret_saved), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactButton(stringResource(R.string.action_replace), onClick = onReplace, primary = false)
            CompactButton(
                stringResource(R.string.action_remove),
                onClick = onRemove,
                primary = false,
                contentColor = MaterialTheme.colorScheme.error,
            )
        }
    }
}
