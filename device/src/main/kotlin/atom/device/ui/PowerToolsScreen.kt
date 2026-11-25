package atom.device.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import atom.core.tool.Tool
import atom.core.tool.ToolContext
import atom.device.ShellTier
import atom.device.Shells
import atom.device.godmode.Firewall
import atom.device.godmode.GodModeTools
import atom.device.godmode.LocationSpoof
import atom.device.godmode.NetScope
import atom.ui.components.AtomCard
import atom.ui.components.CompactButton
import atom.ui.components.Group
import atom.ui.components.GroupDivider
import atom.ui.components.GroupRow
import atom.ui.components.LargeTitleScreen
import atom.ui.components.SectionLabel
import atom.ui.components.SegmentedControl
import atom.ui.feature.FeatureNav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A tap on this screen is the user's own approval, so tool previews are accepted. */
private object ScreenApproval : ToolContext {
    override val taskId: String = "power-tools-screen"
    override suspend fun confirm(action: String): Boolean = true
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PowerToolsScreen(nav: FeatureNav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val god = remember { GodModeTools(context) }
    val tools = remember { (god.privileged() + god.rootless()).associateBy { it.spec.name } }
    val firewall = remember { Firewall(context) }
    var tick by remember { mutableIntStateOf(0) }
    var output by remember { mutableStateOf<Pair<String, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val tier by produceState(initialValue = null as ShellTier?) { value = withContext(Dispatchers.IO) { Shells.detect(context).tier } }
    val spoof by LocationSpoof.state.collectAsState()
    val rules = remember(tick) { firewall.rules() }

    fun run(section: String, tool: String, vararg args: Pair<String, Any>) {
        val t: Tool = tools[tool] ?: return
        busy = true
        scope.launch {
            val json = JsonObject(args.associate { (k, v) ->
                k to when (v) {
                    is Number -> JsonPrimitive(v)
                    is Boolean -> JsonPrimitive(v)
                    else -> JsonPrimitive(v.toString())
                }
            })
            val r = withContext(Dispatchers.IO) {
                runCatching { t.run(json, ScreenApproval) }.getOrElse { atom.core.tool.ToolResult.error(it.message ?: "Failed") }
            }
            output = section to r.text
            busy = false
            tick++
        }
    }

    @Composable
    fun Output(section: String) {
        output?.takeIf { it.first == section }?.let {
            Text(it.second, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }

    val root = tier == ShellTier.Root
    val privileged = tier == ShellTier.Root || tier == ShellTier.Shizuku

    LargeTitleScreen(title = "Power tools", onBack = nav::back) {
        item {
            AtomCard {
                Text(
                    when (tier) {
                        null -> "Checking access…"
                        ShellTier.Root -> "Root is available: every tool here works."
                        ShellTier.Shizuku -> "Shizuku is available: background-data limits, refresh rate and battery drain work. The firewall and charge limit need root."
                        ShellTier.App -> "No root or Shizuku: only GPS spoofing works here. See Permissions."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Changes made here are recorded in Change history with one-tap undo.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionLabel("App firewall")
            var app by remember { mutableStateOf("") }
            var scopeIndex by remember { mutableIntStateOf(1) }
            val scopes = listOf("all" to "All", "mobile" to "Mobile", "wifi" to "Wi-Fi", "background" to "Background")
            AtomCard {
                Text(
                    "Kernel rules per app: zero battery cost once applied, kept across restarts. Background limits mobile data only while the app is in the background.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = app,
                    onValueChange = { app = it },
                    label = { Text("App name or package") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary),
                )
                SegmentedControl(scopes.map { it.second to null }, scopeIndex, { scopeIndex = it })
                val needRoot = scopes[scopeIndex].first != "background"
                CompactButton(
                    "Block",
                    { run("fw", "app_firewall", "action" to "block", "app" to app.trim(), "network" to scopes[scopeIndex].first) },
                    enabled = app.isNotBlank() && !busy && (if (needRoot) root else privileged),
                )
                Output("fw")
            }
            if (rules.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Group {
                    rules.forEachIndexed { i, r ->
                        if (i > 0) GroupDivider(18.dp)
                        GroupRow(
                            title = r.label,
                            subtitle = "${r.scope.label} blocked",
                            trailing = {
                                CompactButton("Unblock", {
                                    run(
                                        "fw", "app_firewall", "action" to "unblock", "app" to r.packageName,
                                        "network" to when (r.scope) { NetScope.All -> "all"; NetScope.Mobile -> "mobile"; NetScope.Wifi -> "wifi"; NetScope.Background -> "background" },
                                    )
                                }, primary = false, enabled = !busy)
                            },
                        )
                    }
                }
            }
        }

        item {
            SectionLabel("Battery")
            AtomCard {
                Text("Charge limit: the kernel stops charging at the level you pick (root, supported phones only).", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactButton("Check", { run("charge", "charge_limit", "action" to "status") }, primary = false, enabled = root && !busy)
                    listOf(80, 85, 90).forEach { p ->
                        CompactButton("$p%", { run("charge", "charge_limit", "action" to "set", "percent" to p) }, primary = false, enabled = root && !busy)
                    }
                    CompactButton("Off", { run("charge", "charge_limit", "action" to "off") }, primary = false, enabled = root && !busy)
                }
                Output("charge")
                Spacer(Modifier.height(4.dp))
                Text("What drained the battery since the last full charge.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CompactButton("Analyze", { run("drain", "battery_drain", "top" to 10) }, primary = false, enabled = privileged && !busy)
                Output("drain")
            }
        }

        item {
            SectionLabel("Display")
            AtomCard {
                Text("Lock the refresh rate (60 Hz saves battery), or give it back to the system.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val rates = remember {
                    val dm = context.getSystemService(android.hardware.display.DisplayManager::class.java)
                    val d = dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                    d?.supportedModes?.filter { it.physicalWidth == d.mode.physicalWidth }?.map { it.refreshRate.toInt() }?.distinct()?.sorted().orEmpty()
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactButton("Status", { run("rate", "refresh_rate", "action" to "status") }, primary = false, enabled = !busy)
                    rates.forEach { hz -> CompactButton("$hz Hz", { run("rate", "refresh_rate", "action" to "lock", "hz" to hz) }, primary = false, enabled = privileged && !busy) }
                    CompactButton("Auto", { run("rate", "refresh_rate", "action" to "auto") }, primary = false, enabled = privileged && !busy)
                }
                Output("rate")
            }
        }

        item {
            SectionLabel("GPS spoofing")
            var lat by remember { mutableStateOf("") }
            var lon by remember { mutableStateOf("") }
            AtomCard {
                Text(LocationSpoof.WARNING, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (spoof.active) {
                    Text(String.format(java.util.Locale.US, "On: %.5f, %.5f", spoof.lat, spoof.lon), style = MaterialTheme.typography.bodyLarge)
                    CompactButton("Stop spoofing", { run("spoof", "spoof_location", "action" to "stop") }, enabled = !busy)
                } else {
                    OutlinedTextField(lat, { lat = it }, label = { Text("Latitude") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(lon, { lon = it }, label = { Text("Longitude") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    val la = lat.trim().toDoubleOrNull()
                    val lo = lon.trim().toDoubleOrNull()
                    CompactButton("Start", { run("spoof", "spoof_location", "action" to "start", "latitude" to la!!, "longitude" to lo!!) }, enabled = la != null && lo != null && !busy)
                }
                Output("spoof")
            }
        }
    }
}
