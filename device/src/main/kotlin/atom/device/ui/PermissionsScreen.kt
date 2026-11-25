package atom.device.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import atom.device.caps.Perms
import atom.device.root.RootAccess
import atom.device.root.RootStatus
import atom.ui.components.AtomCard
import atom.ui.components.CompactButton
import atom.ui.components.Group
import atom.ui.components.GroupDivider
import atom.ui.components.LargeTitleScreen
import atom.ui.components.Pill
import atom.ui.components.SectionLabel
import atom.ui.components.SegmentedControl
import atom.ui.components.StatusLine
import atom.ui.feature.FeatureNav
import atom.ui.theme.extras
import kotlinx.coroutines.launch

@Composable
internal fun statusColor(s: CapStatus): Color = when (s) {
    CapStatus.Available -> MaterialTheme.extras.success
    CapStatus.Issue, CapStatus.Checking -> MaterialTheme.extras.warning
    CapStatus.Missing, CapStatus.NotEnabled -> MaterialTheme.colorScheme.error
    CapStatus.Unsupported -> MaterialTheme.colorScheme.onSurfaceVariant
}

internal fun Context.open(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        startActivity(Perms.appDetails(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * Permissions & status. Reading this page never triggers a prompt: states are re-read on resume, and only the row
 * buttons (Grant / Go to settings / Authorize) ask Android for anything.
 */
@Composable
fun PermissionsScreen(nav: FeatureNav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var allView by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(setOf<String>()) }
    var pendingCap by remember { mutableStateOf<String?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val cap = pendingCap
        if (cap != null && result.values.any { !it }) denied = denied + cap
        pendingCap = null
        tick++
    }
    val root by RootAccess.status.collectAsState()
    val shizuku by produceState(initialValue = null as ShizukuState?, tick) { value = Shells.shizukuState() }
    val shizukuInstalled = remember(tick) {
        runCatching { context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0); true }.getOrDefault(false)
    }

    val caps = remember(tick) { Capabilities.all }
    val states = remember(tick, root) { caps.associate { it.id to it.state(context) } }
    val attention = remember(tick, root) { Capabilities.attentionCount(context) }

    fun act(c: Capability) {
        when (val a = c.action(context)) {
            is CapAction.Request -> if (c.id in denied) {
                context.open(Perms.appDetails(context))
            } else {
                pendingCap = c.id
                launcher.launch(a.permissions.toTypedArray())
            }
            is CapAction.OpenSettings -> context.open(a.intent(context))
            null -> Unit
        }
    }

    LargeTitleScreen(title = "Permissions", onBack = nav::back) {
        item {
            AtomCard {
                StatusLine(
                    if (attention == 0) MaterialTheme.extras.success else MaterialTheme.extras.warning,
                    if (attention == 0) "All good" else "$attention item${if (attention == 1) "" else "s"} need attention",
                )
                Text(
                    "Losing a permission never resets your settings; features that need it pause and say why.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(14.dp))
            SegmentedControl(
                options = listOf("Current device" to null, "All capabilities" to null),
                selected = if (allView) 1 else 0,
                onSelect = { allView = it == 1 },
            )
        }

        item {
            SectionLabel("Root and Shizuku")
            Group {
                val rs = Capabilities.rootState()
                CapRowLayout(
                    title = "Root",
                    subtitle = rs.detail ?: "",
                    state = rs,
                ) {
                    when (root) {
                        RootStatus.Checking -> Unit
                        RootStatus.Granted, RootStatus.NotDetected -> CompactButton("Check again", { scope.launch { RootAccess.authorize(context); Shells.detect(context); tick++ } }, primary = false)
                        else -> CompactButton("Authorize", { scope.launch { RootAccess.authorize(context); Shells.detect(context); tick++ } })
                    }
                }
                if (allView || root != RootStatus.Granted) {
                    Text(
                        RootAccess.HELP,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 14.dp),
                    )
                }
                if (allView || shizukuInstalled || shizuku == ShizukuState.Ready || shizuku == ShizukuState.NoPermission) {
                    GroupDivider(18.dp)
                    val st = when (shizuku) {
                        null -> CapState(CapStatus.Checking)
                        ShizukuState.Ready -> CapState(CapStatus.Available, "Running and allowed")
                        ShizukuState.NoPermission -> CapState(CapStatus.Missing, "Running; Atom not allowed yet")
                        ShizukuState.TooOld -> CapState(CapStatus.Issue, "Update Shizuku")
                        ShizukuState.NotRunning -> CapState(if (shizukuInstalled) CapStatus.NotEnabled else CapStatus.Unsupported, if (shizukuInstalled) "Installed, not running" else "Not installed")
                    }
                    CapRowLayout("Shizuku", "ADB-level access without root: toggles, freezing apps, logs, UI-dump fallback. " + (st.detail ?: ""), st) {
                        when (shizuku) {
                            ShizukuState.NoPermission -> CompactButton("Authorize", { scope.launch { Shells.requestShizuku(); Shells.detect(context); tick++ } })
                            ShizukuState.NotRunning -> if (shizukuInstalled) {
                                CompactButton("Open Shizuku", {
                                    context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let(context::open)
                                }, primary = false)
                            }
                            else -> Unit
                        }
                    }
                }
            }
        }

        val groups = caps.groupBy { it.group }.toSortedMap(compareBy { it.ordinal })
        for ((group, list) in groups) {
            val shown = list.filter { allView || it.applicable(context) }
            if (shown.isEmpty()) continue
            item(key = group.name) {
                SectionLabel(group.title)
                Group {
                    shown.forEachIndexed { i, c ->
                        if (i > 0) GroupDivider(18.dp)
                        val st = states[c.id] ?: CapState(CapStatus.Checking)
                        val sub = buildString {
                            append(c.purpose)
                            st.detail?.let { append("\n").append(it) }
                            if (allView) append("\n").append(c.requirement)
                            if (c.optional) append("\nOptional")
                        }
                        CapRowLayout(c.title, sub, st) {
                            val a = c.action(context)
                            if (a != null && st.status != CapStatus.Unsupported) {
                                val label = if (a is CapAction.Request && c.id in denied) "Go to settings" else a.label
                                CompactButton(label, { act(c) }, primary = st.status != CapStatus.Issue)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CapRowLayout(title: String, subtitle: String, state: CapState, button: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Pill(state.status.label, MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = statusColor(state.status))
        }
        if (subtitle.isNotBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row { button() }
    }
}
