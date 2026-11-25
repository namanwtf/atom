package atom.linux.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import atom.linux.Distro
import atom.linux.LinuxEnv
import atom.linux.LinuxState
import atom.linux.R
import atom.linux.RunMode
import atom.linux.install.Profile
import atom.ui.components.AtomCard
import atom.ui.components.CompactButton
import atom.ui.components.Group
import atom.ui.components.GroupDivider
import atom.ui.components.GroupRow
import atom.ui.components.LargeTitleScreen
import atom.ui.components.Pill
import atom.ui.components.PrimaryButton
import atom.ui.components.RowChevron
import atom.ui.components.ScreenIcons
import atom.ui.components.SectionLabel
import atom.ui.components.SegmentedControl
import atom.ui.theme.AtomIcons
import atom.ui.theme.extras
import kotlinx.coroutines.launch
import java.util.Locale

internal fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "0 MB"
    bytes >= 1_000_000_000L -> String.format(Locale.getDefault(), "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000L -> String.format(Locale.getDefault(), "%.0f MB", bytes / 1e6)
    else -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1e3)
}

/** Settings → Linux environment: status, distro and mode pickers, install/upgrade, profiles and the sub-pages. */
@Composable
internal fun LinuxHomeScreen(onBack: () -> Unit, open: (String) -> Unit) {
    val context = LocalContext.current
    val env = remember { LinuxEnv.get(context) }
    val state by env.state.collectAsStateWithLifecycle()
    val config by env.config.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var distroIndex by rememberSaveable { mutableStateOf(0) }
    var confirmUninstall by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var rootOk by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(state is LinuxState.Ready) {
        if (state is LinuxState.Ready || config.preferredMode == RunMode.Chroot) env.refresh()
        rootOk = env.rootProbe?.chrootCapable
    }

    val ready = state as? LinuxState.Ready
    val installing = state as? LinuxState.Installing

    LargeTitleScreen(title = stringResource(R.string.linux_title), onBack = onBack) {
        item {
            Text(
                stringResource(R.string.linux_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
            )
        }

        // ---------------- status card
        item {
            SectionLabel(stringResource(R.string.linux_status))
            AtomCard {
                when {
                    installing != null -> {
                        Text(installing.distro.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.linux_stage, installing.stage.ifEmpty { installing.step }, installing.stageIndex + 1, installing.stageCount),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        LinearProgressIndicator(progress = { installing.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text(
                            stringResource(R.string.linux_progress, installing.step, (installing.progress * 100).toInt()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        CompactButton(stringResource(R.string.linux_cancel), env::cancelInstall, primary = false)
                    }
                    ready != null -> {
                        Text(
                            stringResource(R.string.linux_ready_line, ready.distro.displayName, ready.release, formatBytes(ready.sizeBytes)),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Pill(
                                stringResource(if (ready.mode == RunMode.Chroot) R.string.linux_mode_line_chroot else R.string.linux_mode_line_proot),
                                MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = if (ready.rootLost) MaterialTheme.colorScheme.error else MaterialTheme.extras.success,
                            )
                        }
                        if (ready.rootLost) {
                            Text(stringResource(R.string.linux_root_lost), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                            CompactButton(stringResource(R.string.linux_use_standard), { scope.launch { problem = env.setMode(RunMode.Proot) } })
                        }
                        config.lastResult?.let {
                            Text(
                                stringResource(R.string.linux_last_result, it),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (config.lastResultOk) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (ready.pendingSteps.isNotEmpty()) CompactButton(stringResource(R.string.linux_continue), env::continueInstall)
                            CompactButton(stringResource(R.string.linux_upgrade), env::upgrade, primary = ready.pendingSteps.isEmpty())
                        }
                        PrimaryButton(stringResource(R.string.linux_open_terminal), { open(LinuxFeature.ROUTE_TERMINAL) })
                    }
                    else -> {
                        Text(stringResource(R.string.linux_not_installed), style = MaterialTheme.typography.titleMedium)
                        (state as? LinuxState.Failed)?.let {
                            Text(stringResource(R.string.linux_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
                            Text(it.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                problem?.let { Text(stringResource(R.string.linux_error, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }

        // ---------------- distribution + install (only when nothing is installed)
        if (ready == null && installing == null) {
            item {
                SectionLabel(stringResource(R.string.linux_distribution))
                AtomCard {
                    SegmentedControl(
                        options = listOf(Distro.Alpine.displayName to null, "Debian Trixie" to null),
                        selected = distroIndex,
                        onSelect = { distroIndex = it },
                    )
                    Text(
                        stringResource(if (distroIndex == 0) R.string.linux_alpine_body else R.string.linux_debian_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val distro = if (distroIndex == 0) Distro.Alpine else Distro.Debian
                    val need = distro.downloadBytes + distro.installedBytes
                    val free = env.freeBytes()
                    if (free in 1 until need) {
                        Text(stringResource(R.string.linux_space_warning, formatBytes(need), formatBytes(free)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (config.pendingSteps.isNotEmpty() && state is LinuxState.Failed) {
                        PrimaryButton(stringResource(R.string.linux_continue), env::continueInstall)
                    } else {
                        PrimaryButton(stringResource(R.string.linux_install), { problem = null; env.install(distro) })
                    }
                }
            }
        }

        // ---------------- execution mode
        item {
            SectionLabel(stringResource(R.string.linux_mode))
            AtomCard {
                SegmentedControl(
                    options = listOf(stringResource(R.string.linux_mode_standard) to null, stringResource(R.string.linux_mode_root) to null),
                    selected = if (config.preferredMode == RunMode.Chroot) 1 else 0,
                    onSelect = { i ->
                        scope.launch {
                            problem = env.setMode(if (i == 1) RunMode.Chroot else RunMode.Proot)
                            rootOk = env.rootProbe?.chrootCapable
                        }
                    },
                )
                Text(
                    stringResource(if (config.preferredMode == RunMode.Chroot) R.string.linux_mode_root_body else R.string.linux_mode_standard_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (config.usedChroot && config.preferredMode == RunMode.Proot) {
                    Text(stringResource(R.string.linux_mode_files_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // ---------------- tools and profiles
        if (ready != null) {
            item {
                SectionLabel(stringResource(R.string.linux_profiles))
                Group {
                    GroupRow(
                        title = stringResource(R.string.linux_base_tools),
                        subtitle = stringResource(R.string.linux_base_tools_body),
                        trailing = {
                            if (ready.toolsInstalled) {
                                Pill(stringResource(R.string.linux_installed), MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.extras.success)
                            } else {
                                CompactButton(stringResource(R.string.linux_install), { env.installProfiles(emptySet()) })
                            }
                        },
                    )
                    for (p in Profile.entries) {
                        GroupDivider(inset = 18.dp)
                        val version = config.versions[p.name]
                        GroupRow(
                            title = p.title,
                            subtitle = (if (version != null && p in ready.profiles) "$version · " else "") + stringResource(profileBody(p)),
                            trailing = {
                                if (p in ready.profiles) {
                                    Pill(stringResource(R.string.linux_installed), MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.extras.success)
                                } else {
                                    CompactButton(stringResource(R.string.linux_install), { env.installProfiles(setOf(p)) }, primary = false)
                                }
                            },
                        )
                    }
                }
            }
        }

        // ---------------- sub-pages
        item {
            SectionLabel(stringResource(R.string.linux_more))
            Group {
                GroupRow(stringResource(R.string.linux_health), icon = Icons.Filled.Build, subtitle = stringResource(R.string.linux_health_body), enabled = ready != null, onClick = { open(LinuxFeature.ROUTE_HEALTH) }, trailing = { RowChevron() })
                GroupDivider()
                GroupRow(stringResource(R.string.linux_folders), icon = ScreenIcons.Storage, subtitle = stringResource(R.string.linux_folders_body), onClick = { open(LinuxFeature.ROUTE_FOLDERS) }, trailing = { RowChevron() })
                GroupDivider()
                GroupRow(stringResource(R.string.linux_files), icon = ScreenIcons.Document, subtitle = stringResource(R.string.linux_files_body), enabled = ready != null, onClick = { open(LinuxFeature.ROUTE_FILES) }, trailing = { RowChevron() })
                GroupDivider()
                GroupRow(stringResource(R.string.linux_workspace), icon = Icons.Filled.Refresh, subtitle = stringResource(R.string.linux_workspace_body), onClick = { open(LinuxFeature.ROUTE_WORKSPACE) }, trailing = { RowChevron() })
                GroupDivider()
                GroupRow(stringResource(R.string.linux_jobs), icon = Icons.Filled.List, subtitle = stringResource(R.string.linux_jobs_body), onClick = { open(LinuxFeature.ROUTE_JOBS) }, trailing = { RowChevron() })
                GroupDivider()
                GroupRow(stringResource(R.string.terminal_title), icon = AtomIcons.Terminal, onClick = { open(LinuxFeature.ROUTE_TERMINAL) }, trailing = { RowChevron() })
            }
        }

        if (ready != null) {
            item {
                Group(Modifier.padding(top = 16.dp)) {
                    GroupRow(
                        title = stringResource(if (removing) R.string.linux_uninstalling else R.string.linux_uninstall),
                        icon = Icons.Filled.Delete,
                        titleColor = MaterialTheme.colorScheme.error,
                        enabled = !removing,
                        onClick = { confirmUninstall = true },
                    )
                }
            }
        }
    }

    if (confirmUninstall && ready != null) {
        AlertDialog(
            onDismissRequest = { confirmUninstall = false },
            title = { Text(stringResource(R.string.linux_uninstall_title, ready.distro.displayName)) },
            text = { Text(stringResource(R.string.linux_uninstall_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUninstall = false
                    removing = true
                    problem = null
                    scope.launch {
                        try {
                            env.uninstall()
                        } catch (e: Exception) {
                            problem = e.message ?: e.javaClass.simpleName
                        } finally {
                            removing = false
                        }
                    }
                }) { Text(stringResource(R.string.linux_uninstall), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmUninstall = false }) { Text(stringResource(R.string.linux_cancel)) } },
        )
    }
}

private fun profileBody(p: Profile) = when (p) {
    Profile.Python -> R.string.linux_profile_python
    Profile.Node -> R.string.linux_profile_node
    Profile.SshServer -> R.string.linux_profile_ssh
    Profile.ApkAnalysis -> R.string.linux_profile_apk
}
