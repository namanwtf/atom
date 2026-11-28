package atom.app.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.ImportContacts
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.JoinLeft
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SportsBar
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material.icons.rounded.SwipeUp
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import atom.app.AppGraph
import atom.app.R
import atom.app.data.AppSettings
import atom.app.data.EngineSelection
import atom.app.data.LocalBackend
import atom.app.data.ProviderStore
import atom.app.engine.ProviderResolver
import atom.core.llm.ReasoningEffort
import atom.core.settings.SettingsGroup
import atom.core.settings.SettingsRows
import atom.core.settings.SettingsTree
import atom.device.Accessibility
import atom.device.ShellTier
import atom.device.root.RootAccess
import atom.device.root.RootStatus
import atom.general.GeneralConfig
import atom.general.language.AppLanguage
import atom.general.update.UpdateChecker
import atom.general.update.UpdateStatus
import atom.llm.cloud.ProviderConfig
import atom.ui.components.ArrowRow
import atom.ui.components.AtomDialog
import atom.ui.components.DialogButtons
import atom.ui.components.DropdownRow
import atom.ui.components.Group
import atom.ui.components.GroupRow
import atom.ui.components.LargeTitleScreen
import atom.ui.components.RowChevron
import atom.ui.components.SectionLabel
import atom.ui.components.SwitchRow
import atom.ui.feature.FeatureEntry
import atom.ui.theme.extras
import atom.vdisplay.system.AutoAssistantRow
import atom.vdisplay.system.BackgroundDisplayRow
import atom.vdisplay.system.CircleToSearchGestureBarRow
import atom.vdisplay.system.CircleToSearchTwoFingerRow
import atom.vdisplay.system.DigitalAssistantRow
import atom.vdisplay.system.EnforceAccessibilityRow
import atom.vdisplay.system.PowerKeyRow
import atom.vdisplay.system.rememberFrameworkSeen
import atom.web.WebFeature
import androidx.compose.material3.MaterialTheme
import atom.app.ui.chat.label
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

class SettingsViewModel(app: Context, private val graph: AppGraph) : ViewModel() {
    val settings: StateFlow<AppSettings?> =
        graph.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val savedKeys: StateFlow<Set<String>> =
        graph.vault.savedProviders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    val providers: StateFlow<List<ProviderConfig>> = ProviderStore.get(app).providers
    /** Keys, sign-ins and installed models: the same readiness the home capsule and the drawer use. */
    internal val readiness: StateFlow<atom.app.ui.chat.Readiness?> =
        atom.app.ui.chat.Readiness.of(graph).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val imageBackend: StateFlow<String?> =
        WebFeature.imageBackendName(app).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Last detected shell tier; null until Setup (or a shell tool) has probed it. Probing can prompt for root, so not here. */
    val shellTier: StateFlow<ShellTier?> = graph.shellTier

    fun setBackend(backend: LocalBackend) {
        viewModelScope.launch {
            val local = graph.settings.current().selection as? EngineSelection.Local ?: return@launch
            graph.settings.setSelection(local.copy(backend = backend))
        }
    }

    fun setFallbackProvider(providerId: String?) {
        viewModelScope.launch {
            if (providerId == null) {
                graph.settings.setFallback(null)
            } else {
                val provider = ProviderResolver.resolve(providerId) ?: return@launch
                graph.settings.setFallback(EngineSelection.Cloud(provider.id, provider.defaultModel))
            }
        }
    }

    fun setConfirmAct(value: Boolean) {
        viewModelScope.launch { graph.settings.setConfirmActTools(value) }
    }

    /**
     * "Provider / Model" for the Model providers row, with the same state the home capsule shows ("· add key",
     * "Not set"); null when nothing is configured.
     */
    internal fun modelSummary(selection: EngineSelection?, ready: atom.app.ui.chat.Readiness?, context: Context, noModel: (String) -> String): String? = when {
        selection == null -> null
        selection is EngineSelection.Cloud && selection.model.isBlank() -> noModel(graph.modelInfo.providerName(selection))
        else -> atom.app.ui.chat.modelStatus(selection, ready, graph.modelInfo) { graph.modelInfo.label(it) }.label(context)
    }

    fun reasoningLevels(selection: EngineSelection?): List<ReasoningEffort> =
        graph.modelInfo.reasoningLevels(selection).filter { it != ReasoningEffort.Off }

    fun setReasoningByDefault(value: Boolean) {
        viewModelScope.launch { graph.settings.setReasoningByDefault(value) }
    }

    fun setDefaultReasoning(value: ReasoningEffort?) {
        viewModelScope.launch { graph.settings.setDefaultReasoning(value) }
    }

    fun setAutoCompaction(value: Boolean) {
        viewModelScope.launch { graph.settings.setAutoCompaction(value) }
    }

    fun setLiveUpdates(value: Boolean) {
        viewModelScope.launch { graph.settings.setLiveUpdates(value) }
    }

    fun setKeepScreenOn(value: Boolean) {
        viewModelScope.launch { graph.settings.setKeepScreenOn(value) }
    }

    fun setLimits(value: atom.core.agent.LimitOverrides) {
        viewModelScope.launch { graph.settings.setLimits(value) }
    }
}

/** Callbacks into the app's navigation. */
class SettingsNav(
    val back: () -> Unit,
    val openModels: () -> Unit,
    val openOnDevice: () -> Unit,
    val openTitles: () -> Unit,
    val openSetup: () -> Unit,
    val openLicenses: () -> Unit,
    val openRoute: (String) -> Unit,
)

/**
 * Settings, in the classic layout: LLM providers, Context & extensions, Tools, the untitled system card, System
 * assistant takes over, Circle to Search (once the framework was seen), General, Permissions and About. Rows for
 * newer features sit at the end of the closest group (see [SettingsTree]).
 */
@Composable
fun SettingsScreen(vm: SettingsViewModel, nav: SettingsNav, features: List<FeatureEntry>) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val s = settings ?: AppSettings()
    val frameworkSeen = rememberFrameworkSeen()
    val root by RootAccess.status.collectAsStateWithLifecycle()
    val links = remember { ProjectLinks.current() }
    val byRoute = remember(features) { features.associateBy { it.route } }
    val groups = SettingsTree.place(features.map { it.route to it.section.name }) { key ->
        when (key) {
            SettingsRows.HARDWARE -> s.selection is EngineSelection.Local
            SettingsRows.FEEDBACK -> links.feedback != null
            SettingsRows.SOURCE_CODE -> links.source != null
            SettingsRows.AUTO_ASSISTANT -> frameworkSeen || root == RootStatus.Granted
            in SettingsRows.NEEDS_FRAMEWORK -> frameworkSeen
            else -> true
        }
    }
    // Re-read system state (overlay permission, accessibility) whenever the page comes back from system settings.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { }
    }

    LargeTitleScreen(title = stringResource(R.string.screen_settings), onBack = nav.back) {
        for (placed in groups) {
            val title = groupTitle(placed.group)
            item(key = "h-${placed.group.name}") {
                if (title != null) SectionLabel(stringResource(title)) else Spacer(Modifier.height(12.dp))
            }
            item(key = "g-${placed.group.name}") {
                Group {
                    placed.keys.forEach { key ->
                        if (SettingsRows.isRow(key)) {
                            BuiltInRow(key, vm, s, nav, links, resumes)
                        } else {
                            byRoute[key]?.let { FeatureRow(it, vm, nav) }
                        }
                    }
                }
            }
        }
    }
}

private fun groupTitle(group: SettingsGroup): Int? = when (group) {
    SettingsGroup.Llm -> R.string.set_group_llm
    SettingsGroup.Context -> R.string.set_group_context
    SettingsGroup.Tools -> R.string.set_group_tools
    SettingsGroup.System -> null
    SettingsGroup.Assistant -> R.string.set_group_assistant
    SettingsGroup.CircleToSearch -> R.string.set_group_cts
    SettingsGroup.General -> R.string.set_group_general
    SettingsGroup.Permissions -> R.string.set_group_permissions
    SettingsGroup.About -> R.string.set_group_about
}

/** Classic titles and icons for feature rows; classic rows show no summary. */
private class RouteLook(val title: Int?, val icon: ImageVector, val classic: Boolean)

private val ROUTE_LOOKS: Map<String, RouteLook> = mapOf(
    "extend/memory" to RouteLook(R.string.set_memory, Icons.AutoMirrored.Rounded.MenuBook, true),
    "extend/subagents" to RouteLook(R.string.set_subagent, Icons.Rounded.JoinLeft, true),
    "extend/skills" to RouteLook(R.string.set_skills, Icons.Rounded.ImportContacts, true),
    "extend/mcp" to RouteLook(R.string.set_mcp, Icons.Rounded.AccountTree, true),
    "automation/tasks" to RouteLook(R.string.set_scheduled, Icons.Rounded.Schedule, true),
    "characters/library" to RouteLook(R.string.set_characters, Icons.Rounded.SportsBar, true),
    "context/notifications" to RouteLook(null, Icons.Rounded.Notifications, false),
    "automation/rules" to RouteLook(null, Icons.Rounded.AutoMode, false),
    "device/tools" to RouteLook(R.string.set_tool_list, Icons.Rounded.Dashboard, true),
    "web/search" to RouteLook(R.string.set_web, Icons.Rounded.TravelExplore, true),
    "web/images" to RouteLook(R.string.set_images, Icons.Rounded.Image, true),
    "linux/home" to RouteLook(R.string.set_linux, Icons.Rounded.Inventory2, true),
    "web/gallery" to RouteLook(null, Icons.Rounded.PhotoLibrary, false),
    "web/browser" to RouteLook(null, Icons.Rounded.Public, false),
    "web/sites" to RouteLook(null, Icons.Rounded.Code, false),
    "linux/workspace" to RouteLook(null, Icons.Rounded.FolderOpen, false),
    "linux/terminal" to RouteLook(null, Icons.Rounded.Terminal, false),
    "vdisplay/system" to RouteLook(R.string.set_system, Icons.Rounded.Security, true),
    "device/changes" to RouteLook(null, Icons.Rounded.History, false),
    "device/power" to RouteLook(null, Icons.Rounded.BatteryChargingFull, false),
    "overlay/settings" to RouteLook(R.string.set_floating, Icons.Rounded.ChatBubble, true),
    "general/appearance" to RouteLook(R.string.set_appearance, Icons.Rounded.Palette, true),
    "voice/settings" to RouteLook(R.string.set_speech, Icons.Rounded.Mic, true),
    "general/backup" to RouteLook(R.string.set_backup, Icons.Rounded.Description, true),
    "voice/chat" to RouteLook(null, Icons.Rounded.GraphicEq, false),
    "device/permissions" to RouteLook(R.string.set_all_permissions, Icons.Rounded.Shield, false),
)

@Composable
private fun FeatureRow(entry: FeatureEntry, vm: SettingsViewModel, nav: SettingsNav) {
    val look = ROUTE_LOOKS[entry.route]
    val title = stringResource(look?.title ?: entry.title)
    val summary = when {
        entry.route == "web/images" -> {
            val backend by vm.imageBackend.collectAsStateWithLifecycle()
            backend?.let { stringResource(R.string.set_images_on, it) } ?: stringResource(R.string.set_images_off)
        }
        entry.route == "device/permissions" -> stringResource(R.string.set_all_permissions_sub)
        look?.classic == true -> null
        else -> entry.subtitle?.let { stringResource(it) }
    }
    ArrowRow(
        title = title,
        summary = summary,
        icon = look?.icon ?: entry.icon ?: Icons.Rounded.Settings,
        onClick = { nav.openRoute(entry.route) },
    )
}

@Composable
private fun BuiltInRow(key: String, vm: SettingsViewModel, s: AppSettings, nav: SettingsNav, links: ProjectLinks, resumes: Int) {
    val context = LocalContext.current
    when (key) {
        SettingsRows.MODEL_PROVIDERS -> {
            val noModel = stringResource(R.string.set_no_model, "%s")
            vm.providers.collectAsStateWithLifecycle().value
            val ready by vm.readiness.collectAsStateWithLifecycle()
            ArrowRow(
                title = stringResource(R.string.settings_model_providers),
                summary = vm.modelSummary(s.selection, ready, context) { noModel.replace("%s", it) } ?: stringResource(R.string.set_not_configured),
                icon = Icons.Rounded.Cloud,
                onClick = nav.openModels,
            )
        }
        SettingsRows.REASONING_DEFAULT -> SwitchRow(
            title = stringResource(R.string.settings_reasoning_default),
            checked = s.reasoningByDefault,
            onCheckedChange = vm::setReasoningByDefault,
            icon = Icons.Rounded.Psychology,
        )
        SettingsRows.DEFAULT_EFFORT -> {
            vm.providers.collectAsStateWithLifecycle().value
            val levels = vm.reasoningLevels(s.selection)
            val stored = s.defaultReasoning
            if (levels.size <= 1) {
                GroupRow(
                    title = stringResource(R.string.settings_default_effort),
                    subtitle = stringResource(R.string.set_effort_unsupported),
                    icon = Icons.Rounded.Tune,
                    enabled = false,
                )
            } else {
                // A level saved for another model stays visible (and marked) instead of reading "Model default".
                val choices: List<ReasoningEffort?> = listOf<ReasoningEffort?>(null) + levels +
                    listOfNotNull(stored?.takeIf { it !in levels })
                val items = choices.map { e ->
                    when {
                        e == null -> stringResource(R.string.settings_effort_default)
                        e in levels -> stringResource(effortLabel(e!!))
                        else -> stringResource(R.string.set_effort_saved_unsupported, stringResource(effortLabel(e!!)))
                    }
                }
                DropdownRow(
                    title = stringResource(R.string.settings_default_effort),
                    items = items,
                    selectedIndex = choices.indexOf(stored).coerceAtLeast(0),
                    onSelect = { i -> vm.setDefaultReasoning(choices[i]) },
                    icon = Icons.Rounded.Tune,
                    enabled = s.reasoningByDefault,
                )
            }
        }
        SettingsRows.ON_DEVICE -> ArrowRow(
            title = stringResource(R.string.set_on_device),
            summary = stringResource(R.string.set_on_device_sub),
            icon = Icons.Rounded.Memory,
            onClick = nav.openOnDevice,
        )
        SettingsRows.HARDWARE -> {
            val local = s.selection as? EngineSelection.Local
            if (local != null) {
                DropdownRow(
                    title = stringResource(R.string.settings_backend),
                    items = LocalBackend.entries.map { stringResource(backendLabel(it)) },
                    selectedIndex = LocalBackend.entries.indexOf(local.backend),
                    onSelect = { vm.setBackend(LocalBackend.entries[it]) },
                    icon = Icons.Rounded.DeveloperBoard,
                    summary = stringResource(R.string.settings_backend_desc),
                )
            }
        }
        SettingsRows.CLOUD_FALLBACK -> FallbackRow(vm, s, nav)
        SettingsRows.ADVANCED -> ArrowRow(
            title = stringResource(R.string.adv_title),
            summary = stringResource(R.string.adv_row_sub),
            icon = Icons.Rounded.Tune,
            onClick = { nav.openRoute(ADVANCED_ROUTE) },
        )
        SettingsRows.AUTO_COMPACTION -> SwitchRow(
            title = stringResource(R.string.settings_auto_compaction),
            checked = s.autoCompaction,
            onCheckedChange = vm::setAutoCompaction,
            icon = Icons.Rounded.Layers,
        )
        SettingsRows.AUTO_TITLES -> ArrowRow(
            title = stringResource(R.string.settings_auto_titles),
            icon = Icons.Rounded.AutoAwesome,
            onClick = nav.openTitles,
        )
        SettingsRows.BACKGROUND_DISPLAY -> BackgroundDisplayRow(Icons.Rounded.Layers)
        SettingsRows.CONFIRM_ACT -> SwitchRow(
            title = stringResource(R.string.settings_confirm_act),
            summary = stringResource(R.string.settings_confirm_act_desc),
            checked = s.confirmActTools,
            onCheckedChange = vm::setConfirmAct,
            icon = Icons.Rounded.PanTool,
        )
        SettingsRows.SHELL_ACCESS -> {
            val tier by vm.shellTier.collectAsStateWithLifecycle()
            val good = tier == ShellTier.Root || tier == ShellTier.Shizuku
            GroupRow(
                title = stringResource(R.string.settings_shell_row),
                icon = Icons.Rounded.AdminPanelSettings,
                onClick = nav.openSetup,
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        EndText(stringResource(tierLabel(tier)), if (good) MaterialTheme.extras.success else null)
                        RowChevron()
                    }
                },
            )
        }
        SettingsRows.DIGITAL_ASSISTANT -> DigitalAssistantRow(Icons.Rounded.SupportAgent)
        SettingsRows.POWER_KEY -> PowerKeyRow(Icons.Rounded.PowerSettingsNew)
        SettingsRows.AUTO_ASSISTANT -> AutoAssistantRow(Icons.Rounded.Settings)
        SettingsRows.CTS_GESTURE_BAR -> CircleToSearchGestureBarRow(Icons.Rounded.SwipeUp)
        SettingsRows.CTS_TWO_FINGER -> CircleToSearchTwoFingerRow(Icons.Rounded.TouchApp)
        SettingsRows.KEEP_SCREEN_ON -> SwitchRow(
            title = stringResource(R.string.settings_keep_screen_on),
            checked = s.keepScreenOn,
            onCheckedChange = vm::setKeepScreenOn,
            icon = Icons.Rounded.BrightnessHigh,
        )
        SettingsRows.LIVE_UPDATES -> SwitchRow(
            title = stringResource(R.string.set_live_updates),
            summary = stringResource(R.string.set_live_updates_sub),
            checked = s.liveUpdates,
            onCheckedChange = vm::setLiveUpdates,
            icon = Icons.Rounded.Notifications,
        )
        SettingsRows.LANGUAGE -> {
            // Only languages that ship: "Follow system" plus any translation in AppLanguage.available.
            val tags = listOf<String?>(null) + AppLanguage.available
            var current by rememberSaveable { mutableStateOf(AppLanguage.current(context)?.takeIf { it in AppLanguage.available }) }
            DropdownRow(
                title = stringResource(R.string.set_language),
                items = tags.map { tag ->
                    tag?.let { java.util.Locale.forLanguageTag(it).let { l -> l.getDisplayName(l) } } ?: stringResource(R.string.set_language_system)
                },
                selectedIndex = tags.indexOf(current).coerceAtLeast(0),
                onSelect = { i ->
                    current = tags[i]
                    AppLanguage.set(context, current)
                },
                icon = Icons.Rounded.Language,
            )
        }
        SettingsRows.OVERLAY_PERMISSION -> {
            val granted = remember(resumes) { Settings.canDrawOverlays(context) }
            GroupRow(
                title = stringResource(R.string.set_overlay_permission),
                icon = Icons.Rounded.Layers,
                onClick = {
                    if (!granted) {
                        context.openOr(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                    }
                },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        EndText(
                            stringResource(if (granted) R.string.set_authorized else R.string.set_not_authorized),
                            if (granted) null else MiuixTheme.colorScheme.error,
                        )
                        RowChevron()
                    }
                },
            )
        }
        SettingsRows.ACCESSIBILITY -> {
            val on = remember(resumes) { Accessibility.isEnabled(context) }
            GroupRow(
                title = stringResource(R.string.set_accessibility),
                icon = Icons.Rounded.AccessibilityNew,
                onClick = { context.openOr(Accessibility.settingsIntent()) },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        EndText(stringResource(if (on) R.string.set_enabled else R.string.set_disabled), MiuixTheme.colorScheme.primary)
                        RowChevron()
                    }
                },
            )
        }
        SettingsRows.ENFORCE_ACCESSIBILITY -> EnforceAccessibilityRow(Icons.Rounded.VerifiedUser)
        SettingsRows.VERSION -> ArrowRow(
            title = stringResource(R.string.set_version),
            summary = remember { versionLabel(context) },
            icon = Icons.Rounded.Info,
            onClick = { nav.openRoute(ABOUT_ROUTE) },
        )
        SettingsRows.SETUP -> ArrowRow(
            title = stringResource(R.string.settings_permissions_setup),
            summary = stringResource(R.string.settings_setup_desc),
            icon = Icons.Rounded.VerifiedUser,
            onClick = nav.openSetup,
        )
        SettingsRows.CHECK_UPDATES -> UpdateRow(links, nav)
        SettingsRows.FEEDBACK -> links.feedback?.let { url ->
            ArrowRow(title = stringResource(R.string.set_feedback), icon = Icons.Rounded.BugReport, onClick = { context.openOr(Intent(Intent.ACTION_VIEW, Uri.parse(url))) })
        }
        SettingsRows.SOURCE_CODE -> links.source?.let { url ->
            ArrowRow(
                title = stringResource(R.string.set_source),
                summary = stringResource(R.string.set_source_sub),
                icon = Icons.Rounded.Code,
                onClick = { context.openOr(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
            )
        }
        SettingsRows.LICENSES -> ArrowRow(
            title = stringResource(R.string.screen_licenses),
            summary = stringResource(R.string.about_license),
            icon = Icons.Rounded.Gavel,
            onClick = nav.openLicenses,
        )
    }
}

/** End-of-row status text; [color] null uses the row value grey. */
@Composable
private fun EndText(text: String, color: Color?) {
    Text(
        text,
        style = MiuixTheme.textStyles.body2,
        color = color ?: MiuixTheme.colorScheme.onSurfaceVariantActions,
        maxLines = 1,
    )
}

@Composable
private fun FallbackRow(vm: SettingsViewModel, s: AppSettings, nav: SettingsNav) {
    val saved by vm.savedKeys.collectAsStateWithLifecycle()
    val ready = ProviderResolver.all().filter { it.id in saved }
    val current = s.cloudFallback?.providerId
    val items = buildList {
        add(stringResource(R.string.settings_fallback_none))
        ready.forEach { add(it.displayName) }
        if (ready.isEmpty()) add(stringResource(R.string.settings_fallback_add_key))
    }
    DropdownRow(
        title = stringResource(R.string.settings_fallback),
        items = items,
        selectedIndex = if (current == null) 0 else ready.indexOfFirst { it.id == current } + 1,
        onSelect = { i ->
            when {
                i == 0 -> vm.setFallbackProvider(null)
                ready.isEmpty() -> nav.openModels()
                else -> vm.setFallbackProvider(ready[i - 1].id)
            }
        },
        icon = Icons.Rounded.Bolt,
        summary = stringResource(R.string.settings_fallback_desc),
    )
}

/** "Check for updates": a toast for the common answers, a dialog when a newer version exists. */
@Composable
private fun UpdateRow(links: ProjectLinks, nav: SettingsNav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var available by remember { mutableStateOf<UpdateStatus.Available?>(null) }
    ArrowRow(
        title = stringResource(R.string.set_check_updates),
        summary = if (checking) stringResource(R.string.set_checking) else null,
        icon = Icons.Rounded.SystemUpdate,
        enabled = !checking,
        onClick = {
            val repo = links.updateRepo
            if (repo == null) {
                Toast.makeText(context, R.string.set_updates_not_configured, Toast.LENGTH_SHORT).show()
            } else {
                scope.launch {
                    checking = true
                    val status = UpdateChecker.check(repo, versionName(context), force = true)
                    checking = false
                    when (status) {
                        is UpdateStatus.Available -> available = status
                        is UpdateStatus.UpToDate -> Toast.makeText(context, R.string.set_up_to_date, Toast.LENGTH_SHORT).show()
                        is UpdateStatus.NoReleases -> Toast.makeText(context, R.string.set_no_releases, Toast.LENGTH_SHORT).show()
                        is UpdateStatus.NotConfigured -> Toast.makeText(context, R.string.set_updates_not_configured, Toast.LENGTH_SHORT).show()
                        is UpdateStatus.Failed -> Toast.makeText(context, R.string.set_update_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        },
    )
    val update = available
    AtomDialog(
        show = update != null,
        onDismiss = { available = null },
        title = stringResource(R.string.set_update_available),
        summary = update?.let { stringResource(R.string.set_update_versions, it.latest, it.current) },
    ) {
        DialogButtons(
            confirmText = stringResource(R.string.set_download),
            onConfirm = {
                available = null
                // The About page downloads and installs the release.
                nav.openRoute(ABOUT_ROUTE)
            },
            dismissText = stringResource(R.string.set_cancel),
            onDismiss = { available = null },
        )
    }
}

private const val ABOUT_ROUTE = "general/about"

/** The Advanced page's route (also opened by the screenshot workflow). */
const val ADVANCED_ROUTE = "settings-advanced"

/** Where Check for updates, Feedback and Source code point; null hides or disables the row. */
private class ProjectLinks(val updateRepo: String?, val source: String?, val feedback: String?) {
    companion object {
        fun current(): ProjectLinks {
            val repo = GeneralConfig.updateRepo.trim().takeIf { it.isNotEmpty() }
            val github = repo?.let { "https://github.com/$it" }
            return ProjectLinks(
                updateRepo = repo,
                source = GeneralConfig.sourceUrl.trim().ifEmpty { github.orEmpty() }.takeIf { it.isNotEmpty() },
                feedback = GeneralConfig.feedback.trim().takeIf { it.startsWith("http") }
                    ?: github?.let { "$it/issues" },
            )
        }
    }
}

private fun Context.openOr(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: SecurityException) {
    }
}

private fun effortLabel(effort: ReasoningEffort): Int = when (effort) {
    ReasoningEffort.Off -> R.string.settings_effort_off
    ReasoningEffort.Low -> R.string.settings_effort_low
    ReasoningEffort.Medium -> R.string.settings_effort_medium
    ReasoningEffort.High -> R.string.settings_effort_high
    ReasoningEffort.XHigh -> R.string.settings_effort_xhigh
    ReasoningEffort.Max -> R.string.settings_effort_max
}

private fun backendLabel(backend: LocalBackend) = when (backend) {
    LocalBackend.Auto -> R.string.backend_auto
    LocalBackend.Npu -> R.string.backend_npu
    LocalBackend.Gpu -> R.string.backend_gpu
    LocalBackend.Cpu -> R.string.backend_cpu
}

private fun tierLabel(tier: ShellTier?) = when (tier) {
    ShellTier.Root -> R.string.settings_tier_root
    ShellTier.Shizuku -> R.string.settings_tier_shizuku
    ShellTier.App -> R.string.settings_tier_app
    null -> R.string.settings_shell_not_checked
}

private fun versionName(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

/** "4.0.1 (40001)". */
@Suppress("DEPRECATION")
private fun versionLabel(context: Context): String {
    val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull() ?: return "?"
    val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    return "${info.versionName ?: "?"} ($code)"
}
