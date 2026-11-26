package atom.device.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import atom.device.ShizukuState
import atom.device.Shells
import atom.device.caps.CapAction
import atom.device.caps.CapState
import atom.device.caps.CapStatus
import atom.device.caps.Capabilities
import atom.device.caps.Capability
import atom.device.caps.PermissionRows
import atom.device.caps.Perms
import atom.device.caps.StatusTone
import atom.device.root.RootAccess
import atom.device.root.RootStatus
import atom.device.root.RootMonitor
import atom.ui.components.AtomDialog
import atom.ui.components.DialogButtons
import atom.ui.components.CompactButton
import atom.ui.components.Group
import atom.ui.components.GroupRow
import atom.ui.components.LargeTitleScreen
import atom.ui.components.RowChevron
import atom.ui.components.SectionLabel
import atom.ui.feature.FeatureNav
import atom.ui.theme.extras
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val ROUTE_SYSTEM = "vdisplay/system"

@Composable
internal fun statusColor(s: CapStatus, optional: Boolean = false): Color = when (PermissionRows.tone(s, optional)) {
    StatusTone.Good -> MaterialTheme.extras.success
    StatusTone.Warning -> MaterialTheme.extras.warning
    StatusTone.Bad -> MiuixTheme.colorScheme.error
    StatusTone.Muted -> MiuixTheme.colorScheme.onSurfaceVariantActions
}

internal fun Context.open(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        startActivity(Perms.appDetails(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: SecurityException) {
        // Some system pages may only be opened by the system; the app's own settings page always works.
        startActivity(Perms.appDetails(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** The local-network capability ("Nearby devices" on Android 13+, "Local network" on newer releases). */
private const val NEARBY = "nearby"

/** The system page that lists Atom's permissions; [Context.open] falls back to app info if it cannot open. */
private fun permissionsPage(context: Context): Intent =
    Intent("android.intent.action.MANAGE_APP_PERMISSIONS").putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)

/**
 * Permissions and status: the twelve classic rows, each an arrow row with a coloured status on the right; tapping
 * the row asks for the permission or opens the system page. Everything newer (microphone, contacts, Shizuku...)
 * follows below, with the Current device / All capabilities switch. Reading this page never prompts.
 */
@Composable
fun PermissionsScreen(nav: FeatureNav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var allView by rememberSaveable { mutableStateOf(false) }
    var denied by remember { mutableStateOf(setOf<String>()) }
    var pendingCap by remember { mutableStateOf<String?>(null) }
    var explainNearby by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val cap = pendingCap
        if (cap != null && result.values.any { !it }) {
            denied = denied + cap
            if (cap == NEARBY) explainNearby = true
        }
        pendingCap = null
        tick++
    }
    val root by RootAccess.status.collectAsState()
    val shizuku by produceState(initialValue = null as ShizukuState?, tick) { value = Shells.shizukuState() }
    val shizukuInstalled = remember(tick) {
        runCatching { context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0); true }.getOrDefault(false)
    }

    val caps = remember(tick) { Capabilities.all }
    val byId = caps.associateBy { it.id }
    val states = remember(tick, root) { caps.associate { it.id to it.state(context) } }

    fun act(c: Capability) {
        when (val a = c.action(context)) {
            is CapAction.Request -> if (c.id == NEARBY && c.id in denied) {
                explainNearby = true
            } else if (c.id in denied) {
                context.open(Perms.appDetails(context))
            } else {
                pendingCap = c.id
                launcher.launch(a.permissions.toTypedArray())
            }
            is CapAction.OpenSettings -> context.open(a.intent(context))
            null -> Unit
        }
    }

    if (explainNearby) {
        AtomDialog(show = true, onDismiss = { explainNearby = false }, title = "Local network access") {
            Column {
                Text(
                    "You can allow Nearby devices for Atom in the system settings (Permissions › Nearby devices). Atom " +
                        "needs it for models, MCP servers, the browser and terminals on your local network; internet " +
                        "access keeps working without it.",
                )
                DialogButtons(
                    confirmText = "Open settings",
                    onConfirm = {
                        explainNearby = false
                        context.open(permissionsPage(context))
                    },
                    dismissText = "Not now",
                    onDismiss = { explainNearby = false },
                )
            }
        }
    }

    LargeTitleScreen(title = "Permissions", onBack = nav::back) {
        item(key = "h-classic") { SectionLabel("Permissions and status") }
        item(key = "g-classic") {
            Group {
                PermissionRows.classic(byId.keys).forEach { (id, title) ->
                    if (id == PermissionRows.ROOT) {
                        StatusRow(
                            title = title,
                            summary = "Optional enhancement permissions",
                            status = PermissionRows.rootStatus(root),
                            optional = true,
                            onClick = { nav.open(ROUTE_SYSTEM) },
                        )
                    } else {
                        val c = byId.getValue(id)
                        val st = states[id] ?: CapState(CapStatus.Checking)
                        StatusRow(title = title, summary = summaryOf(c, st, allView), status = st.status, onClick = { act(c) })
                    }
                }
            }
        }

        item(key = "tabs") {
            TabRow(
                tabs = listOf("Current device", "All capabilities"),
                selectedTabIndex = if (allView) 1 else 0,
                onTabSelected = { allView = it == 1 },
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
            )
        }

        val others = PermissionRows.others(caps.map { it.id }).mapNotNull(byId::get).filter { allView || it.applicable(context) }
        if (others.isNotEmpty()) {
            item(key = "h-more") { SectionLabel("More permissions") }
            item(key = "g-more") {
                Group {
                    others.forEach { c ->
                        val st = states[c.id] ?: CapState(CapStatus.Checking)
                        StatusRow(title = c.title, summary = summaryOf(c, st, allView), status = st.status, optional = c.optional, onClick = { act(c) })
                    }
                }
            }
        }

        item(key = "h-shell") { SectionLabel("Root and Shizuku") }
        item(key = "g-shell") {
            Group {
                val rs = Capabilities.rootState()
                GroupRow(
                    title = "Root",
                    subtitle = listOfNotNull(rs.detail, if (allView || root != RootStatus.Granted) RootAccess.HELP else null).joinToString("\n"),
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusText(rs.status, optional = true)
                            when (root) {
                                RootStatus.Checking -> Unit
                                RootStatus.Granted, RootStatus.NotDetected -> CompactButton(
                                    "Check again",
                                    { scope.launch { RootMonitor.checkAgain(context); tick++ } },
                                    primary = false,
                                )
                                else -> CompactButton("Authorize", { scope.launch { RootMonitor.checkAgain(context); tick++ } })
                            }
                        }
                    },
                )
                if (allView || shizukuInstalled || shizuku == ShizukuState.Ready || shizuku == ShizukuState.NoPermission) {
                    val st = when (shizuku) {
                        null -> CapState(CapStatus.Checking)
                        ShizukuState.Ready -> CapState(CapStatus.Available, "Running and allowed")
                        ShizukuState.NoPermission -> CapState(CapStatus.Missing, "Running; Atom not allowed yet")
                        ShizukuState.TooOld -> CapState(CapStatus.Issue, "Update Shizuku")
                        ShizukuState.NotRunning -> CapState(
                            if (shizukuInstalled) CapStatus.NotEnabled else CapStatus.Unsupported,
                            if (shizukuInstalled) "Installed, not running" else "Not installed",
                        )
                    }
                    StatusRow(
                        title = "Shizuku",
                        optional = true,
                        summary = "ADB-level access without root: toggles, freezing apps, logs, UI-dump fallback. " + (st.detail ?: ""),
                        status = st.status,
                        onClick = {
                            when (shizuku) {
                                ShizukuState.NoPermission -> scope.launch { Shells.requestShizuku(); Shells.detect(context); tick++ }
                                ShizukuState.NotRunning -> if (shizukuInstalled) {
                                    context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let(context::open)
                                }
                                else -> Unit
                            }
                        },
                    )
                }
            }
            Text(
                "Losing a permission never resets your settings; features that need it pause and say why.",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

private fun summaryOf(c: Capability, st: CapState, allView: Boolean): String = buildString {
    append(c.purpose)
    st.detail?.let { append("\n").append(it) }
    if (allView) append("\n").append(c.requirement)
    if (c.optional) append("\nOptional")
}

/** Arrow row with the coloured status label at the end; tapping it acts. */
@Composable
private fun StatusRow(title: String, summary: String?, status: CapStatus, onClick: () -> Unit, optional: Boolean = false) {
    GroupRow(
        title = title,
        subtitle = summary,
        onClick = onClick,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusText(status, optional)
                RowChevron()
            }
        },
    )
}

@Composable
private fun StatusText(status: CapStatus, optional: Boolean = false) {
    Text(status.label, style = MiuixTheme.textStyles.body2, color = statusColor(status, optional), maxLines = 1)
}
