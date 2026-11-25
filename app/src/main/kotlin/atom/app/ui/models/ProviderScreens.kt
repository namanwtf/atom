@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package atom.app.ui.models

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import atom.app.R
import atom.app.data.EngineSelection
import atom.app.engine.AccountState
import atom.app.engine.SubscriptionAuth
import atom.core.llm.ReasoningEffort
import atom.llm.cloud.AuthMethod
import atom.llm.cloud.CapabilitySource
import atom.llm.cloud.CatalogProvider
import atom.llm.cloud.HeaderEntry
import atom.llm.cloud.ModelConfig
import atom.llm.cloud.PresetCatalog
import atom.llm.cloud.Protocol
import atom.llm.cloud.ProviderConfig
import atom.llm.cloud.ReasoningMode
import atom.llm.cloud.RequestCustomizer
import atom.llm.cloud.Wire
import atom.ui.components.AtomMenu
import atom.ui.components.AtomMenuItem
import atom.ui.components.AtomSwitch
import atom.ui.components.BottomActionBar
import atom.ui.components.CircleIconButton
import atom.ui.components.CompactButton
import atom.ui.components.Group
import atom.ui.components.GroupDivider
import atom.ui.components.GroupRow
import atom.ui.components.LargeTitleScreen
import atom.ui.components.PrimaryButton
import atom.ui.components.RowChevron
import atom.ui.components.ScreenIcons
import atom.ui.components.SecondaryButton
import atom.ui.components.SectionLabel
import atom.ui.components.SegmentedControl
import atom.ui.theme.AtomIcons
import atom.ui.theme.extras
import kotlinx.coroutines.delay

/** How long a page waits for its provider or model to appear (after a rename or import) before leaving. */
private const val MISSING_GRACE_MS = 400L

// ================================================================ Provider detail

@Composable
internal fun ProviderScreen(vm: ModelsViewModel, nav: ModelsNav, providerId: String, onBack: () -> Unit) {
    val providers by vm.providers.collectAsStateWithLifecycle()
    val config = providers.firstOrNull { it.id == providerId }
    DisposableEffect(providerId) {
        vm.openProvider(providerId)
        onDispose { vm.closeProvider() }
    }
    if (config == null) {
        LaunchedEffect(Unit) {
            delay(MISSING_GRACE_MS)
            onBack()
        }
        return
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val copySuffix = stringResource(R.string.mdl_copy_suffix)

    LargeTitleScreen(
        title = config.displayName,
        onBack = onBack,
        actions = {
            Box {
                CircleIconButton(Icons.Filled.MoreVert, stringResource(R.string.mdl_more), { menu = true }, modifier = Modifier.padding(end = 8.dp))
                AtomMenu(expanded = menu, onDismiss = { menu = false }) {
                    AtomMenuItem(stringResource(R.string.mdl_duplicate), checked = false) {
                        menu = false
                        vm.duplicateProvider(providerId, copySuffix) { nav.replaceWith(ModelsPage.Provider(it)) }
                    }
                    if (config.builtIn) {
                        AtomMenuItem(stringResource(R.string.mdl_reset), checked = false) {
                            menu = false
                            confirm = "reset"
                        }
                    }
                    AtomMenuItem(stringResource(R.string.mdl_remove_provider), checked = false) {
                        menu = false
                        confirm = "remove"
                    }
                }
            }
        },
    ) {
        item(key = "tabs") {
            SegmentedControl(
                listOf(stringResource(R.string.mdl_tab_settings) to ScreenIcons.Tune, stringResource(R.string.mdl_tab_models) to AtomIcons.Chip),
                tab,
                onSelect = { tab = it },
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (tab == 0) providerSettingsItems(vm, config) else providerModelItems(vm, nav, config)
    }

    when (confirm) {
        "remove" -> ConfirmDialog(
            title = stringResource(R.string.mdl_remove_title),
            text = stringResource(R.string.mdl_remove_text, config.displayName),
            confirm = stringResource(R.string.action_remove),
            onConfirm = {
                vm.removeProvider(providerId)
                onBack()
            },
            onDismiss = { confirm = null },
        )
        "reset" -> ConfirmDialog(
            title = stringResource(R.string.mdl_reset_title),
            text = stringResource(R.string.mdl_reset_text),
            confirm = stringResource(R.string.mdl_reset_confirm),
            onConfirm = { vm.resetProvider(providerId) },
            onDismiss = { confirm = null },
        )
    }
}

private fun LazyListScope.providerSettingsItems(vm: ModelsViewModel, config: ProviderConfig) {
    val id = config.id
    val preset = config.preset

    item(key = "g-basic") {
        var name by remember(id) { mutableStateOf(config.displayName) }
        Group(Modifier.padding(top = 18.dp)) {
            GroupRow(
                title = stringResource(R.string.mdl_enabled),
                subtitle = stringResource(R.string.mdl_enabled_sub),
                onClick = { vm.edit(id) { it.copy(enabled = !it.enabled) } },
                trailing = { AtomSwitch(checked = config.enabled, onCheckedChange = { on -> vm.edit(id) { it.copy(enabled = on) } }) },
            )
            GroupDivider(inset = 18.dp)
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldLabel(stringResource(R.string.mdl_display_name))
                AtomTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        if (it.isNotBlank()) vm.edit(id) { c -> c.copy(displayName = it.trim()) }
                    },
                    placeholder = stringResource(R.string.mdl_display_name),
                )
            }
        }
    }

    val methods = preset?.authMethods ?: listOf(AuthMethod.ApiKey)
    if (methods.size > 1) {
        item(key = "h-auth") { SectionLabel(stringResource(R.string.mdl_auth_method)) }
        item(key = "auth") {
            val labels = methods.map { stringResource(authLabel(it)) to null }
            SegmentedControl(labels, methods.indexOf(config.auth).coerceAtLeast(0), onSelect = { vm.setAuth(id, methods[it]) })
        }
    }

    if (config.auth == AuthMethod.ApiKey) {
        item(key = "h-key") { SectionLabel(stringResource(R.string.cloud_api_key)) }
        item(key = "g-key") { KeyGroup(vm, config) }
    } else {
        item(key = "h-account") { SectionLabel(stringResource(R.string.mdl_account)) }
        item(key = "g-account") { AccountGroup(vm, config) }
    }

    item(key = "h-conn") { SectionLabel(stringResource(R.string.mdl_connection)) }
    item(key = "g-conn") { ConnectionGroup(vm, config) }

    val responses = config.wire == Wire.OpenAiResponses && config.auth == AuthMethod.ApiKey
    val gemini = config.wire.protocol == Protocol.Gemini
    if (responses || gemini || config.auth == AuthMethod.ChatGpt) {
        item(key = "h-search") { SectionLabel(stringResource(R.string.mdl_hosted_tools)) }
        item(key = "g-search") {
            Group {
                if (gemini) {
                    GroupRow(
                        title = stringResource(R.string.mdl_google_search),
                        subtitle = stringResource(R.string.mdl_google_search_sub),
                        onClick = { vm.edit(id) { it.copy(googleSearch = !it.googleSearch) } },
                        trailing = { AtomSwitch(checked = config.googleSearch, onCheckedChange = { on -> vm.edit(id) { it.copy(googleSearch = on) } }) },
                    )
                } else {
                    GroupRow(
                        title = stringResource(R.string.mdl_hosted_search),
                        subtitle = stringResource(R.string.mdl_hosted_search_sub),
                        onClick = { vm.edit(id) { it.copy(hostedWebSearch = !it.hostedWebSearch) } },
                        trailing = { AtomSwitch(checked = config.hostedWebSearch, onCheckedChange = { on -> vm.edit(id) { it.copy(hostedWebSearch = on) } }) },
                    )
                }
            }
        }
    }

    item(key = "h-prompt") { SectionLabel(stringResource(R.string.mdl_system_prompt)) }
    item(key = "g-prompt") {
        var prompt by remember(id) { mutableStateOf(config.systemPrompt) }
        Group {
            Box(Modifier.padding(12.dp)) {
                AtomTextField(
                    value = prompt,
                    onValueChange = {
                        prompt = it
                        vm.edit(id) { c -> c.copy(systemPrompt = it) }
                    },
                    placeholder = stringResource(R.string.mdl_system_prompt_hint),
                    singleLine = false,
                )
            }
        }
    }
    item(key = "n-prompt") { Note(stringResource(R.string.mdl_system_prompt_note)) }

    item(key = "h-headers") { SectionLabel(stringResource(R.string.mdl_headers)) }
    item(key = "g-headers") { HeadersGroup(vm, config) }

    item(key = "h-body") { SectionLabel(stringResource(R.string.mdl_extra_body)) }
    item(key = "g-body") {
        var body by remember(id) { mutableStateOf(config.extraBody) }
        val invalid = RequestCustomizer.parseExtraBody(body).isFailure
        Group {
            Box(Modifier.padding(12.dp)) {
                AtomTextField(
                    value = body,
                    onValueChange = {
                        body = it
                        if (RequestCustomizer.parseExtraBody(it).isSuccess) vm.edit(id) { c -> c.copy(extraBody = it.trim()) }
                    },
                    placeholder = "{\"top_p\": 0.9}",
                    singleLine = false,
                    mono = true,
                    isError = invalid,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                )
            }
        }
        Note(
            stringResource(if (invalid) R.string.mdl_extra_body_invalid else R.string.mdl_extra_body_note),
            color = if (invalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    item(key = "test") { TestBlock(vm, config) }
}

private fun authLabel(method: AuthMethod) = when (method) {
    AuthMethod.ApiKey -> R.string.mdl_auth_key
    AuthMethod.ChatGpt -> R.string.mdl_auth_chatgpt
    AuthMethod.Grok -> R.string.mdl_auth_grok
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
}

@Composable
private fun KeyGroup(vm: ModelsViewModel, config: ProviderConfig) {
    val draft by vm.keyDraft.collectAsStateWithLifecycle()
    val saved by vm.savedKeys.collectAsStateWithLifecycle()
    val unreadable by vm.unreadable.collectAsStateWithLifecycle()
    val key = draft?.takeIf { it.first == config.id }?.second.orEmpty()
    val hasSaved = config.id in saved
    var savedValue by remember(config.id, hasSaved) { mutableStateOf<String?>(null) }
    LaunchedEffect(draft, hasSaved) { if (savedValue == null && draft?.first == config.id) savedValue = if (hasSaved) key else "" }
    val dirty = savedValue != null && key != savedValue
    Group(Modifier.animateContentSize(tween(200))) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (config.id in unreadable && !hasSaved) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, null, Modifier.size(16.dp), tint = MaterialTheme.extras.warning)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.mdl_key_unreadable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extras.warning)
                }
            }
            SecretField(
                value = key,
                onValueChange = { vm.editKey(config.id, it) },
                placeholder = stringResource(if (config.keyOptional) R.string.cloud_key_optional else R.string.cloud_key_label),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasSaved) {
                    Icon(Icons.Filled.Lock, null, Modifier.size(14.dp), tint = MaterialTheme.extras.success)
                    Text(stringResource(R.string.secret_saved), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                if (hasSaved) {
                    CompactButton(stringResource(R.string.action_remove), onClick = {
                        vm.removeKey(config.id)
                        savedValue = ""
                    }, primary = false, contentColor = MaterialTheme.colorScheme.error)
                }
                CompactButton(stringResource(R.string.action_save), enabled = dirty, onClick = {
                    vm.saveKey(config.id)
                    savedValue = key
                })
            }
        }
        config.keyHelpUrl?.let { url ->
            GroupDivider(inset = 0.dp)
            GroupRow(
                title = stringResource(R.string.cloud_get_key),
                icon = ScreenIcons.Key,
                onClick = { vm.openUrl(url) },
                trailing = { RowChevron() },
            )
        }
    }
    Note(stringResource(R.string.cloud_key_note))
}

@Composable
private fun AccountGroup(vm: ModelsViewModel, config: ProviderConfig) {
    val states by vm.accountStates.collectAsStateWithLifecycle()
    val state = states[config.id] ?: AccountState.SignedOut
    val clipboard = LocalClipboardManager.current
    val c = MaterialTheme.colorScheme
    val method = config.auth
    Group(Modifier.animateContentSize(tween(200))) {
        Row(Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Warning, null, Modifier.size(18.dp), tint = MaterialTheme.extras.warning)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.mdl_account_risk), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extras.warning)
        }
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AnimatedContent(state, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) }, label = "account") { s ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (s) {
                        AccountState.SignedOut -> {
                            Text(stringResource(R.string.mdl_account_signed_out), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                            CompactButton(
                                stringResource(if (method == AuthMethod.ChatGpt) R.string.mdl_sign_in_chatgpt else R.string.mdl_sign_in_grok),
                                onClick = { vm.signIn(config.id, method) },
                            )
                        }
                        AccountState.Working -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.onSurfaceVariant)
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.mdl_account_working), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                        }
                        is AccountState.AwaitingBrowser -> {
                            Text(stringResource(R.string.mdl_account_browser), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CompactButton(stringResource(R.string.mdl_open_browser), onClick = { vm.openUrl(s.url) })
                                CompactButton(stringResource(R.string.action_cancel), onClick = { vm.cancelSignIn(config.id) }, primary = false)
                            }
                        }
                        is AccountState.DeviceCode -> {
                            Text(stringResource(R.string.mdl_device_code_hint), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                            Text(s.userCode, style = MaterialTheme.extras.mono.copy(fontWeight = FontWeight.SemiBold), color = c.onSurface)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CompactButton(stringResource(R.string.mdl_open_browser), onClick = { vm.openUrl(s.verificationUrl) })
                                CompactButton(stringResource(R.string.mdl_copy_code), onClick = { clipboard.setText(AnnotatedString(s.userCode)) }, primary = false)
                                CompactButton(stringResource(R.string.action_cancel), onClick = { vm.cancelSignIn(config.id) }, primary = false)
                            }
                        }
                        is AccountState.SignedIn -> {
                            Text(
                                listOfNotNull(s.email ?: stringResource(R.string.mdl_account_signed_in), s.plan?.replaceFirstChar { it.uppercase() }).joinToString(" · "),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(pluralStringResource(R.plurals.mdl_synced_models, s.models, s.models), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                CompactButton(stringResource(R.string.mdl_sync_models), onClick = { vm.syncAccountModels(config.id) }, primary = false)
                                CompactButton(stringResource(R.string.mdl_reauthorize), onClick = { vm.signIn(config.id, method, reauthorize = true) }, primary = false)
                                CompactButton(
                                    stringResource(R.string.mdl_manage_usage),
                                    onClick = { vm.openUrl(if (method == AuthMethod.ChatGpt) SubscriptionAuth.CHATGPT_USAGE_URL else SubscriptionAuth.GROK_USAGE_URL) },
                                    primary = false,
                                )
                                CompactButton(stringResource(R.string.mdl_sign_out), onClick = { vm.signOut(config.id) }, primary = false, contentColor = c.error)
                            }
                        }
                        is AccountState.Failed -> {
                            Text(s.message, style = MaterialTheme.typography.bodyMedium, color = c.error)
                            CompactButton(stringResource(R.string.mdl_try_again), onClick = { vm.signIn(config.id, method) })
                        }
                    }
                }
            }
        }
    }
    Note(stringResource(R.string.mdl_account_note))
}

@Composable
private fun ConnectionGroup(vm: ModelsViewModel, config: ProviderConfig) {
    val id = config.id
    val account = config.auth != AuthMethod.ApiKey
    var url by remember(id) { mutableStateOf(config.baseUrl) }
    var project by remember(id) { mutableStateOf(config.vertexProject) }
    var location by remember(id) { mutableStateOf(config.vertexLocation) }
    Group {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (config.wire.protocol == Protocol.OpenAi && !account) {
                FieldLabel(stringResource(R.string.mdl_endpoint_mode))
                val modes = listOf(Wire.OpenAiChat, Wire.OpenAiResponses)
                SegmentedControl(
                    modes.map { protocolLabel(it) to null },
                    modes.indexOf(config.wire).coerceAtLeast(0),
                    onSelect = { i -> vm.edit(id) { it.copy(wire = modes[i]) } },
                )
            }
            if (config.wire.protocol == Protocol.Gemini) {
                FieldLabel(stringResource(R.string.mdl_gemini_mode))
                val modes = listOf(Wire.Gemini, Wire.Vertex)
                SegmentedControl(
                    modes.map { protocolLabel(it) to null },
                    modes.indexOf(config.wire).coerceAtLeast(0),
                    onSelect = { i ->
                        val wire = modes[i]
                        vm.edit(id) {
                            it.copy(
                                wire = wire,
                                baseUrl = if (wire == Wire.Gemini && it.baseUrl.isBlank()) PresetCatalog.GEMINI_BASE_URL else if (wire == Wire.Vertex && it.baseUrl == PresetCatalog.GEMINI_BASE_URL) "" else it.baseUrl,
                                supportsModelList = wire == Wire.Gemini,
                            )
                        }
                        url = if (wire == Wire.Vertex && url == PresetCatalog.GEMINI_BASE_URL) "" else if (wire == Wire.Gemini && url.isBlank()) PresetCatalog.GEMINI_BASE_URL else url
                    },
                )
            }
            FieldLabel(stringResource(if (config.wire == Wire.Vertex) R.string.mdl_vertex_proxy else R.string.cloud_base_url))
            if (account) {
                Text(stringResource(R.string.mdl_account_endpoint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
            } else {
                AtomTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        vm.edit(id) { c -> c.copy(baseUrl = it.trim().trimEnd('/')) }
                    },
                    placeholder = if (config.wire == Wire.Vertex) "aiplatform.googleapis.com" else "https://host/v1",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                )
            }
            if (config.wire == Wire.Vertex) {
                FieldLabel(stringResource(R.string.mdl_vertex_project))
                AtomTextField(
                    value = project,
                    onValueChange = {
                        project = it
                        vm.edit(id) { c -> c.copy(vertexProject = it.trim()) }
                    },
                    placeholder = stringResource(R.string.mdl_vertex_project_hint),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                )
                FieldLabel(stringResource(R.string.mdl_vertex_location))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PresetCatalog.vertexLocations.forEach { loc ->
                        CompactButton(loc, onClick = {
                            location = loc
                            vm.edit(id) { it.copy(vertexLocation = loc) }
                        }, primary = location == loc)
                    }
                }
                AtomTextField(
                    value = location,
                    onValueChange = {
                        location = it
                        vm.edit(id) { c -> c.copy(vertexLocation = it.trim().ifEmpty { "global" }) }
                    },
                    placeholder = "global",
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                )
            }
        }
    }
    if (config.wire == Wire.Vertex) Note(stringResource(R.string.mdl_vertex_note))
}

@Composable
private fun HeadersGroup(vm: ModelsViewModel, config: ProviderConfig) {
    var name by remember(config.id) { mutableStateOf("") }
    var value by remember(config.id) { mutableStateOf("") }
    val protectedName = RequestCustomizer.isProtectedHeader(name)
    val validName = RequestCustomizer.isValidHeaderName(name.trim())
    Group(Modifier.animateContentSize(tween(200))) {
        config.headers.forEachIndexed { i, header ->
            if (i > 0) GroupDivider(inset = 18.dp)
            GroupRow(
                title = header.name,
                subtitle = header.value.ifEmpty { "—" },
                trailing = {
                    CircleIconButton(
                        Icons.Filled.Close,
                        stringResource(R.string.action_remove),
                        { vm.edit(config.id) { c -> c.copy(headers = c.headers.filterIndexed { j, _ -> j != i }) } },
                        size = 36.dp,
                        container = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                },
            )
        }
        if (config.headers.isNotEmpty()) GroupDivider(inset = 0.dp)
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AtomTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = stringResource(R.string.mdl_header_name),
                isError = name.isNotBlank() && (protectedName || !validName),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
            )
            AtomTextField(
                value = value,
                onValueChange = { value = it },
                placeholder = stringResource(R.string.mdl_header_value),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            )
            if (protectedName) Text(stringResource(R.string.mdl_header_protected), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            CompactButton(
                stringResource(R.string.mdl_add_header),
                icon = Icons.Filled.Add,
                enabled = validName && !protectedName,
                onClick = {
                    val entry = HeaderEntry(name.trim(), value)
                    vm.edit(config.id) { c -> c.copy(headers = c.headers.filterNot { it.name.equals(entry.name, ignoreCase = true) } + entry) }
                    name = ""
                    value = ""
                },
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
    Note(stringResource(R.string.mdl_headers_note))
}

@Composable
private fun TestBlock(vm: ModelsViewModel, config: ProviderConfig) {
    val tests by vm.tests.collectAsStateWithLifecycle()
    val test = tests[config.id] ?: TestState.Idle
    var model by remember(config.id) { mutableStateOf(config.primaryModel) }
    var open by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(
                stringResource(R.string.models_test_connection),
                onClick = { vm.test(config.id, model) },
                enabled = test != TestState.Running && model.isNotBlank(),
            )
            if (config.models.size > 1) {
                Box {
                    CompactButton(
                        config.model(model)?.displayName ?: model,
                        onClick = { open = true },
                        primary = false,
                        icon = ScreenIcons.ChevronDown,
                    )
                    AtomMenu(expanded = open, onDismiss = { open = false }) {
                        config.models.take(100).forEach { m ->
                            AtomMenuItem(m.displayName, checked = m.id == model) {
                                model = m.id
                                open = false
                            }
                        }
                    }
                }
            }
        }
        if (model.isBlank()) Note(stringResource(R.string.mdl_test_needs_model), Modifier.padding(top = 0.dp))
        AnimatedContent(test, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) using null }, label = "test") { TestResult(it) }
    }
}

// ================================================================ Models tab

private fun LazyListScope.providerModelItems(vm: ModelsViewModel, nav: ModelsNav, config: ProviderConfig) {
    item(key = "models-body") { ProviderModels(vm, nav, config) }
}

@Composable
private fun ProviderModels(vm: ModelsViewModel, nav: ModelsNav, config: ProviderConfig) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val pulls by vm.pulls.collectAsStateWithLifecycle()
    val pull = pulls[config.id]
    var query by rememberSaveable(config.id) { mutableStateOf("") }
    var selected by remember(config.id) { mutableStateOf(emptySet<String>()) }
    var adding by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val current = settings?.selection as? EngineSelection.Cloud
    val q = query.trim().lowercase()
    val shown = config.models.filter { q.isEmpty() || it.id.lowercase().contains(q) || it.displayName.lowercase().contains(q) }

    Column {
        SearchField(query, { query = it }, stringResource(R.string.mdl_search_models), Modifier.padding(top = 14.dp))

        SectionLabel(stringResource(R.string.mdl_manage_models))
        Group(Modifier.animateContentSize(tween(200))) {
            if (config.toProvider().supportsModelList && config.auth == AuthMethod.ApiKey) {
                GroupRow(
                    title = stringResource(R.string.mdl_pull_models),
                    subtitle = stringResource(R.string.mdl_pull_models_sub),
                    icon = Icons.Filled.Refresh,
                    enabled = pull?.loading != true,
                    onClick = { vm.pullModels(config.id) },
                    trailing = if (pull?.loading == true) {
                        { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    } else {
                        null
                    },
                )
                pull?.error?.let { Note(it, Modifier.padding(start = 40.dp, bottom = 12.dp), color = MaterialTheme.colorScheme.error) }
                pull?.fresh?.let { fresh ->
                    GroupDivider(inset = 18.dp)
                    if (fresh.isEmpty()) {
                        GroupRow(title = stringResource(R.string.mdl_pull_none))
                    } else {
                        GroupRow(
                            title = pluralStringResource(R.plurals.mdl_new_models, fresh.size, fresh.size),
                            onClick = { picking = true },
                            trailing = {
                                CompactButton(
                                    pluralStringResource(R.plurals.mdl_add_n, fresh.size, fresh.size),
                                    onClick = { vm.addPulled(config.id, fresh.map { it.id }.toSet()) },
                                )
                            },
                        )
                    }
                }
                GroupDivider(inset = 0.dp)
            }
            GroupRow(
                title = stringResource(R.string.mdl_add_custom_model),
                icon = Icons.Filled.Add,
                onClick = { adding = true },
                trailing = { RowChevron() },
            )
        }

        if (selected.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 18.dp, start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(pluralStringResource(R.plurals.mdl_selected, selected.size, selected.size), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                CompactButton(stringResource(R.string.mdl_select_all), onClick = { selected = shown.map { it.id }.toSet() }, primary = false)
                Spacer(Modifier.width(8.dp))
                CompactButton(stringResource(R.string.action_cancel), onClick = { selected = emptySet() }, primary = false)
                Spacer(Modifier.width(8.dp))
                CompactButton(stringResource(R.string.action_delete), onClick = { confirmDelete = true }, icon = Icons.Filled.Delete, contentColor = MaterialTheme.colorScheme.error, primary = false)
            }
        }

        SectionLabel(pluralStringResource(R.plurals.mdl_model_count, config.models.size, config.models.size))
        Group {
            if (shown.isEmpty()) GroupRow(title = stringResource(if (config.models.isEmpty()) R.string.mdl_no_models else R.string.mdl_no_match))
            shown.forEachIndexed { i, model ->
                if (i > 0) GroupDivider(inset = 18.dp)
                ModelRow(
                    model = model,
                    current = current?.providerId == config.id && current.model == model.id,
                    selecting = selected.isNotEmpty(),
                    selected = model.id in selected,
                    onClick = {
                        if (selected.isNotEmpty()) selected = selected.toggle(model.id) else nav.open(ModelsPage.Model(config.id, model.id))
                    },
                    onLongClick = { selected = selected.toggle(model.id) },
                )
            }
        }
        Note(stringResource(R.string.mdl_models_hint))
    }

    if (adding) AddModelDialog(onAdd = { name, id -> vm.addCustomModel(config.id, name, id) }, onDismiss = { adding = false })
    if (picking) {
        PickPulledDialog(
            models = pull?.fresh.orEmpty().map { it.id to (it.displayName ?: it.id) },
            onAdd = { vm.addPulled(config.id, it) },
            onDismiss = { picking = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = pluralStringResource(R.plurals.mdl_delete_models_title, selected.size, selected.size),
            text = stringResource(R.string.mdl_delete_models_text),
            confirm = stringResource(R.string.action_delete),
            onConfirm = {
                vm.deleteModels(config.id, selected)
                selected = emptySet()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

private fun Set<String>.toggle(id: String) = if (id in this) this - id else this + id

@Composable
private fun ModelRow(model: ModelConfig, current: Boolean, selecting: Boolean, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(model.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (model.displayName != model.id) {
                Text(model.id, style = MaterialTheme.extras.mono, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                model.effectiveContextWindow?.let { QuietPill(formatTokens(it)) }
                if (model.reasoning.supported) QuietPill(stringResource(R.string.mdl_badge_reasoning))
                if (model.vision == true) QuietPill(stringResource(R.string.badge_vision))
                if (model.builtIn) QuietPill(stringResource(R.string.mdl_badge_builtin))
                if (model.fromAccount) QuietPill(stringResource(R.string.mdl_badge_account))
            }
        }
        Spacer(Modifier.width(12.dp))
        when {
            selecting -> CheckBoxMark(selected)
            current -> CheckMark(true, stringResource(R.string.mdl_badge_current))
            else -> RowChevron()
        }
    }
}

@Composable
private fun CheckBoxMark(checked: Boolean) {
    if (checked) CheckMark(true) else Box(Modifier.size(24.dp).border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape))
}

@Composable
private fun AddModelDialog(onAdd: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var id by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        title = { Text(stringResource(R.string.mdl_add_custom_model)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AtomTextField(id, { id = it }, stringResource(R.string.mdl_model_id), mono = true, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
                AtomTextField(name, { name = it }, stringResource(R.string.mdl_display_name_optional))
            }
        },
        confirmButton = {
            TextButton(enabled = id.isNotBlank(), onClick = {
                onAdd(name, id)
                onDismiss()
            }) { Text(stringResource(R.string.mdl_add), color = MaterialTheme.colorScheme.onSurface) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.onSurface) } },
    )
}

@Composable
private fun PickPulledDialog(models: List<Pair<String, String>>, onAdd: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    var chosen by remember { mutableStateOf(models.map { it.first }.toSet()) }
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        title = { Text(pluralStringResource(R.plurals.mdl_new_models, models.size, models.size)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SearchField(query, { query = it }, stringResource(R.string.mdl_search_models))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    val q = query.trim().lowercase()
                    val shown = models.filter { q.isEmpty() || it.first.lowercase().contains(q) || it.second.lowercase().contains(q) }
                    items(shown, key = { it.first }) { (id, name) ->
                        CheckRow(name, id in chosen, onToggle = { chosen = chosen.toggle(id) }, subtitle = id.takeIf { it != name })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = chosen.isNotEmpty(), onClick = {
                onAdd(chosen)
                onDismiss()
            }) { Text(pluralStringResource(R.plurals.mdl_add_n, chosen.size, chosen.size), color = MaterialTheme.colorScheme.onSurface) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.onSurface) } },
    )
}

// ================================================================ Model detail

private val CONTEXT_SIZES = listOf(8_192, 32_768, 131_072, 200_000, 262_144, 1_000_000)

@Composable
internal fun ModelEditScreen(vm: ModelsViewModel, nav: ModelsNav, providerId: String, modelId: String, onBack: () -> Unit) {
    val providers by vm.providers.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val config = providers.firstOrNull { it.id == providerId }
    val model = config?.model(modelId)
    if (config == null || model == null) {
        LaunchedEffect(Unit) {
            delay(MISSING_GRACE_MS)
            onBack()
        }
        return
    }
    val auto = model.autoCapabilities
    val current = (settings?.selection as? EngineSelection.Cloud)?.let { it.providerId == providerId && it.model == modelId } == true
    var name by remember(modelId) { mutableStateOf(model.displayName) }
    var idText by remember(modelId) { mutableStateOf(model.id) }
    var context by remember(modelId) { mutableStateOf(model.contextWindow?.toString().orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }

    LargeTitleScreen(
        title = model.displayName,
        onBack = onBack,
        bottomBar = {
            BottomActionBar {
                PrimaryButton(
                    stringResource(if (current) R.string.mdl_is_default else R.string.mdl_set_default),
                    onClick = { vm.setDefault(EngineSelection.Cloud(providerId, modelId)) },
                    enabled = !current,
                )
            }
        },
    ) {
        item(key = "g-names") {
            Group(Modifier.padding(top = 12.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldLabel(stringResource(R.string.mdl_display_name))
                    AtomTextField(name, {
                        name = it
                        if (it.isNotBlank()) vm.updateModel(providerId, modelId) { m -> m.copy(displayName = it.trim()) }
                    }, model.id)
                    FieldLabel(stringResource(R.string.mdl_model_id))
                    AtomTextField(
                        idText,
                        { idText = it },
                        model.id,
                        mono = true,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                        trailing = if (idText.trim() != model.id && idText.isNotBlank()) {
                            {
                                CompactButton(stringResource(R.string.action_save), onClick = {
                                    val next = idText.trim()
                                    vm.changeModelId(providerId, model.id, next) { nav.replaceWith(ModelsPage.Model(providerId, next)) }
                                })
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }

        item(key = "h-ctx") { SectionLabel(stringResource(R.string.mdl_context_window)) }
        item(key = "g-ctx") {
            Group {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompactButton(stringResource(R.string.mdl_auto), onClick = {
                            context = ""
                            vm.updateModel(providerId, modelId) { it.copy(contextWindow = null) }
                        }, primary = model.contextWindow == null)
                        CONTEXT_SIZES.forEach { size ->
                            CompactButton(formatTokens(size), onClick = {
                                context = size.toString()
                                vm.updateModel(providerId, modelId) { it.copy(contextWindow = size) }
                            }, primary = model.contextWindow == size)
                        }
                    }
                    AtomTextField(
                        value = context,
                        onValueChange = { text ->
                            context = text.filter { it.isDigit() }.take(9)
                            vm.updateModel(providerId, modelId) { it.copy(contextWindow = context.toIntOrNull()?.takeIf { n -> n >= 512 }) }
                        },
                        placeholder = stringResource(R.string.mdl_context_hint),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            }
            Note(
                if (auto.contextWindow != null) {
                    stringResource(R.string.mdl_detected_context, formatTokens(auto.contextWindow!!), sourceLabel(auto.source))
                } else {
                    stringResource(R.string.mdl_context_unknown)
                },
            )
        }

        item(key = "h-reason") { SectionLabel(stringResource(R.string.mdl_supports_reasoning)) }
        item(key = "g-reason") {
            val modes = listOf(ReasoningMode.Auto, ReasoningMode.On, ReasoningMode.Off)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SegmentedControl(
                    listOf(stringResource(R.string.mdl_auto) to null, stringResource(R.string.mdl_on) to null, stringResource(R.string.mdl_off) to null),
                    modes.indexOf(model.reasoningMode),
                    onSelect = { i ->
                        val mode = modes[i]
                        vm.updateModel(providerId, modelId) {
                            it.copy(
                                reasoningMode = mode,
                                reasoningLevels = if (mode == ReasoningMode.On) it.reasoningLevels ?: auto.reasoning?.levels.orEmpty() else it.reasoningLevels,
                            )
                        }
                    },
                )
                val autoReasoning = auto.reasoning
                Note(
                    when {
                        autoReasoning == null || auto.source == CapabilitySource.Unknown -> stringResource(R.string.mdl_reasoning_unknown)
                        !autoReasoning.supported -> stringResource(R.string.mdl_reasoning_detected_none, sourceLabel(auto.source))
                        autoReasoning.levels.isEmpty() -> stringResource(R.string.mdl_reasoning_detected_forced, sourceLabel(auto.source))
                        else -> stringResource(
                            R.string.mdl_reasoning_detected_levels,
                            autoReasoning.levels.map { effortLabelPlain(it) }.joinToString(", "),
                            sourceLabel(auto.source),
                        )
                    },
                    Modifier.padding(top = 0.dp),
                )
            }
        }
        if (model.reasoningMode == ReasoningMode.On) {
            item(key = "h-levels") { SectionLabel(stringResource(R.string.mdl_reasoning_levels)) }
            item(key = "g-levels") {
                val levels = model.reasoningLevels.orEmpty()
                Group {
                    ReasoningEffort.entries.forEachIndexed { i, effort ->
                        if (i > 0) GroupDivider(inset = 18.dp)
                        CheckRow(effortLabel(effort), effort in levels, onToggle = {
                            vm.updateModel(providerId, modelId) { m ->
                                val now = m.reasoningLevels.orEmpty()
                                m.copy(reasoningLevels = (if (effort in now) now - effort else now + effort).sorted())
                            }
                        })
                    }
                }
                Note(stringResource(R.string.mdl_levels_note))
            }
        }

        item(key = "g-delete") {
            Group(Modifier.padding(top = 26.dp)) {
                GroupRow(
                    title = stringResource(R.string.mdl_delete_model),
                    titleColor = MaterialTheme.colorScheme.error,
                    icon = Icons.Filled.Delete,
                    onClick = { confirmDelete = true },
                )
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = pluralStringResource(R.plurals.mdl_delete_models_title, 1, 1),
            text = stringResource(R.string.mdl_delete_models_text),
            confirm = stringResource(R.string.action_delete),
            onConfirm = {
                vm.deleteModels(providerId, setOf(modelId))
                onBack()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun sourceLabel(source: CapabilitySource): String = stringResource(
    when (source) {
        CapabilitySource.Catalog -> R.string.mdl_source_catalog
        CapabilitySource.Remote -> R.string.mdl_source_remote
        CapabilitySource.Family -> R.string.mdl_source_family
        CapabilitySource.Manual, CapabilitySource.Unknown -> R.string.mdl_source_auto
    },
)

@Composable
private fun effortLabelPlain(effort: ReasoningEffort): String = effortLabel(effort)

// ================================================================ Add provider

@Composable
internal fun AddProviderScreen(vm: ModelsViewModel, nav: ModelsNav, kind: NewProviderKind, onBack: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable {
        mutableStateOf(
            when (kind) {
                NewProviderKind.OpenAiCompatible -> ""
                NewProviderKind.Anthropic -> "https://api.anthropic.com"
                NewProviderKind.Gemini -> PresetCatalog.GEMINI_BASE_URL
            },
        )
    }
    var wire by rememberSaveable {
        mutableStateOf(
            when (kind) {
                NewProviderKind.OpenAiCompatible -> Wire.OpenAiChat
                NewProviderKind.Anthropic -> Wire.Anthropic
                NewProviderKind.Gemini -> Wire.Gemini
            },
        )
    }
    var key by remember { mutableStateOf("") }
    var project by rememberSaveable { mutableStateOf("") }
    var location by rememberSaveable { mutableStateOf("global") }
    val needsUrl = wire != Wire.Vertex
    val canCreate = !needsUrl || url.trim().startsWith("http")

    LargeTitleScreen(
        title = stringResource(
            when (kind) {
                NewProviderKind.OpenAiCompatible -> R.string.mdl_add_openai
                NewProviderKind.Anthropic -> R.string.mdl_add_anthropic
                NewProviderKind.Gemini -> R.string.mdl_add_gemini
            },
        ),
        onBack = onBack,
        bottomBar = {
            BottomActionBar {
                PrimaryButton(stringResource(R.string.mdl_create_provider), enabled = canCreate, onClick = {
                    vm.addProvider(kind, name, url, wire, key, project, location) { nav.replaceWith(ModelsPage.Provider(it)) }
                })
            }
        },
    ) {
        item(key = "g-form") {
            Group(Modifier.padding(top = 12.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FieldLabel(stringResource(R.string.mdl_display_name))
                    AtomTextField(name, { name = it }, stringResource(R.string.mdl_name_hint))
                    if (kind == NewProviderKind.OpenAiCompatible) {
                        FieldLabel(stringResource(R.string.mdl_endpoint_mode))
                        val modes = listOf(Wire.OpenAiChat, Wire.OpenAiResponses)
                        SegmentedControl(modes.map { protocolLabel(it) to null }, modes.indexOf(wire), onSelect = { wire = modes[it] })
                    }
                    if (kind == NewProviderKind.Gemini) {
                        FieldLabel(stringResource(R.string.mdl_gemini_mode))
                        val modes = listOf(Wire.Gemini, Wire.Vertex)
                        SegmentedControl(modes.map { protocolLabel(it) to null }, modes.indexOf(wire), onSelect = {
                            wire = modes[it]
                            url = if (wire == Wire.Gemini) PresetCatalog.GEMINI_BASE_URL else ""
                        })
                    }
                    FieldLabel(stringResource(if (wire == Wire.Vertex) R.string.mdl_vertex_proxy else R.string.cloud_base_url))
                    AtomTextField(
                        url,
                        { url = it },
                        if (wire == Wire.Vertex) "aiplatform.googleapis.com" else "https://host/v1",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    )
                    if (kind == NewProviderKind.OpenAiCompatible) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PresetCatalog.localTemplates.forEach { (label, template) ->
                                CompactButton(label, onClick = {
                                    url = template
                                    if (name.isBlank()) name = label
                                }, primary = false)
                            }
                        }
                    }
                    if (wire == Wire.Vertex) {
                        FieldLabel(stringResource(R.string.mdl_vertex_project))
                        AtomTextField(project, { project = it }, stringResource(R.string.mdl_vertex_project_hint), keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
                        FieldLabel(stringResource(R.string.mdl_vertex_location))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PresetCatalog.vertexLocations.forEach { loc -> CompactButton(loc, onClick = { location = loc }, primary = location == loc) }
                        }
                        AtomTextField(location, { location = it }, "global", keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
                    }
                    FieldLabel(stringResource(R.string.cloud_api_key))
                    SecretField(key, { key = it }, stringResource(R.string.cloud_key_optional))
                }
            }
        }
        item(key = "n") {
            Note(stringResource(if (wire == Wire.Vertex) R.string.mdl_vertex_note else R.string.mdl_add_note))
        }
    }
}

// ================================================================ models.dev import

@Composable
internal fun CatalogScreen(vm: ModelsViewModel, nav: ModelsNav, onBack: () -> Unit) {
    val state by vm.catalogState.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { if (state !is CatalogState.Ready) vm.loadCatalog() }

    LargeTitleScreen(
        title = stringResource(R.string.mdl_catalog_title),
        onBack = onBack,
        actions = {
            CircleIconButton(Icons.Filled.Refresh, stringResource(R.string.mdl_refresh), { vm.loadCatalog(force = true) }, modifier = Modifier.padding(end = 8.dp))
        },
    ) {
        item(key = "intro") { Note(stringResource(R.string.mdl_catalog_intro), Modifier.padding(top = 4.dp)) }
        when (val s = state) {
            CatalogState.Idle, CatalogState.Loading -> item(key = "loading") {
                Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.mdl_catalog_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            is CatalogState.Failed -> item(key = "failed") {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.mdl_catalog_failed, s.message), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    CompactButton(stringResource(R.string.mdl_try_again), onClick = { vm.loadCatalog(force = true) })
                }
            }
            is CatalogState.Ready -> {
                item(key = "search") { SearchField(query, { query = it }, stringResource(R.string.mdl_search_providers), Modifier.padding(top = 14.dp)) }
                val q = query.trim().lowercase()
                val shown = s.providers.filter { q.isEmpty() || it.name.lowercase().contains(q) || it.id.contains(q) }
                item(key = "h") { SectionLabel(pluralStringResource(R.plurals.mdl_catalog_count, shown.size, shown.size)) }
                item(key = "g") {
                    Group {
                        shown.take(400).forEachIndexed { i, p ->
                            if (i > 0) GroupDivider(inset = 70.dp)
                            CatalogProviderRow(p) { nav.open(ModelsPage.CatalogModels(p.id)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogProviderRow(provider: CatalogProvider, onClick: () -> Unit) {
    GroupRow(
        title = provider.name,
        subtitle = listOfNotNull(
            provider.baseUrl?.removePrefix("https://"),
            pluralStringResource(R.plurals.mdl_model_count, provider.models.size, provider.models.size),
        ).joinToString(" · "),
        onClick = onClick,
        trailing = { RowChevron() },
    )
}

@Composable
internal fun CatalogModelsScreen(vm: ModelsViewModel, nav: ModelsNav, catalogId: String, onBack: () -> Unit) {
    val state by vm.catalogState.collectAsStateWithLifecycle()
    val provider = (state as? CatalogState.Ready)?.providers?.firstOrNull { it.id == catalogId }
    if (provider == null) {
        LaunchedEffect(Unit) {
            delay(MISSING_GRACE_MS)
            onBack()
        }
        return
    }
    var chosen by rememberSaveable(catalogId) { mutableStateOf(provider.models.filter { it.toolCall }.map { it.id }.toSet().ifEmpty { provider.models.map { it.id }.toSet() }) }
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim().lowercase()
    val shown = provider.models.filter { q.isEmpty() || it.id.lowercase().contains(q) || it.name.lowercase().contains(q) }

    LargeTitleScreen(
        title = provider.name,
        onBack = onBack,
        bottomBar = {
            BottomActionBar {
                PrimaryButton(
                    pluralStringResource(R.plurals.mdl_import_n, chosen.size, chosen.size),
                    enabled = chosen.isNotEmpty(),
                    onClick = { vm.importFromCatalog(provider, chosen) { id -> nav.replaceWith(ModelsPage.Provider(id)) } },
                )
            }
        },
    ) {
        item(key = "intro") {
            Note(
                if (provider.baseUrl == null) stringResource(R.string.mdl_catalog_no_url) else stringResource(R.string.mdl_catalog_import_note),
                Modifier.padding(top = 4.dp),
            )
        }
        item(key = "search") { SearchField(query, { query = it }, stringResource(R.string.mdl_search_models), Modifier.padding(top = 14.dp)) }
        item(key = "actions") {
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactButton(stringResource(R.string.mdl_select_all), onClick = { chosen = chosen + shown.map { it.id } }, primary = false)
                CompactButton(stringResource(R.string.mdl_select_none), onClick = { chosen = chosen - shown.map { it.id }.toSet() }, primary = false)
            }
        }
        item(key = "g") {
            Group(Modifier.padding(top = 12.dp)) {
                shown.forEachIndexed { i, m ->
                    if (i > 0) GroupDivider(inset = 18.dp)
                    val caps = m.capabilities
                    val detail = listOfNotNull(
                        m.id.takeIf { it != m.name },
                        caps.contextWindow?.let(::formatTokens),
                        if (caps.reasoning?.supported == true) stringResource(R.string.mdl_badge_reasoning) else null,
                        if (!m.toolCall) stringResource(R.string.mdl_no_tools) else null,
                    ).joinToString(" · ")
                    CheckRow(m.name, m.id in chosen, onToggle = { chosen = chosen.toggle(m.id) }, subtitle = detail.ifEmpty { null })
                }
            }
        }
    }
}
