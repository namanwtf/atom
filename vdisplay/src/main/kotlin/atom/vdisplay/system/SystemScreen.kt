package atom.vdisplay.system

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import atom.device.Shells
import atom.device.root.RootAccess
import atom.device.root.RootStatus
import atom.ui.components.AtomMenu
import atom.ui.components.AtomMenuItem
import atom.ui.components.AtomSwitch
import atom.ui.components.CompactButton
import atom.ui.components.Group
import atom.ui.components.GroupDivider
import atom.ui.components.GroupRow
import atom.ui.components.LargeTitleScreen
import atom.ui.components.RowChevron
import atom.ui.components.RowValue
import atom.ui.components.SectionLabel
import atom.ui.feature.FeatureNav
import atom.vdisplay.R
import atom.vdisplay.route.DisplayRouter
import atom.vdisplay.route.LeaseState
import atom.xposed.HookConfig
import atom.xposed.ModuleStatus
import atom.xposed.PowerKeyTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings → System enhancements. */
@Composable
internal fun SystemScreen(nav: FeatureNav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { SystemPrefs.get(context) }
    val hooks by prefs.hooks.collectAsStateWithLifecycle()
    val bg by prefs.backgroundDisplay.collectAsStateWithLifecycle()
    val root by RootAccess.status.collectAsStateWithLifecycle()
    val framework by ModuleStatus.state.collectAsStateWithLifecycle()
    val lease by DisplayRouter.state.collectAsStateWithLifecycle()
    var helpOpen by remember { mutableStateOf(false) }
    // Re-read system state (assistant holder, Google app) each time the screen comes back from system settings.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        ModuleStatus.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) { RootAccess.load(context) }
    val connected = framework as? ModuleStatus.Connection.Connected
    val needsModule = if (connected == null) stringResource(R.string.vd_lsposed_needed) else null

    LargeTitleScreen(stringResource(R.string.vd_system_title), onBack = nav::back) {
        item { SectionLabel(stringResource(R.string.vd_section_status)) }
        item {
            Group {
                GroupRow(
                    stringResource(R.string.vd_root),
                    subtitle = rootLabel(root),
                    trailing = {
                        CompactButton(
                            stringResource(if (root == RootStatus.Granted) R.string.vd_check_again else R.string.vd_authorize),
                            onClick = { scope.launch { RootAccess.authorize(context); Shells.detect(context) } },
                            primary = root != RootStatus.Granted,
                            enabled = root != RootStatus.Checking,
                        )
                    },
                )
                GroupDivider(18.dp)
                GroupRow(
                    stringResource(R.string.vd_framework),
                    subtitle = frameworkLabel(connected),
                    trailing = { CompactButton(stringResource(R.string.vd_check_again), onClick = ModuleStatus::refresh, primary = false) },
                )
                if (connected != null && !connected.framework.scope.containsAll(RECOMMENDED_SCOPE.take(1))) {
                    GroupDivider(18.dp)
                    GroupRow(
                        stringResource(R.string.vd_request_scope),
                        onClick = { ModuleStatus.requestScope(RECOMMENDED_SCOPE + launchers(context)) { _, _ -> } },
                        trailing = { RowChevron() },
                    )
                }
                GroupDivider(18.dp)
                GroupRow(stringResource(R.string.vd_module_help), onClick = { helpOpen = !helpOpen }, trailing = { RowChevron() })
                if (helpOpen) {
                    Text(
                        stringResource(R.string.vd_module_help_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 16.dp),
                    )
                }
            }
        }

        item { SectionLabel(stringResource(R.string.vd_section_assistant)) }
        item {
            Group {
                val holder = remember(resumes) { assistantHolder(context) }
                GroupRow(
                    stringResource(R.string.vd_digital_assistant),
                    onClick = { openAssistantPicker(context) },
                    trailing = { RowValue(holder); RowChevron() },
                )
                GroupDivider(18.dp)
                PowerTargetRow(hooks.powerKeyTarget, needsModule ?: stringResource(R.string.vd_power_sub)) { prefs.setPowerKeyTarget(it) }
                GroupDivider(18.dp)
                ToggleRow(R.string.vd_auto_assistant, needsModule ?: stringResource(R.string.vd_auto_assistant_sub), hooks.autoDefaultAssistant) { on ->
                    prefs.updateHooks { it.copy(autoDefaultAssistant = on) }
                    if (on && connected == null) scope.launch { setAssistantWithRoot(context, prefs.hooks.value) }
                }
            }
        }

        item { SectionLabel(stringResource(R.string.vd_section_cts)) }
        item {
            Group {
                ToggleRow(R.string.vd_cts_bar, needsModule ?: stringResource(R.string.vd_cts_bar_sub), hooks.gestureBarCircleToSearch) { on ->
                    prefs.updateHooks { it.copy(gestureBarCircleToSearch = on) }
                }
                GroupDivider(18.dp)
                ToggleRow(R.string.vd_cts_two_finger, needsModule ?: stringResource(R.string.vd_cts_two_finger_sub), hooks.twoFingerCircleToSearch) { on ->
                    prefs.updateHooks { it.copy(twoFingerCircleToSearch = on) }
                }
                GroupDivider(18.dp)
                val google = remember(resumes) { googleAppStatus(context) }
                GroupRow(stringResource(R.string.vd_google_app), subtitle = stringResource(google) + "\n" + stringResource(R.string.vd_google_eligibility))
            }
        }

        item { SectionLabel(stringResource(R.string.vd_section_protection)) }
        item {
            Group {
                ToggleRow(R.string.vd_enforce_a11y, (needsModule?.let { "$it. " } ?: "") + stringResource(R.string.vd_enforce_a11y_sub), hooks.enforceAccessibility) { on ->
                    prefs.updateHooks { it.copy(enforceAccessibility = on) }
                }
            }
        }

        item { SectionLabel(stringResource(R.string.vd_section_background)) }
        item {
            Group {
                val active = (lease as? LeaseState.Active)?.lease?.takeIf { !it.main }
                val sub = stringResource(R.string.vd_bg_display_sub) +
                    (active?.let { "\n" + stringResource(R.string.vd_bg_active, it.width, it.height) } ?: "")
                ToggleRow(R.string.vd_bg_display, sub, bg) { on ->
                    prefs.setBackgroundDisplay(on)
                    if (!on) DisplayRouter.release()
                }
            }
        }
    }
}

private val RECOMMENDED_SCOPE = listOf("system", "com.android.systemui", HookConfig.GOOGLE_APP)

private fun launchers(context: Context): List<String> =
    context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        .map { it.activityInfo.packageName }
        .filter { it != context.packageName && it != "com.android.settings" }
        .distinct()

@Composable
private fun ToggleRow(title: Int, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    GroupRow(
        stringResource(title),
        subtitle = subtitle,
        onClick = { onChange(!checked) },
        trailing = { AtomSwitch(checked, onCheckedChange = onChange) },
    )
}

@Composable
private fun PowerTargetRow(current: PowerKeyTarget, subtitle: String, onPick: (PowerKeyTarget) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val labels = mapOf(
        PowerKeyTarget.SystemDefault to stringResource(R.string.vd_power_system),
        PowerKeyTarget.Gemini to stringResource(R.string.vd_power_gemini),
        PowerKeyTarget.Atom to stringResource(R.string.vd_power_atom),
    )
    Box {
        GroupRow(
            stringResource(R.string.vd_power_target),
            subtitle = subtitle,
            onClick = { open = true },
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    RowValue(labels.getValue(current))
                    RowChevron()
                }
            },
        )
        AtomMenu(open, onDismiss = { open = false }) {
            PowerKeyTarget.entries.forEach { t ->
                AtomMenuItem(labels.getValue(t), checked = t == current) {
                    open = false
                    onPick(t)
                }
            }
        }
    }
}

@Composable
private fun rootLabel(s: RootStatus): String = stringResource(
    when (s) {
        RootStatus.Granted -> R.string.vd_root_granted
        RootStatus.Checking -> R.string.vd_root_checking
        RootStatus.Unknown -> R.string.vd_root_unknown
        RootStatus.NotDetected -> R.string.vd_root_not_detected
        RootStatus.TimedOut -> R.string.vd_root_timed_out
        RootStatus.NotGranted -> R.string.vd_root_denied
    },
)

@Composable
private fun frameworkLabel(c: ModuleStatus.Connection.Connected?): String {
    if (c == null) return stringResource(R.string.vd_framework_not_connected)
    val f = c.framework
    val lines = mutableListOf(stringResource(R.string.vd_framework_connected, f.name, f.version, f.api))
    lines += if (f.scope.isEmpty()) stringResource(R.string.vd_framework_scope_none) else stringResource(R.string.vd_framework_scope, f.scope.joinToString(", "))
    f.hookedProcesses?.let { hooked ->
        lines += if (hooked.isEmpty()) stringResource(R.string.vd_framework_hooked_none) else stringResource(R.string.vd_framework_hooked, hooked.joinToString(", "))
    }
    return lines.joinToString("\n")
}

private fun assistantHolder(context: Context): String {
    val roles = context.getSystemService(RoleManager::class.java)
    if (roles != null && runCatching { roles.isRoleHeld(RoleManager.ROLE_ASSISTANT) }.getOrDefault(false)) {
        return context.getString(R.string.vd_assistant_atom)
    }
    // "assistant" is readable by apps on current Android; fall back to "not set" when it is not.
    val flat = runCatching { Settings.Secure.getString(context.contentResolver, "assistant") }.getOrNull()
    val pkg = flat?.substringBefore('/')?.takeIf { it.isNotBlank() } ?: return context.getString(R.string.vd_assistant_none)
    val label = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
    return context.getString(R.string.vd_assistant_other, label)
}

private fun openAssistantPicker(context: Context) {
    val intents = listOf(
        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
    )
    for (i in intents) {
        try {
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: ActivityNotFoundException) {
        } catch (e: SecurityException) {
        }
    }
}

private fun googleAppStatus(context: Context): Int {
    val info = try {
        context.packageManager.getApplicationInfo(HookConfig.GOOGLE_APP, 0)
    } catch (e: PackageManager.NameNotFoundException) {
        return R.string.vd_google_missing
    }
    val system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0 || info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
    return when {
        system && "/priv-app/" in (info.sourceDir.orEmpty() + runCatching { context.packageManager.getPackageInfo(HookConfig.GOOGLE_APP, 0).applicationInfo?.publicSourceDir }.getOrNull().orEmpty()) -> R.string.vd_google_priv
        system -> R.string.vd_google_system
        else -> R.string.vd_google_user
    }
}

/** Without the module, "automatically set" can still set the role once, through root, when the switch turns on. */
private suspend fun setAssistantWithRoot(context: Context, hooks: HookConfig) {
    if (!RootAccess.granted) return
    val pkg = hooks.assistantPackage(context.packageName)
    withContext(Dispatchers.IO) {
        runCatching { Shells.detect(context).run("cmd role add-role-holder --user 0 android.app.role.ASSISTANT '$pkg' 0") }
    }
}
