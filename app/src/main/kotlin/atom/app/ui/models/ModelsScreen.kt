@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package atom.app.ui.models

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import atom.app.R
import atom.app.data.EngineSelection
import atom.app.ui.common.AtomCard
import atom.app.ui.common.Pill
import atom.app.ui.common.SectionHeader
import atom.app.ui.common.formatBytes
import atom.app.ui.common.formatDuration
import atom.app.ui.theme.AtomIcons
import atom.app.ui.theme.extras
import atom.llm.cloud.Providers
import atom.models.CatalogModel
import atom.models.DownloadState
import atom.models.InstalledModel
import atom.models.ModelFormat

@Composable
fun ModelsScreen(vm: ModelsViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(-1) }
    val selected = if (tab >= 0) tab else if (settings?.selection is EngineSelection.Cloud) 1 else 0

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.screen_models)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
                PrimaryTabRow(selectedTabIndex = selected, containerColor = MaterialTheme.colorScheme.background) {
                    Tab(
                        selected = selected == 0,
                        onClick = { tab = 0 },
                        text = { Text(stringResource(R.string.tab_on_device)) },
                        icon = { Icon(AtomIcons.Chip, null, Modifier.size(18.dp)) },
                    )
                    Tab(
                        selected = selected == 1,
                        onClick = { tab = 1 },
                        text = { Text(stringResource(R.string.tab_cloud)) },
                        icon = { Icon(AtomIcons.Cloud, null, Modifier.size(18.dp)) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (selected == 0) LocalTab(vm) else CloudTab(vm)
        }
    }
}

// ================================================================ On-device

@Composable
private fun LocalTab(vm: ModelsViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val hasHfToken by vm.hasHfToken.collectAsStateWithLifecycle()
    val linkModels by vm.linkModels.collectAsStateWithLifecycle()
    val linkError by vm.linkError.collectAsStateWithLifecycle()
    val activeLocalId = (settings?.selection as? EngineSelection.Local)?.installedModelId
    val catalogIds = remember(vm.catalog) { vm.catalog.map { it.id }.toSet() }
    val extraInstalled = installed.filter { it.id !in catalogIds && linkModels.none { l -> l.id == it.id } }
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "intro") {
            AtomCard {
                Text(stringResource(R.string.local_intro_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.local_intro_text, vm.deviceRamGb),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.wifi_only), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.wifi_only_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = settings?.wifiOnlyDownloads ?: true, onCheckedChange = vm::setWifiOnly)
                }
            }
        }

        if (extraInstalled.isNotEmpty()) {
            item(key = "h-installed") { SectionHeader(stringResource(R.string.section_installed)) }
            items(extraInstalled, key = { "i-" + it.id }) { model ->
                InstalledRow(model, active = model.id == activeLocalId, onUse = { vm.use(model.id) }, onDelete = { confirmDelete = model.id })
            }
        }

        item(key = "h-catalog") { SectionHeader(stringResource(R.string.section_catalog)) }
        items(vm.catalog, key = { "c-" + it.id }) { model ->
            ModelCard(
                vm = vm,
                model = model,
                installed = installed.firstOrNull { it.id == model.id },
                active = model.id == activeLocalId,
                hasHfToken = hasHfToken,
                onDelete = { confirmDelete = model.id },
            )
        }

        item(key = "h-link") { SectionHeader(stringResource(R.string.section_from_link)) }
        item(key = "link") { AddFromLink(error = linkError, onAdd = vm::addFromLink) }
        items(linkModels, key = { "l-" + it.id }) { model ->
            ModelCard(
                vm = vm,
                model = model,
                installed = installed.firstOrNull { it.id == model.id },
                active = model.id == activeLocalId,
                hasHfToken = hasHfToken,
                onDelete = { confirmDelete = model.id },
            )
        }

        item(key = "h-hf") { SectionHeader(stringResource(R.string.section_hf)) }
        item(key = "hf") { HfTokenCard(hasToken = hasHfToken, onSave = vm::saveHfToken) }
    }

    confirmDelete?.let { id ->
        val name = installed.firstOrNull { it.id == id }?.name ?: id
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.delete_model_title)) },
            text = { Text(stringResource(R.string.delete_model_text, name)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(id)
                    confirmDelete = null
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
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

    Surface(
        color = colors.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        border = if (active) androidx.compose.foundation.BorderStroke(1.5.dp, colors.primary) else null,
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    model.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (active) {
                    Pill(stringResource(R.string.badge_in_use), colors.primary, contentColor = colors.onPrimary)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(formatLabel(model.format), colors.secondaryContainer, contentColor = colors.onSecondaryContainer)
                Pill(formatBytes(model.sizeBytes), colors.surfaceContainerHighest)
                Pill(stringResource(R.string.min_ram, model.minRamGb), colors.surfaceContainerHighest)
                if (model.license.isNotBlank()) Pill(model.license, colors.surfaceContainerHighest)
                if (model.supportsVision) Pill(stringResource(R.string.badge_vision), colors.tertiaryContainer, contentColor = colors.onTertiaryContainer)
                if (model.gated) Pill(stringResource(R.string.badge_needs_hf), colors.errorContainer, contentColor = colors.onErrorContainer)
            }
            if (model.summary.isNotBlank()) {
                Text(model.summary, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            if (tooBig) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, null, Modifier.size(16.dp), tint = MaterialTheme.extras.warning)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.ram_warning, model.minRamGb, vm.deviceRamGb),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.extras.warning,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            ModelActions(
                state = progress,
                installed = installed != null,
                active = active,
                needsToken = needsToken,
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
    state: DownloadState,
    installed: Boolean,
    active: Boolean,
    needsToken: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onUse: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    when {
        state is DownloadState.Running -> {
            val fraction = if (state.totalBytes > 0) (state.downloadedBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f) else 0f
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (state.totalBytes > 0) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (state.totalBytes > 0) {
                            stringResource(
                                R.string.download_progress,
                                formatBytes(state.downloadedBytes),
                                formatBytes(state.totalBytes),
                                (fraction * 100).toInt(),
                            )
                        } else {
                            formatBytes(state.downloadedBytes)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                }
            }
        }
        state is DownloadState.Queued -> Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.download_queued), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        }
        state is DownloadState.Verifying -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.download_verifying), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
        installed -> Row(verticalAlignment = Alignment.CenterVertically) {
            if (active) {
                Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp), tint = colors.primary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.model_active), style = MaterialTheme.typography.labelLarge, color = colors.primary, modifier = Modifier.weight(1f))
            } else {
                Button(onClick = onUse, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_use_model))
                }
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, stringResource(R.string.action_delete), tint = colors.onSurfaceVariant) }
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state is DownloadState.Failed) {
                Text(stringResource(R.string.download_failed, state.message), style = MaterialTheme.typography.bodySmall, color = colors.error)
            }
            if (needsToken) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.needs_hf_hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            Button(onClick = onDownload, enabled = !needsToken, modifier = Modifier.fillMaxWidth()) {
                Icon(if (state is DownloadState.Failed) Icons.Filled.Refresh else AtomIcons.Download, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (state is DownloadState.Failed) R.string.action_retry else R.string.action_download))
            }
        }
    }
}

@Composable
private fun InstalledRow(model: InstalledModel, active: Boolean, onUse: () -> Unit, onDelete: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(model.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${formatLabel(model.format)} · ${formatBytes(model.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            if (active) {
                Pill(stringResource(R.string.badge_in_use), colors.primary, contentColor = colors.onPrimary)
            } else {
                TextButton(onClick = onUse) { Text(stringResource(R.string.action_use)) }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, stringResource(R.string.action_delete), tint = colors.onSurfaceVariant) }
        }
    }
}

@Composable
private fun AddFromLink(error: String?, onAdd: (String) -> Boolean) {
    var url by rememberSaveable { mutableStateOf("") }
    AtomCard {
        Text(stringResource(R.string.link_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            singleLine = true,
            leadingIcon = { Icon(AtomIcons.Link, null) },
            placeholder = { Text(stringResource(R.string.link_hint)) },
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        FilledTonalButton(
            onClick = { if (onAdd(url)) url = "" },
            enabled = url.isNotBlank(),
            modifier = Modifier.align(Alignment.End),
        ) { Text(stringResource(R.string.action_add_download)) }
    }
}

@Composable
private fun HfTokenCard(hasToken: Boolean, onSave: (String?) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    AtomCard {
        Text(stringResource(R.string.hf_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (hasToken && !editing) {
            SavedSecretRow(onReplace = { editing = true }, onRemove = { onSave(null) })
        } else {
            SecretField(value = token, onValueChange = { token = it }, label = stringResource(R.string.hf_label))
            Row(Modifier.align(Alignment.End)) {
                if (hasToken) TextButton(onClick = { editing = false; token = "" }) { Text(stringResource(R.string.action_cancel)) }
                FilledTonalButton(onClick = {
                    onSave(token)
                    token = ""
                    editing = false
                }, enabled = token.isNotBlank()) { Text(stringResource(R.string.action_save)) }
            }
        }
    }
}

private fun formatLabel(format: ModelFormat) = when (format) {
    ModelFormat.LiteRtLm -> "LiteRT-LM"
    ModelFormat.Gguf -> "GGUF"
}

// ================================================================ Cloud

@Composable
private fun CloudTab(vm: ModelsViewModel) {
    val draft by vm.draft.collectAsStateWithLifecycle()
    val savedKeys by vm.savedKeys.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val provider = vm.providers.firstOrNull { it.id == draft.providerId } ?: vm.providers.first()
    val keySaved = provider.id in savedKeys
    val isCustom = provider.id == Providers.CUSTOM_ID
    val uri = LocalUriHandler.current
    val active = (settings?.selection as? EngineSelection.Cloud)?.let { it.providerId == provider.id && it.model == draft.model.trim() } == true
    val isFallback = settings?.cloudFallback?.let { it.providerId == provider.id && it.model == draft.model.trim() } == true

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AtomCard {
            Text(stringResource(R.string.cloud_provider), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Dropdown(
                value = provider.displayName,
                options = vm.providers.map { it.id to it.displayName },
                onSelect = vm::selectProvider,
                trailingBadge = { id -> if (id in savedKeys) stringResource(R.string.badge_key_saved) else null },
            )
            if (isCustom) {
                OutlinedTextField(
                    value = draft.baseUrl,
                    onValueChange = { v -> vm.editDraft { copy(baseUrl = v) } },
                    label = { Text(stringResource(R.string.cloud_base_url)) },
                    placeholder = { Text("https://host/v1") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        AtomCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.cloud_api_key), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                provider.keyHelpUrl?.let { url ->
                    TextButton(onClick = { runCatching { uri.openUri(url) } }) { Text(stringResource(R.string.cloud_get_key)) }
                }
            }
            if (keySaved && !draft.editingKey) {
                SavedSecretRow(onReplace = { vm.editDraft { copy(editingKey = true) } }, onRemove = vm::removeKey)
            } else {
                SecretField(
                    value = draft.keyInput,
                    onValueChange = { v -> vm.editDraft { copy(keyInput = v) } },
                    label = stringResource(if (isCustom) R.string.cloud_key_optional else R.string.cloud_key_label),
                )
                Row(Modifier.align(Alignment.End)) {
                    if (keySaved) TextButton(onClick = { vm.editDraft { copy(editingKey = false, keyInput = "") } }) { Text(stringResource(R.string.action_cancel)) }
                    FilledTonalButton(onClick = vm::saveKey, enabled = draft.keyInput.isNotBlank()) { Text(stringResource(R.string.action_save)) }
                }
            }
            Text(stringResource(R.string.cloud_key_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        AtomCard {
            Text(stringResource(R.string.cloud_model), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ModelField(
                value = draft.model,
                options = draft.fetchedModels,
                onValueChange = { v -> vm.editDraft { copy(model = v) } },
            )
            if (provider.supportsModelList) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = vm::fetchModels, enabled = !draft.fetching && (keySaved || draft.keyInput.isNotBlank() || isCustom)) {
                        if (draft.fetching) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.cloud_fetch_models))
                    }
                    if (draft.fetchedModels.isNotEmpty()) {
                        Text(
                            stringResource(R.string.cloud_models_found, draft.fetchedModels.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                draft.fetchError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }

        TestResult(draft.test)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = vm::test,
                enabled = draft.test != TestState.Running && draft.model.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.action_test)) }
            Button(
                onClick = vm::useCloud,
                enabled = !active && draft.model.isNotBlank() && (keySaved || draft.keyInput.isNotBlank() || isCustom),
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(if (active) R.string.model_active else R.string.action_use_model)) }
        }
        TextButton(
            onClick = vm::useAsFallback,
            enabled = !isFallback && draft.model.isNotBlank() && (keySaved || draft.keyInput.isNotBlank() || isCustom),
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) { Text(stringResource(if (isFallback) R.string.cloud_is_fallback else R.string.cloud_set_fallback)) }
        Text(
            stringResource(R.string.cloud_fallback_explain),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun TestResult(test: TestState) {
    val colors = MaterialTheme.colorScheme
    when (test) {
        TestState.Idle -> Unit
        TestState.Running -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.test_running), style = MaterialTheme.typography.bodyMedium)
        }
        is TestState.Ok -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp), tint = MaterialTheme.extras.success)
            Spacer(Modifier.width(8.dp))
            Text(
                if (test.reply.isEmpty()) stringResource(R.string.test_ok_empty, formatDuration(test.millis))
                else stringResource(R.string.test_ok, test.reply, formatDuration(test.millis)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        is TestState.Failed -> Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(horizontal = 4.dp)) {
            Icon(Icons.Filled.Warning, null, Modifier.size(18.dp), tint = colors.error)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.test_failed, test.message), style = MaterialTheme.typography.bodyMedium, color = colors.error)
        }
    }
}

// ================================================================ Shared bits

@Composable
private fun SecretField(value: String, onValueChange: (String) -> Unit, label: String) {
    var visible by remember { mutableStateOf(false) }
    val state = rememberTextFieldState(value)
    // Mirror into the owner and accept external resets (e.g. cleared after saving).
    LaunchedEffect(state) { snapshotFlow { state.text.toString() }.collect { onValueChange(it) } }
    LaunchedEffect(value) { if (value != state.text.toString()) state.setTextAndPlaceCursorAtEnd(value) }
    OutlinedSecureTextField(
        state = state,
        label = { Text(label) },
        textObfuscationMode = if (visible) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) AtomIcons.VisibilityOff else AtomIcons.Eye,
                    stringResource(if (visible) R.string.cd_hide_secret else R.string.cd_show_secret),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SavedSecretRow(onReplace: () -> Unit, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Lock, null, Modifier.size(16.dp), tint = MaterialTheme.extras.success)
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.secret_saved),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onReplace) { Text(stringResource(R.string.action_replace)) }
        TextButton(onClick = onRemove) { Text(stringResource(R.string.action_remove), color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun Dropdown(
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    trailingBadge: @Composable (String) -> String? = { null },
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().clickable { open = true },
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, null)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, label) ->
                val badge = trailingBadge(id)
                DropdownMenuItem(
                    text = { Text(label) },
                    trailingIcon = badge?.let { { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) } },
                    onClick = {
                        open = false
                        onSelect(id)
                    },
                )
            }
        }
    }
}

@Composable
private fun ModelField(value: String, options: List<String>, onValueChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.cloud_model_hint)) },
            trailingIcon = if (options.isNotEmpty()) {
                { IconButton(onClick = { open = true }) { Icon(Icons.Filled.ArrowDropDown, stringResource(R.string.cd_pick_model)) } }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.take(200).forEach { option ->
                DropdownMenuItem(text = { Text(option, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = {
                    open = false
                    onValueChange(option)
                })
            }
        }
    }
}
