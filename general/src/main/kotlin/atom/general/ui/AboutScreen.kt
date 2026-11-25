package atom.general.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import atom.general.GeneralConfig
import atom.general.R
import atom.general.update.InstallState
import atom.general.update.UpdateChecker
import atom.general.update.UpdateInstaller
import atom.general.update.UpdateStatus
import atom.ui.components.CompactButton
import atom.ui.components.Group
import atom.ui.components.GroupDivider
import atom.ui.components.GroupRow
import atom.ui.components.LargeTitleScreen
import atom.ui.components.RowChevron
import atom.ui.components.RowValue
import atom.ui.components.ScreenIcons
import atom.ui.feature.FeatureNav
import atom.ui.theme.AtomIcons
import kotlinx.coroutines.launch

/** Route of the app's existing open-source licenses screen. */
const val LICENSES_ROUTE = "licenses"

@Composable
internal fun AboutScreen(nav: FeatureNav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version = remember { appVersion(context) }
    val repo = GeneralConfig.updateRepo.trim()
    val source = GeneralConfig.sourceUrl.trim()
    val feedback = GeneralConfig.feedback.trim()
    var status by remember { mutableStateOf(if (repo.isEmpty()) UpdateStatus.NotConfigured else UpdateChecker.cachedFor(repo)) }
    var checking by remember { mutableStateOf(false) }
    val installState by UpdateInstaller.state.collectAsState()

    fun check(force: Boolean) {
        if (repo.isEmpty() || checking) return
        if (force) UpdateInstaller.reset()
        scope.launch {
            checking = true
            status = UpdateChecker.check(repo, version, force)
            checking = false
        }
    }
    // The only automatic check: once when About opens (and only if a repository is configured).
    LaunchedEffect(repo) { if (repo.isNotEmpty() && status == null) check(force = false) }

    LargeTitleScreen(title = stringResource(R.string.about_title), onBack = nav::back) {
        item(key = "hero") {
            Column(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(R.string.about_app_name), style = MaterialTheme.typography.headlineMedium)
                Text(
                    stringResource(R.string.about_tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item(key = "g-version") {
            Group {
                GroupRow(
                    title = stringResource(R.string.about_version_row),
                    icon = GeneralIcons.Info,
                    trailing = { RowValue(version) },
                )
                GroupDivider()
                val s = status
                val install = installState
                val subtitle = when {
                    checking -> stringResource(R.string.about_checking)
                    s is UpdateStatus.Available && install !is InstallState.Idle -> when (install) {
                        is InstallState.Downloading -> stringResource(R.string.about_update_downloading, install.percent)
                        InstallState.Verifying -> stringResource(R.string.about_update_verifying)
                        InstallState.NeedsPermission -> stringResource(R.string.about_update_permission)
                        InstallState.WaitingForUser -> stringResource(R.string.about_update_confirm)
                        is InstallState.Failed -> stringResource(R.string.about_update_failed, install.reason)
                        InstallState.Idle -> null
                    }
                    s == null -> stringResource(R.string.about_tap_to_check)
                    s is UpdateStatus.NotConfigured -> stringResource(R.string.about_updates_not_configured)
                    s is UpdateStatus.UpToDate -> stringResource(R.string.about_up_to_date)
                    s is UpdateStatus.Available -> stringResource(R.string.about_update_available, s.latest, s.current)
                    s is UpdateStatus.NoReleases -> stringResource(R.string.about_no_releases)
                    s is UpdateStatus.Failed -> stringResource(R.string.about_check_failed, s.reason)
                    else -> null
                }
                GroupRow(
                    title = stringResource(R.string.about_check_updates),
                    subtitle = subtitle,
                    icon = AtomIcons.Download,
                    enabled = repo.isNotEmpty() && !checking,
                    onClick = { check(force = true) },
                    trailing = if (s is UpdateStatus.Available) {
                        {
                            val apk = s.apk
                            if (apk != null && install !is InstallState.Failed) {
                                // Downloads, checks the SHA-256 and signing key, then opens the system installer.
                                CompactButton(
                                    stringResource(R.string.about_update),
                                    onClick = { UpdateInstaller.start(context, apk) },
                                )
                            } else {
                                CompactButton(
                                    stringResource(R.string.about_download),
                                    onClick = { openExternal(context, s.releaseUrl) },
                                )
                            }
                        }
                    } else {
                        null
                    },
                )
            }
            FootNote(stringResource(R.string.about_no_installs))
        }
        if (source.isNotEmpty() || feedback.isNotEmpty()) {
            item(key = "g-links") {
                Group(Modifier.padding(top = 18.dp)) {
                    if (source.isNotEmpty()) {
                        GroupRow(
                            title = stringResource(R.string.about_source),
                            subtitle = source.removePrefix("https://"),
                            icon = AtomIcons.Link,
                            onClick = { openExternal(context, source) },
                            trailing = { RowChevron() },
                        )
                    }
                    if (source.isNotEmpty() && feedback.isNotEmpty()) GroupDivider()
                    if (feedback.isNotEmpty()) {
                        val subject = stringResource(R.string.about_feedback_subject) + " " + version
                        GroupRow(
                            title = stringResource(R.string.about_feedback),
                            subtitle = stringResource(R.string.about_feedback_sub),
                            icon = ScreenIcons.Document,
                            onClick = { openExternal(context, feedback, subject) },
                            trailing = { RowChevron() },
                        )
                    }
                }
            }
        }
        item(key = "g-licenses") {
            Group(Modifier.padding(top = 18.dp)) {
                GroupRow(
                    title = stringResource(R.string.about_licenses),
                    subtitle = stringResource(R.string.about_licenses_sub),
                    icon = ScreenIcons.Shield,
                    onClick = { nav.open(LICENSES_ROUTE) },
                    trailing = { RowChevron() },
                )
            }
        }
    }
}
