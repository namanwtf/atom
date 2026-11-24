package atom.app.ui.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import atom.app.R
import atom.app.ui.common.AtomCard
import atom.app.ui.common.LargeTitleScreen
import atom.app.ui.common.Pill
import atom.app.ui.common.PrimaryButton
import atom.app.ui.common.SecondaryButton
import atom.app.ui.common.formatBytes
import atom.app.ui.theme.extras
import atom.linux.Distro
import atom.linux.LinuxEnv
import atom.linux.LinuxState
import atom.linux.RunMode
import kotlinx.coroutines.launch

/** One-tap install of Alpine or Debian, live progress, and the installed system's status. */
@Composable
fun LinuxScreen(onBack: () -> Unit, onOpenTerminal: () -> Unit) {
    val context = LocalContext.current
    val env = remember { LinuxEnv.get(context) }
    val state by env.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<Distro?>(null) }
    var removing by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    // Only touch su (for the chroot check) once something is installed.
    LaunchedEffect(state is LinuxState.Ready) {
        if (state is LinuxState.Ready) env.refresh()
    }

    LargeTitleScreen(title = stringResource(R.string.linux_title), onBack = onBack) {
        item {
            Text(
                stringResource(R.string.linux_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 16.dp),
            )
        }
        val failed = state as? LinuxState.Failed
        val shownProblem = problem ?: failed?.message
        if (shownProblem != null) {
            item {
                AtomCard(Modifier.padding(bottom = 12.dp)) {
                    Text(
                        if (problem != null) stringResource(R.string.linux_uninstall_failed, shownProblem) else stringResource(R.string.linux_failed),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (problem == null) {
                        Text(shownProblem, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        for (distro in listOf(Distro.Alpine, Distro.Debian)) {
            item(key = distro.name) {
                DistroCard(
                    distro = distro,
                    state = state,
                    removing = removing,
                    onInstall = {
                        problem = null
                        env.install(distro)
                    },
                    onCancel = env::cancelInstall,
                    onOpenTerminal = onOpenTerminal,
                    onUninstall = { confirm = distro },
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
        }
    }

    val target = confirm
    if (target != null) {
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.linux_uninstall_title, target.displayName)) },
            text = { Text(stringResource(R.string.linux_uninstall_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
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
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.linux_cancel)) } },
        )
    }
}

@Composable
private fun DistroCard(
    distro: Distro,
    state: LinuxState,
    removing: Boolean,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onOpenTerminal: () -> Unit,
    onUninstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (name, body) = when (distro) {
        Distro.Alpine -> R.string.linux_alpine_name to R.string.linux_alpine_body
        Distro.Debian -> R.string.linux_debian_name to R.string.linux_debian_body
    }
    AtomCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(name), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
            if (distro == Distro.Alpine) {
                Pill(stringResource(R.string.linux_recommended), MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        when {
            state is LinuxState.Installing && state.distro == distro -> {
                LinearProgressIndicator(progress = { state.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.linux_progress, state.step, (state.progress * 100).toInt()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SecondaryButton(stringResource(R.string.linux_cancel), onCancel)
            }
            state is LinuxState.Ready && state.distro == distro -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(
                        stringResource(if (state.mode == RunMode.Chroot) R.string.linux_mode_chroot else R.string.linux_mode_proot),
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.extras.success,
                    )
                    Text(
                        stringResource(R.string.linux_ready, formatBytes(state.sizeBytes)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                PrimaryButton(stringResource(R.string.linux_open_terminal), onOpenTerminal, enabled = !removing)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SecondaryButton(
                        stringResource(if (removing) R.string.linux_uninstalling else R.string.linux_uninstall),
                        onUninstall,
                        enabled = !removing,
                    )
                    if (removing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            else -> {
                Text(
                    stringResource(R.string.linux_sizes, formatBytes(distro.downloadBytes), formatBytes(distro.installedBytes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val other = when (state) {
                    is LinuxState.Ready -> state.distro
                    is LinuxState.Installing -> state.distro
                    else -> null
                }
                if (other != null && other != distro) {
                    Text(
                        stringResource(R.string.linux_only_one, other.displayName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (distro == Distro.Alpine) {
                    PrimaryButton(stringResource(R.string.linux_install), onInstall)
                } else {
                    SecondaryButton(stringResource(R.string.linux_install), onInstall, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
