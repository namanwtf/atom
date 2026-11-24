@file:OptIn(ExperimentalMaterial3Api::class)

package atom.app.ui.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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
import atom.app.ui.common.SectionHeader
import atom.app.ui.theme.AtomIcons
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
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val savedKeys by vm.savedKeys.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val s = settings ?: AppSettings()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            SectionHeader(stringResource(R.string.settings_engine))
            Group {
                val selection = s.selection
                val (title, subtitle) = when (selection) {
                    null -> stringResource(R.string.engine_none) to stringResource(R.string.settings_engine_none_sub)
                    is EngineSelection.Cloud -> selection.model to (
                        ProviderResolver.resolve(selection.providerId, selection.baseUrl, selection.model)?.displayName
                            ?: selection.providerId
                        )
                    is EngineSelection.Local -> (
                        installed.firstOrNull { it.id == selection.installedModelId }?.name
                            ?: stringResource(R.string.engine_model_missing)
                        ) to stringResource(R.string.badge_on_device)
                }
                NavRow(
                    icon = if (selection is EngineSelection.Local) AtomIcons.Chip else AtomIcons.Cloud,
                    title = title,
                    subtitle = subtitle,
                    onClick = onOpenModels,
                )
                if (selection is EngineSelection.Local) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.settings_backend), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.settings_backend_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.size(10.dp))
                        val options = LocalBackend.entries
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            options.forEachIndexed { i, backend ->
                                SegmentedButton(
                                    selected = selection.backend == backend,
                                    onClick = { vm.setBackend(backend) },
                                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                                ) { Text(backendLabel(backend)) }
                            }
                        }
                    }
                }
            }

            SectionHeader(stringResource(R.string.settings_fallback))
            Group {
                var open by remember { mutableStateOf(false) }
                val fallback = s.cloudFallback
                Box {
                    NavRow(
                        icon = AtomIcons.Bolt,
                        title = fallback?.let {
                            ProviderResolver.resolve(it.providerId, it.baseUrl, it.model)?.displayName ?: it.providerId
                        } ?: stringResource(R.string.settings_fallback_none),
                        subtitle = fallback?.model ?: stringResource(R.string.settings_fallback_desc),
                        onClick = { open = true },
                    )
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.settings_fallback_none)) }, onClick = {
                            open = false
                            vm.setFallbackProvider(null)
                        })
                        ProviderResolver.all().filter { it.id in savedKeys }.forEach { p ->
                            DropdownMenuItem(text = { Text(p.displayName) }, onClick = {
                                open = false
                                vm.setFallbackProvider(p.id)
                            })
                        }
                        if (savedKeys.isEmpty()) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.settings_fallback_add_key)) }, onClick = {
                                open = false
                                onOpenModels()
                            })
                        }
                    }
                }
            }
            Text(
                stringResource(R.string.cloud_fallback_explain),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )

            SectionHeader(stringResource(R.string.settings_safety))
            Group {
                SwitchRow(
                    icon = Icons.Filled.Lock,
                    title = stringResource(R.string.settings_confirm_act),
                    subtitle = stringResource(R.string.settings_confirm_act_desc),
                    checked = s.confirmActTools,
                    onChange = vm::setConfirmAct,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NavRow(
                    icon = Icons.Filled.Settings,
                    title = stringResource(R.string.screen_setup),
                    subtitle = stringResource(R.string.settings_setup_desc),
                    onClick = onOpenSetup,
                )
            }

            SectionHeader(stringResource(R.string.settings_appearance))
            Group {
                Column(Modifier.padding(16.dp)) {
                    val modes = listOf(ThemeMode.Dark, ThemeMode.Light, ThemeMode.System)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        modes.forEachIndexed { i, mode ->
                            SegmentedButton(
                                selected = s.theme == mode,
                                onClick = { vm.setTheme(mode) },
                                shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                            ) {
                                Text(
                                    stringResource(
                                        when (mode) {
                                            ThemeMode.Dark -> R.string.theme_dark
                                            ThemeMode.Light -> R.string.theme_light
                                            ThemeMode.System -> R.string.theme_system
                                        },
                                    ),
                                )
                            }
                        }
                    }
                }
            }

            SectionHeader(stringResource(R.string.settings_about))
            Group {
                NavRow(
                    icon = Icons.Filled.Info,
                    title = stringResource(R.string.app_name),
                    subtitle = stringResource(R.string.about_version, versionName(context)),
                    onClick = null,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NavRow(
                    icon = null,
                    title = stringResource(R.string.about_license),
                    subtitle = stringResource(R.string.about_license_view),
                    onClick = onOpenLicenses,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NavRow(
                    icon = null,
                    title = stringResource(R.string.screen_licenses),
                    subtitle = stringResource(R.string.about_notices_desc),
                    onClick = onOpenLicenses,
                )
            }
            Text(
                stringResource(R.string.about_trademark),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
            Spacer(Modifier.size(32.dp))
        }
    }
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column { content() }
    }
}

@Composable
private fun NavRow(icon: ImageVector?, title: String, subtitle: String?, onClick: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun backendLabel(backend: LocalBackend) = stringResource(
    when (backend) {
        LocalBackend.Auto -> R.string.backend_auto
        LocalBackend.Npu -> R.string.backend_npu
        LocalBackend.Gpu -> R.string.backend_gpu
        LocalBackend.Cpu -> R.string.backend_cpu
    },
)

private fun versionName(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
