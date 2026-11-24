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
    val installed by vm.installed.collectAsStateWithLifecycle()
    val savedKeys by vm.savedKeys.collectAsStateWithLifecycle()
    val tier by vm.shellTier.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val s = settings ?: AppSettings()
    val selection = s.selection

    val (engineTitle, engineSubtitle) = when (selection) {
        null -> stringResource(R.string.engine_none) to stringResource(R.string.settings_engine_none_sub)
        is EngineSelection.Cloud -> selection.model to (
            ProviderResolver.resolve(selection.providerId, selection.baseUrl, selection.model)?.displayName ?: selection.providerId
            )
        is EngineSelection.Local -> (
            installed.firstOrNull { it.id == selection.installedModelId }?.name ?: stringResource(R.string.engine_model_missing)
            ) to stringResource(R.string.badge_on_device)
    }
    val fallback = s.cloudFallback
    val fallbackTitle = fallback?.let { ProviderResolver.resolve(it.providerId, it.baseUrl, it.model)?.displayName ?: it.providerId }
    val fallbackNone = stringResource(R.string.settings_fallback_none)
    val fallbackDesc = stringResource(R.string.settings_fallback_desc)
    val confirmTitle = stringResource(R.string.settings_confirm_act)
    val confirmDesc = stringResource(R.string.settings_confirm_act_desc)
    val successColor = MaterialTheme.extras.success
    val idleColor = MaterialTheme.colorScheme.outline
    val tierLabel = stringResource(
        when (tier) {
            ShellTier.Root -> R.string.settings_tier_root
            ShellTier.Shizuku -> R.string.settings_tier_shizuku
            ShellTier.App -> R.string.settings_tier_app
            null -> R.string.settings_shell_not_checked
        },
    )
    val tierDot = if (tier == ShellTier.Root || tier == ShellTier.Shizuku) successColor else idleColor

    LargeTitleScreen(title = stringResource(R.string.screen_settings), onBack = onBack) {
        // ---------------------------------------------------------------- Model
        item(key = "l-model") { SectionLabel(stringResource(R.string.settings_engine)) }
        item(key = "g-model") {
            Group {
                GroupRow(
                    title = engineTitle,
                    subtitle = engineSubtitle,
                    icon = if (selection is EngineSelection.Cloud) AtomIcons.Cloud else AtomIcons.Chip,
                    onClick = onOpenModels,
                    trailing = { RowChevron() },
                )
                if (selection is EngineSelection.Local) {
                    GroupDivider()
                    MenuRow(
                        title = stringResource(R.string.settings_backend),
                        subtitle = stringResource(R.string.settings_backend_desc),
                        icon = ScreenIcons.Tune,
                        value = backendLabel(selection.backend),
                        options = LocalBackend.entries.map { it to backendLabelRes(it) },
                        selected = selection.backend,
                        onSelect = vm::setBackend,
                    )
                }
                GroupDivider()
                FallbackRow(
                    title = stringResource(R.string.settings_fallback),
                    subtitle = fallback?.let { "$fallbackTitle · ${it.model}" } ?: fallbackDesc,
                    value = if (fallback == null) fallbackNone else null,
                    savedKeys = savedKeys,
                    current = fallback?.providerId,
                    onSelect = vm::setFallbackProvider,
                    onAddKey = onOpenModels,
                )
            }
        }
        item(key = "n-fallback") { FootNote(stringResource(R.string.cloud_fallback_explain)) }

        // ---------------------------------------------------------------- Behaviour
        item(key = "l-behaviour") { SectionLabel(stringResource(R.string.settings_behaviour)) }
        item(key = "g-behaviour") {
            Group {
                GroupRow(
                    title = confirmTitle,
                    subtitle = confirmDesc,
                    icon = AtomIcons.Touch,
                    onClick = { vm.setConfirmAct(!s.confirmActTools) },
                    trailing = { AtomSwitch(checked = s.confirmActTools, onCheckedChange = vm::setConfirmAct) },
                )
                GroupDivider()
                MenuRow(
                    title = stringResource(R.string.settings_theme),
                    subtitle = null,
                    icon = ScreenIcons.Contrast,
                    value = stringResource(themeLabelRes(s.theme)),
                    options = listOf(ThemeMode.Dark, ThemeMode.Light, ThemeMode.System).map { it to themeLabelRes(it) },
                    selected = s.theme,
                    onSelect = vm::setTheme,
                )
            }
        }

        // ---------------------------------------------------------------- System
        item(key = "l-system") { SectionLabel(stringResource(R.string.settings_system)) }
        item(key = "g-system") {
            Group {
                GroupRow(
                    title = stringResource(R.string.screen_setup),
                    subtitle = stringResource(R.string.settings_setup_desc),
                    icon = Icons.Filled.Settings,
                    onClick = onOpenSetup,
                    trailing = { RowChevron() },
                )
                GroupDivider()
                GroupRow(
                    title = stringResource(R.string.settings_shell_row),
                    icon = ScreenIcons.Shield,
                    onClick = onOpenSetup,
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusDot(tierDot)
                            RowValue(tierLabel)
                            RowChevron()
                        }
                    },
                )
                GroupDivider()
                GroupRow(
                    title = stringResource(R.string.settings_terminal_row),
                    subtitle = stringResource(R.string.settings_terminal_row_desc),
                    icon = AtomIcons.Terminal,
                    onClick = onOpenTerminal,
                    trailing = { RowChevron() },
                )
                GroupDivider()
                GroupRow(
                    title = stringResource(R.string.settings_linux_row),
                    subtitle = stringResource(R.string.settings_linux_row_desc),
                    icon = ScreenIcons.Storage,
                    onClick = onOpenLinux,
                    trailing = { RowChevron() },
                )
            }
        }

        // ---------------------------------------------------------------- Voice
        item(key = "l-voice") { SectionLabel(stringResource(R.string.settings_voice)) }
        item(key = "g-voice") {
            Group {
                GroupRow(
                    title = stringResource(R.string.voice_hey_atom),
                    subtitle = stringResource(R.string.voice_hey_atom_text),
                    icon = AtomIcons.Mic,
                    onClick = onOpenSetup,
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RowValue(stringResource(if (s.heyAtom) R.string.settings_hey_atom_on else R.string.status_off))
                            RowChevron()
                        }
                    },
                )
            }
        }

        // ---------------------------------------------------------------- Feature modules
        FeatureSection.entries.forEach { section ->
            val rows = features.filter { it.section == section }
            if (rows.isNotEmpty()) {
                item(key = "l-f-${section.name}") { SectionLabel(stringResource(featureSectionLabel(section))) }
                item(key = "g-f-${section.name}") {
                    Group {
                        rows.forEachIndexed { i, entry ->
                            if (i > 0) GroupDivider()
                            GroupRow(
                                title = stringResource(entry.title),
                                subtitle = entry.subtitle?.let { stringResource(it) },
                                icon = entry.icon,
                                onClick = { onOpenRoute(entry.route) },
                                trailing = { RowChevron() },
                            )
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------------------- About
        item(key = "l-about") { SectionLabel(stringResource(R.string.settings_about)) }
        item(key = "g-about") {
            Group {
                GroupRow(
                    title = stringResource(R.string.app_name),
                    icon = Icons.Filled.Info,
                    trailing = { RowValue(stringResource(R.string.about_version, versionName(context))) },
                )
                GroupDivider()
                GroupRow(
                    title = stringResource(R.string.settings_license_row),
                    subtitle = stringResource(R.string.about_license),
                    icon = ScreenIcons.Document,
                    onClick = onOpenLicenses,
                    trailing = { RowChevron() },
                )
                GroupDivider()
                GroupRow(
                    title = stringResource(R.string.screen_licenses),
                    subtitle = stringResource(R.string.about_notices_desc),
                    icon = Icons.AutoMirrored.Filled.List,
                    onClick = onOpenLicenses,
                    trailing = { RowChevron() },
                )
            }
        }
        item(key = "n-trademark") { FootNote(stringResource(R.string.about_trademark), Modifier.padding(top = 4.dp)) }
    }
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
