@file:OptIn(ExperimentalMaterial3Api::class)

package atom.app.ui.settings

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
import atom.app.data.ThemeMode
import atom.app.engine.ProviderResolver
import atom.app.ui.common.Group
import atom.app.ui.common.GroupDivider
import atom.app.ui.common.GroupRow
import atom.app.ui.common.LargeTitleScreen
import atom.app.ui.common.RowChevron
import atom.app.ui.common.RowValue
import atom.app.ui.common.SectionLabel
import atom.app.ui.common.StatusDot
import atom.app.ui.common.AtomSwitch
import atom.app.ui.theme.AtomIcons
import atom.app.ui.theme.extras
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

// ==================================================================== Shared with Models and Setup

/** Graphite popup menu with rounded corners, matching the grouped lists. */
@Composable
internal fun AtomMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = DpOffset(16.dp, 0.dp),
        shape = RoundedCornerShape(18.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
        shadowElevation = 12.dp,
    ) { content() }
}

@Composable
internal fun AtomMenuItem(text: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = if (checked) {
            { Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface) }
        } else {
            null
        },
        onClick = onClick,
    )
}

/**
 * Graphite track with a white pill that slides to the selected segment. Used for the Models tabs.
 */
@Composable
internal fun SegmentedControl(
    options: List<Pair<String, ImageVector?>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.colorScheme
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(50))
            .background(c.surfaceContainerHigh)
            .padding(4.dp),
    ) {
        val segment = maxWidth / options.size
        val offset by animateDpAsState(segment * selected, tween(220), label = "segment")
        Box(
            Modifier
                .offset(x = offset)
                .width(segment)
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(c.primary),
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEachIndexed { i, (label, icon) ->
                val active = i == selected
                val tint by animateColorAsState(if (active) c.onPrimary else c.onSurfaceVariant, tween(200), label = "segmentText")
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(i) },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (icon != null) {
                        Icon(icon, null, Modifier.size(16.dp), tint = tint)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = tint)
                }
            }
        }
    }
}

/** Compact pill button for actions inside a group or card: white when [primary], graphite otherwise. */
@Composable
internal fun CompactButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    contentColor: Color? = null,
) {
    val c = MaterialTheme.colorScheme
    val container = when {
        !enabled -> c.surfaceContainerHigh
        primary -> c.primary
        else -> c.surfaceContainerHighest
    }
    val content = when {
        !enabled -> c.onSurfaceVariant
        contentColor != null -> contentColor
        primary -> c.onPrimary
        else -> c.onSurface
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        color = container,
        contentColor = content,
        modifier = modifier.heightIn(min = 40.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** Glyphs these screens need beyond AtomIcons (Material Design paths, Apache-2.0). */
internal object ScreenIcons {
    val Shield: ImageVector by lazy {
        icon("Shield", "M12,1L3,5v6c0,5.55 3.84,10.74 9,12 5.16,-1.26 9,-6.45 9,-12V5l-9,-4zM12,11.99h7c-0.53,4.12 -3.28,7.79 -7,8.94V12H5V6.3l7,-3.11v8.8z")
    }
    val Storage: ImageVector by lazy {
        icon("Storage", "M2,20h20v-4H2v4zM4,17h2v2H4v-2zM2,4v4h20V4H2zM6,7H4V5h2v2zM2,14h20v-4H2v4zM4,11h2v2H4v-2z")
    }
    val Document: ImageVector by lazy {
        icon(
            "Document",
            "M14,2H6c-1.1,0 -1.99,0.9 -1.99,2L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8l-6,-6zM16,18H8v-2h8v2zM16,14H8v-2h8v2zM13,9V3.5L18.5,9H13z",
        )
    }
    val Contrast: ImageVector by lazy {
        icon(
            "Contrast",
            "M12,3c-4.97,0 -9,4.03 -9,9s4.03,9 9,9s9,-4.03 9,-9c0,-0.46 -0.04,-0.92 -0.1,-1.36c-0.98,1.37 -2.58,2.26 -4.4,2.26c-2.98,0 -5.4,-2.42 -5.4,-5.4c0,-1.81 0.89,-3.42 2.26,-4.4C12.92,3.04 12.46,3 12,3L12,3z",
        )
    }
    val Key: ImageVector by lazy {
        icon(
            "Key",
            "M12.65,10C11.83,7.67 9.61,6 7,6c-3.31,0 -6,2.69 -6,6s2.69,6 6,6c2.61,0 4.83,-1.67 5.65,-4H17v4h4v-4h2v-4H12.65zM7,14c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2z",
        )
    }
    val Tune: ImageVector by lazy {
        icon("Tune", "M3,17v2h6v-2H3zM3,5v2h10V5H3zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2H3v2h4v2h2V9H7zM21,13v-2H11v2h10zM15,9h2V7h4V5h-4V3h-2v6z")
    }
    val ChevronDown: ImageVector by lazy { icon("ChevronDown", "M7.41,8.59L12,13.17l4.59,-4.58L18,10l-6,6 -6,-6 1.41,-1.41z") }

    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
            .build()
}
