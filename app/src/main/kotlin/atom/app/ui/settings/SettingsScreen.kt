@file:OptIn(ExperimentalMaterial3Api::class)

package atom.app.ui.settings

import atom.ui.feature.FeatureEntry
import atom.ui.feature.FeatureSection

import atom.ui.components.AtomMenu
import atom.ui.components.AtomMenuItem
import atom.ui.components.ScreenIcons

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BubbleChart
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TheaterComedy
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Widgets
import atom.core.llm.ReasoningEffort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import atom.app.AppGraph
import atom.app.R
import atom.app.data.AppSettings
import atom.app.data.EngineSelection
import atom.app.data.LocalBackend
import atom.ui.theme.ThemeMode
import atom.app.engine.ProviderResolver
import atom.ui.components.Group
import atom.ui.components.GroupDivider
import atom.ui.components.GroupRow
import atom.ui.components.LargeTitleScreen
import atom.ui.components.RowChevron
import atom.ui.components.RowValue
import atom.ui.components.SectionLabel
import atom.ui.components.StatusDot
import atom.ui.components.AtomSwitch
import atom.ui.theme.AtomIcons
import atom.ui.theme.extras
import atom.device.ShellTier
import atom.models.InstalledModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val graph: AppGraph) : ViewModel() {
    val settings: StateFlow<AppSettings?> =
        graph.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val installed: StateFlow<List<InstalledModel>> = graph.models.installed
    val savedKeys: StateFlow<Set<String>> =
        graph.vault.savedProviders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

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

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { graph.settings.setTheme(mode) }
    }

    fun modelLabel(selection: EngineSelection): String = graph.modelInfo.label(selection)

    fun setReasoningByDefault(value: Boolean) {
        viewModelScope.launch { graph.settings.setReasoningByDefault(value) }
    }

    fun setDefaultReasoning(value: ReasoningEffort?) {
        viewModelScope.launch { graph.settings.setDefaultReasoning(value) }
    }

    fun setAutoTitles(value: Boolean) {
        viewModelScope.launch { graph.settings.setAutoTitles(value) }
    }

    fun setAutoCompaction(value: Boolean) {
        viewModelScope.launch { graph.settings.setAutoCompaction(value) }
    }

    fun setKeepScreenOn(value: Boolean) {
        viewModelScope.launch { graph.settings.setKeepScreenOn(value) }
    }
}

@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenLicenses: () -> Unit,
    onOpenTerminal: () -> Unit = {},
    onOpenLinux: () -> Unit = {},
    features: List<FeatureEntry> = emptyList(),
    onOpenRoute: (String) -> Unit = {},
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val savedKeys by vm.savedKeys.collectAsStateWithLifecycle()
    val tier by vm.shellTier.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val s = settings ?: AppSettings()
    val selection = s.selection
    val fallback = s.cloudFallback
    val fallbackTitle = fallback?.let { ProviderResolver.resolve(it.providerId, it.baseUrl, it.model)?.displayName ?: it.providerId }
    val tierDot = if (tier == ShellTier.Root || tier == ShellTier.Shizuku) MaterialTheme.extras.success else MaterialTheme.colorScheme.outline
    val tierLabel = stringResource(
        when (tier) {
            ShellTier.Root -> R.string.settings_tier_root
            ShellTier.Shizuku -> R.string.settings_tier_shizuku
            ShellTier.App -> R.string.settings_tier_app
            null -> R.string.settings_shell_not_checked
        },
    )
    fun entries(section: FeatureSection) = features.filter { it.section == section }

    LargeTitleScreen(title = stringResource(R.string.screen_settings), onBack = onBack) {
        // ---------------------------------------------------------------- LLM providers
        item(key = "l-llm") { SectionLabel(stringResource(R.string.settings_llm_providers)) }
        item(key = "g-llm") {
            Group {
                GroupRow(
                    title = stringResource(R.string.settings_model_providers),
                    subtitle = selection?.let { vm.modelLabel(it) } ?: stringResource(R.string.settings_engine_none_sub),
                    icon = Icons.Outlined.Hub,
                    onClick = onOpenModels,
                    trailing = { RowChevron() },
                )
                if (selection is EngineSelection.Local) {
                    GroupDivider()
                    MenuRow(
                        title = stringResource(R.string.settings_backend),
                        subtitle = stringResource(R.string.settings_backend_desc),
                        icon = Icons.Outlined.Memory,
                        value = backendLabel(selection.backend),
                        options = LocalBackend.entries.map { it to backendLabelRes(it) },
                        selected = selection.backend,
                        onSelect = vm::setBackend,
                    )
                }
                GroupDivider()
                GroupRow(
                    title = stringResource(R.string.settings_reasoning_default),
                    icon = Icons.Outlined.Psychology,
                    onClick = { vm.setReasoningByDefault(!s.reasoningByDefault) },
                    trailing = { AtomSwitch(s.reasoningByDefault, vm::setReasoningByDefault) },
                )
                GroupDivider()
                MenuRow(
                    title = stringResource(R.string.settings_default_effort),
                    subtitle = null,
                    icon = Icons.Outlined.Tune,
                    value = stringResource(effortLabel(s.defaultReasoning)),
                    options = (listOf<ReasoningEffort?>(null) + ReasoningEffort.entries).map { it to effortLabel(it) },
                    selected = s.defaultReasoning,
                    onSelect = vm::setDefaultReasoning,
                )
                GroupDivider()
                FallbackRow(
                    title = stringResource(R.string.settings_fallback),
                    subtitle = fallback?.let { "$fallbackTitle · ${it.model}" } ?: stringResource(R.string.settings_fallback_desc),
                    value = if (fallback == null) stringResource(R.string.settings_fallback_none) else null,
                    savedKeys = savedKeys,
                    current = fallback?.providerId,
                    onSelect = vm::setFallbackProvider,
                    onAddKey = onOpenModels,
                )
                EntryRows(entries(FeatureSection.Models), onOpenRoute, leadingDivider = true)
            }
        }

        // ---------------------------------------------------------------- Context & extensions
        item(key = "l-context") { SectionLabel(stringResource(R.string.settings_section_context)) }
        item(key = "g-context") {
            Group {
                GroupRow(
                    title = stringResource(R.string.settings_auto_compaction),
                    icon = Icons.Outlined.Layers,
                    onClick = { vm.setAutoCompaction(!s.autoCompaction) },
                    trailing = { AtomSwitch(s.autoCompaction, vm::setAutoCompaction) },
                )
                GroupDivider()
                GroupRow(
                    title = stringResource(R.string.settings_auto_titles),
                    icon = Icons.Outlined.AutoAwesome,
                    onClick = { vm.setAutoTitles(!s.autoTitles) },
                    trailing = { AtomSwitch(s.autoTitles, vm::setAutoTitles) },
                )
                EntryRows(entries(FeatureSection.Context), onOpenRoute, leadingDivider = true)
            }
        }

        // ---------------------------------------------------------------- Tools
        item(key = "l-tools") { SectionLabel(stringResource(R.string.settings_section_tools)) }
        item(key = "g-tools") {
            Group {
                GroupRow(
                    title = stringResource(R.string.settings_confirm_act),
                    subtitle = stringResource(R.string.settings_confirm_act_desc),
                    icon = Icons.Outlined.TouchApp,
                    onClick = { vm.setConfirmAct(!s.confirmActTools) },
                    trailing = { AtomSwitch(s.confirmActTools, vm::setConfirmAct) },
                )
                EntryRows(entries(FeatureSection.Tools), onOpenRoute, leadingDivider = true)
            }
        }

        // ---------------------------------------------------------------- System
        item(key = "l-system") { SectionLabel(stringResource(R.string.settings_system)) }
        item(key = "g-system") {
            Group {
                GroupRow(
                    title = stringResource(R.string.settings_permissions_setup),
                    icon = Icons.Outlined.VerifiedUser,
                    onClick = onOpenSetup,
                    trailing = { RowChevron() },
                )
                GroupDivider()
                GroupRow(
                    title = stringResource(R.string.settings_shell_row),
                    icon = Icons.Outlined.AdminPanelSettings,
                    onClick = onOpenSetup,
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusDot(tierDot)
                            RowValue(tierLabel)
                            RowChevron()
                        }
                    },
                )
                EntryRows(entries(FeatureSection.System), onOpenRoute, leadingDivider = true)
            }
        }

        // ---------------------------------------------------------------- General
        item(key = "l-general") { SectionLabel(stringResource(R.string.settings_section_general)) }
        item(key = "g-general") {
            Group {
                GroupRow(
                    title = stringResource(R.string.settings_keep_screen_on),
                    subtitle = stringResource(R.string.settings_keep_screen_on_sub),
                    icon = Icons.Outlined.LightMode,
                    onClick = { vm.setKeepScreenOn(!s.keepScreenOn) },
                    trailing = { AtomSwitch(s.keepScreenOn, vm::setKeepScreenOn) },
                )
                EntryRows(entries(FeatureSection.General), onOpenRoute, leadingDivider = true)
                GroupDivider()
                GroupRow(
                    title = stringResource(R.string.screen_licenses),
                    subtitle = stringResource(R.string.about_license),
                    icon = Icons.Outlined.Gavel,
                    onClick = onOpenLicenses,
                    trailing = { RowChevron() },
                )
            }
        }
        item(key = "n-version") {
            FootNote(stringResource(R.string.about_version, versionName(context)) + " · " + stringResource(R.string.about_trademark), Modifier.padding(top = 4.dp))
        }
    }
}

/** Feature rows inside a group, each with an icon (the feature's own, or one picked from what the row is about). */
@Composable
private fun EntryRows(rows: List<FeatureEntry>, onOpen: (String) -> Unit, leadingDivider: Boolean) {
    val context = LocalContext.current
    rows.forEachIndexed { i, entry ->
        if (leadingDivider || i > 0) GroupDivider()
        GroupRow(
            title = stringResource(entry.title),
            subtitle = entry.subtitle?.let { stringResource(it) },
            icon = entry.icon ?: iconFor(context, entry),
            onClick = { onOpen(entry.route) },
            trailing = { RowChevron() },
        )
    }
}

private fun iconFor(context: Context, entry: FeatureEntry): ImageVector {
    val key = (runCatching { context.resources.getResourceEntryName(entry.title) }.getOrDefault("") + " " + entry.route).lowercase()
    return ICONS.firstOrNull { (words, _) -> words.any { it in key } }?.second ?: Icons.Outlined.Settings
}

private val ICONS: List<Pair<List<String>, ImageVector>> = listOf(
    listOf("memory") to Icons.AutoMirrored.Outlined.MenuBook,
    listOf("skill") to Icons.Outlined.Extension,
    listOf("mcp") to Icons.Outlined.Hub,
    listOf("subagent") to Icons.Outlined.Groups,
    listOf("character") to Icons.Outlined.TheaterComedy,
    listOf("tasks", "schedule") to Icons.Outlined.Schedule,
    listOf("rules", "automation") to Icons.Outlined.Bolt,
    listOf("history", "notification") to Icons.Outlined.NotificationsNone,
    listOf("tool_list") to Icons.Outlined.Widgets,
    listOf("permission") to Icons.Outlined.Shield,
    listOf("gallery") to Icons.Outlined.PhotoLibrary,
    listOf("images", "image") to Icons.Outlined.AutoAwesome,
    listOf("search") to Icons.Outlined.TravelExplore,
    listOf("browser") to Icons.Outlined.Public,
    listOf("terminal") to Icons.Outlined.Terminal,
    listOf("linux") to Icons.Outlined.Dns,
    listOf("workspace") to Icons.Outlined.FolderOpen,
    listOf("vd_", "system_title", "enhance") to Icons.Outlined.DeveloperBoard,
    listOf("changes", "undo") to Icons.AutoMirrored.Outlined.Undo,
    listOf("power") to Icons.Outlined.BatteryChargingFull,
    listOf("appearance") to Icons.Outlined.Palette,
    listOf("language") to Icons.Outlined.Translate,
    listOf("backup") to Icons.Outlined.Backup,
    listOf("about") to Icons.Outlined.Info,
    listOf("audio_chat") to Icons.Outlined.GraphicEq,
    listOf("speech", "voice") to Icons.Outlined.RecordVoiceOver,
    listOf("overlay", "bubble", "floating") to Icons.Outlined.BubbleChart,
)

private fun effortLabel(effort: ReasoningEffort?): Int = when (effort) {
    null -> R.string.settings_effort_default
    ReasoningEffort.Off -> R.string.settings_effort_off
    ReasoningEffort.Low -> R.string.settings_effort_low
    ReasoningEffort.Medium -> R.string.settings_effort_medium
    ReasoningEffort.High -> R.string.settings_effort_high
    ReasoningEffort.XHigh -> R.string.settings_effort_xhigh
    ReasoningEffort.Max -> R.string.settings_effort_max
}

/** A row that shows its current value and opens a small menu of choices, the selected one checked. */
@Composable
private fun <T> MenuRow(
    title: String,
    subtitle: String?,
    icon: ImageVector,
    value: String,
    options: List<Pair<T, Int>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        GroupRow(
            title = title,
            subtitle = subtitle,
            icon = icon,
            onClick = { open = true },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RowValue(value)
                    RowChevron()
                }
            },
        )
        AtomMenu(expanded = open, onDismiss = { open = false }) {
            options.forEach { (option, label) ->
                AtomMenuItem(text = stringResource(label), checked = option == selected) {
                    open = false
                    onSelect(option)
                }
            }
        }
    }
}

@Composable
private fun FallbackRow(
    title: String,
    subtitle: String,
    value: String?,
    savedKeys: Set<String>,
    current: String?,
    onSelect: (String?) -> Unit,
    onAddKey: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        GroupRow(
            title = title,
            subtitle = subtitle,
            icon = AtomIcons.Bolt,
            onClick = { open = true },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (value != null) RowValue(value)
                    RowChevron()
                }
            },
        )
        AtomMenu(expanded = open, onDismiss = { open = false }) {
            AtomMenuItem(text = stringResource(R.string.settings_fallback_none), checked = current == null) {
                open = false
                onSelect(null)
            }
            ProviderResolver.all().filter { it.id in savedKeys }.forEach { p ->
                AtomMenuItem(text = p.displayName, checked = p.id == current) {
                    open = false
                    onSelect(p.id)
                }
            }
            if (savedKeys.isEmpty()) {
                AtomMenuItem(text = stringResource(R.string.settings_fallback_add_key), checked = false) {
                    open = false
                    onAddKey()
                }
            }
        }
    }
}

@Composable
private fun FootNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 18.dp, end = 18.dp, top = 8.dp),
    )
}

private fun backendLabelRes(backend: LocalBackend) = when (backend) {
    LocalBackend.Auto -> R.string.backend_auto
    LocalBackend.Npu -> R.string.backend_npu
    LocalBackend.Gpu -> R.string.backend_gpu
    LocalBackend.Cpu -> R.string.backend_cpu
}

@Composable
private fun backendLabel(backend: LocalBackend) = stringResource(backendLabelRes(backend))

private fun themeLabelRes(mode: ThemeMode) = when (mode) {
    ThemeMode.Dark -> R.string.theme_dark
    ThemeMode.Light -> R.string.theme_light
    ThemeMode.System -> R.string.theme_system
}

private fun versionName(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

private fun featureSectionLabel(section: FeatureSection): Int = when (section) {
    FeatureSection.Models -> R.string.settings_section_models
    FeatureSection.Context -> R.string.settings_section_context
    FeatureSection.Tools -> R.string.settings_section_tools
    FeatureSection.System -> R.string.settings_section_system_more
    FeatureSection.General -> R.string.settings_section_general
}
