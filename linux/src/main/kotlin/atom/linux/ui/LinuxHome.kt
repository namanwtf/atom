package atom.linux.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import atom.linux.Distro
import atom.linux.LinuxConfig
import atom.linux.LinuxEnv
import atom.linux.LinuxStage
import atom.linux.LinuxState
import atom.linux.R
import atom.linux.RunMode
import atom.linux.TerminalText
import atom.linux.install.Profile
import atom.ui.components.ArrowRow
import atom.ui.components.AtomDialog
import atom.ui.components.AtomRow
import atom.ui.components.AtomTextButton
import atom.ui.components.DialogButtons
import atom.ui.components.Group
import atom.ui.components.GroupRow
import atom.ui.components.LargeTitleScreen
import atom.ui.components.RowValue
import atom.ui.components.SectionLabel
import atom.ui.components.SpinnerOption
import atom.ui.components.SpinnerRow
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale

internal fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "0 MB"
    bytes >= 1_000_000_000L -> String.format(Locale.getDefault(), "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000L -> String.format(Locale.getDefault(), "%.0f MB", bytes / 1e6)
    else -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1e3)
}

/**
 * Settings → Linux environment. A status card with one main action, then the sections in the order the base
 * system unlocks them: Environment configuration, Files and directories (shared folders and the file browser once
 * the base is ready), Optional tools (once the base tools are ready), and Atom's extras.
 */
@Composable
internal fun LinuxHomeScreen(onBack: () -> Unit, open: (String) -> Unit) {
    val context = LocalContext.current
    val env = remember { LinuxEnv.get(context) }
    val state by env.state.collectAsStateWithLifecycle()
    val config by env.config.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var chosen by rememberSaveable { mutableStateOf<Distro?>(null) }
    var confirmUninstall by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var confirmProfile by remember { mutableStateOf<Profile?>(null) }

    LaunchedEffect(state is LinuxState.Ready) {
        if (state is LinuxState.Ready || config.preferredMode == RunMode.Chroot) env.refresh()
    }

    val ready = state as? LinuxState.Ready
    val installing = state as? LinuxState.Installing
    val installed = listOfNotNull(ready?.distro)
    val distro = ready?.distro ?: installing?.distro ?: chosen ?: Distro.preferred(installed)
    val stage = LinuxStage.of(state)

    LargeTitleScreen(title = stringResource(R.string.linux_page_title), onBack = onBack) {
        // ---------------- status card
        item {
            Group(Modifier.padding(top = 12.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val heading = if (ready != null && ready.release.isNotBlank()) "${ready.distro.displayName} ${ready.release}" else distro.displayName
                    Text(heading, fontSize = MiuixTheme.textStyles.title4.fontSize, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface)
                    Text(
                        stringResource(if ((ready?.mode ?: config.preferredMode) == RunMode.Chroot) R.string.linux_mode_root_option else R.string.linux_mode_standard_option),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    StatusText(stage, state, config, distro, env.freeBytes())
                    config.lastResult?.takeIf { ready != null }?.let {
                        Text(
                            stringResource(R.string.linux_last_result, it),
                            style = MiuixTheme.textStyles.footnote1,
                            color = if (config.lastResultOk) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.error,
                        )
                    }
                    problem?.let { Text(stringResource(R.string.linux_error, it), color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1) }
                    Spacer(Modifier.height(6.dp))
                    when (stage) {
                        LinuxStage.NotInstalled -> AtomTextButton(
                            stringResource(if (config.pendingSteps.isNotEmpty() && state is LinuxState.Failed) R.string.linux_continue else R.string.linux_install_base),
                            {
                                problem = null
                                if (config.pendingSteps.isNotEmpty() && state is LinuxState.Failed) env.continueInstall() else env.install(distro)
                            },
                            Modifier.fillMaxWidth(),
                            primary = true,
                        )
                        LinuxStage.Installing -> {
                            AtomTextButton(stringResource(R.string.linux_installing), {}, Modifier.fillMaxWidth(), primary = true, enabled = false)
                            AtomTextButton(stringResource(R.string.linux_cancel), env::cancelInstall, Modifier.fillMaxWidth())
                        }
                        LinuxStage.RootLost -> {
                            AtomTextButton(stringResource(R.string.linux_root_enhancements), { open(ROOT_ENHANCEMENTS_ROUTE) }, Modifier.fillMaxWidth(), primary = true)
                            AtomTextButton(stringResource(R.string.linux_use_standard), { scope.launch { problem = env.setMode(RunMode.Proot) } }, Modifier.fillMaxWidth())
                        }
                        LinuxStage.BaseReady -> AtomTextButton(stringResource(R.string.linux_install_tools), { env.installProfiles(emptySet()) }, Modifier.fillMaxWidth(), primary = true)
                        LinuxStage.ToolsReady -> AtomTextButton(stringResource(R.string.linux_open_terminal), { open(LinuxFeature.ROUTE_TERMINAL) }, Modifier.fillMaxWidth(), primary = true)
                    }
                }
            }
        }

        // ---------------- environment configuration
        item {
            SectionLabel(stringResource(R.string.linux_env_config))
            Group {
                val distros = listOf(Distro.Alpine, Distro.Debian)
                SpinnerRow(
                    title = stringResource(R.string.linux_distribution_row),
                    options = distros.map { SpinnerOption(it.shortName, stringResource(distroSummary(it))) },
                    selectedIndex = distros.indexOf(distro),
                    onSelect = { chosen = distros[it] },
                    summary = if (ready != null) stringResource(R.string.linux_distro_locked) else stringResource(distroSummary(distro)),
                    enabled = ready == null && installing == null,
                )
                val modes = listOf(RunMode.Proot, RunMode.Chroot)
                SpinnerRow(
                    title = stringResource(R.string.linux_mode),
                    options = listOf(
                        SpinnerOption(stringResource(R.string.linux_mode_standard_option), stringResource(R.string.linux_mode_standard_body)),
                        SpinnerOption(stringResource(R.string.linux_mode_root_option), stringResource(R.string.linux_mode_root_body)),
                    ),
                    selectedIndex = modes.indexOf(config.preferredMode),
                    onSelect = { i -> scope.launch { problem = env.setMode(modes[i]) } },
                    summary = if (config.usedChroot && config.preferredMode == RunMode.Proot) stringResource(R.string.linux_mode_files_note) else null,
                    enabled = installing == null,
                )
            }
        }

        // ---------------- files and directories
        item {
            SectionLabel(stringResource(R.string.linux_files_dirs))
            Group {
                ArrowRow(stringResource(R.string.linux_workspace), { open(LinuxFeature.ROUTE_WORKSPACE) }, summary = stringResource(R.string.linux_workspace_body))
                if (ready != null) {
                    ArrowRow(stringResource(R.string.linux_folders), { open(LinuxFeature.ROUTE_FOLDERS) }, summary = stringResource(R.string.linux_folders_body))
                    ArrowRow(stringResource(R.string.linux_files_row), { open(LinuxFeature.ROUTE_FILES) }, summary = stringResource(R.string.linux_files_body))
                }
            }
        }

        // ---------------- optional tools (after the base tools)
        if (ready != null && ready.toolsInstalled) {
            item {
                SectionLabel(stringResource(R.string.linux_optional_tools))
                Group {
                    for (p in Profile.entries) {
                        val version = config.versions[p.name]
                        val has = p in ready.profiles
                        AtomRow(
                            title = stringResource(profileTitle(p)),
                            summary = (if (version != null && has) "$version · " else "") + stringResource(profileBody(p)),
                            end = {
                                Box(Modifier.align(Alignment.CenterVertically)) {
                                    if (has) {
                                        RowValue(stringResource(R.string.linux_installed))
                                    } else {
                                        AtomTextButton(
                                            stringResource(R.string.linux_install),
                                            { if (p == Profile.ApkAnalysis) confirmProfile = p else env.installProfiles(setOf(p)) },
                                            enabled = installing == null,
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }

        // ---------------- Atom's extras: background tasks, health check, terminal text size, upgrades
        item {
            SectionLabel(stringResource(R.string.linux_more))
            Group {
                ArrowRow(stringResource(R.string.linux_jobs), { open(LinuxFeature.ROUTE_JOBS) }, summary = stringResource(R.string.linux_jobs_body))
                ArrowRow(stringResource(R.string.terminal_title), { open(LinuxFeature.ROUTE_TERMINAL) })
                if (ready != null) {
                    ArrowRow(stringResource(R.string.linux_health), { open(LinuxFeature.ROUTE_HEALTH) }, summary = stringResource(R.string.linux_health_body))
                    if (ready.pendingSteps.isNotEmpty()) ArrowRow(stringResource(R.string.linux_continue), env::continueInstall, enabled = installing == null)
                    if (ready.toolsInstalled) ArrowRow(stringResource(R.string.linux_upgrade), env::upgrade, enabled = installing == null)
                }
                val size = config.terminalFontSp
                AtomRow(
                    title = stringResource(R.string.linux_text_size),
                    summary = stringResource(R.string.linux_text_size_body, size.toInt().toString()),
                    end = {
                        Row(Modifier.align(Alignment.CenterVertically), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AtomTextButton("A−", { env.setTerminalFontSp(size - 1f) }, enabled = size > TerminalText.MIN_SP)
                            AtomTextButton("A+", { env.setTerminalFontSp(size + 1f) }, enabled = size < TerminalText.MAX_SP)
                        }
                    },
                )
            }
        }

        if (ready != null) {
            item {
                Group(Modifier.padding(top = 16.dp)) {
                    GroupRow(
                        title = stringResource(if (removing) R.string.linux_uninstalling else R.string.linux_uninstall),
                        titleColor = MiuixTheme.colorScheme.error,
                        enabled = !removing && installing == null,
                        onClick = { confirmUninstall = true },
                    )
                }
            }
        }
    }

    AtomDialog(
        show = confirmProfile != null,
        onDismiss = { confirmProfile = null },
        title = confirmProfile?.let { stringResource(R.string.linux_profile_confirm_title, stringResource(profileTitle(it))) },
        summary = confirmProfile?.let { stringResource(R.string.linux_profile_confirm_body, formatBytes(it.approxBytes), formatBytes(env.freeBytes())) },
    ) {
        DialogButtons(
            confirmText = stringResource(R.string.linux_profile_confirm_ok),
            onConfirm = {
                confirmProfile?.let { env.installProfiles(setOf(it)) }
                confirmProfile = null
            },
            dismissText = stringResource(R.string.linux_cancel),
            onDismiss = { confirmProfile = null },
        )
    }

    AtomDialog(
        show = confirmUninstall && ready != null,
        onDismiss = { confirmUninstall = false },
        title = ready?.let { stringResource(R.string.linux_uninstall_title, it.distro.displayName) },
        summary = stringResource(R.string.linux_uninstall_body),
    ) {
        DialogButtons(
            confirmText = stringResource(R.string.linux_uninstall),
            onConfirm = {
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
            },
            dismissText = stringResource(R.string.linux_cancel),
            onDismiss = { confirmUninstall = false },
        )
    }
}

/** The settings route of Root and system enhancements (another module); opened when root is lost. */
private const val ROOT_ENHANCEMENTS_ROUTE = "vdisplay/system"

@Composable
private fun StatusText(stage: LinuxStage, state: LinuxState, config: LinuxConfig, distro: Distro, free: Long) {
    val c = MiuixTheme.colorScheme
    when (stage) {
        LinuxStage.NotInstalled -> {
            Text(stringResource(R.string.linux_not_installed), style = MiuixTheme.textStyles.body2, color = c.onSurfaceVariantSummary)
            (state as? LinuxState.Failed)?.let {
                Text(stringResource(R.string.linux_failed) + ": " + it.message, style = MiuixTheme.textStyles.body2, color = c.error)
            }
            val need = distro.downloadBytes + distro.installedBytes
            if (free in 1 until need) {
                Text(stringResource(R.string.linux_space_warning, formatBytes(need), formatBytes(free)), color = c.error, style = MiuixTheme.textStyles.body2)
            }
        }
        LinuxStage.Installing -> {
            val i = state as LinuxState.Installing
            Text(
                stringResource(R.string.linux_stage, i.stage.ifEmpty { i.step }, i.stageIndex + 1, i.stageCount),
                style = MiuixTheme.textStyles.body2,
                color = c.onSurface,
            )
            LinearProgressIndicator(progress = { i.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            Text(stringResource(R.string.linux_progress, i.step, (i.progress * 100).toInt()), style = MiuixTheme.textStyles.footnote1, color = c.onSurfaceVariantSummary)
        }
        LinuxStage.RootLost -> Text(stringResource(R.string.linux_root_lost), style = MiuixTheme.textStyles.body2, color = c.error)
        LinuxStage.BaseReady -> Text(stringResource(R.string.linux_status_base_ready), style = MiuixTheme.textStyles.body2, color = c.onSurfaceVariantSummary)
        LinuxStage.ToolsReady -> Text(
            stringResource(R.string.linux_status_ready, formatBytes((state as LinuxState.Ready).sizeBytes)) +
                if (config.profiles.isNotEmpty()) " · " + config.profiles.joinToString { it.title } else "",
            style = MiuixTheme.textStyles.body2,
            color = c.onSurfaceVariantSummary,
        )
    }
}

private fun distroSummary(d: Distro) = when (d) {
    Distro.Alpine -> R.string.linux_alpine_summary
    Distro.Debian -> R.string.linux_debian_summary
}

private fun profileTitle(p: Profile) = when (p) {
    Profile.Python -> R.string.linux_profile_python_title
    Profile.Node -> R.string.linux_profile_node_title
    Profile.SshServer -> R.string.linux_profile_ssh_title
    Profile.ApkAnalysis -> R.string.linux_profile_apk_title
}

private fun profileBody(p: Profile) = when (p) {
    Profile.Python -> R.string.linux_profile_python
    Profile.Node -> R.string.linux_profile_node
    Profile.SshServer -> R.string.linux_profile_ssh
    Profile.ApkAnalysis -> R.string.linux_profile_apk
}
