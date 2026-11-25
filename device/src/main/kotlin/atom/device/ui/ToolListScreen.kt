package atom.device.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import atom.device.caps.ToolCatalog
import atom.device.feature.DeviceFeature
import atom.device.switches.ToolSwitch
import atom.device.switches.ToolSwitches
import atom.ui.components.AtomCard
import atom.ui.components.AtomSwitch
import atom.ui.components.CompactButton
import atom.ui.components.Group
import atom.ui.components.GroupDivider
import atom.ui.components.GroupRow
import atom.ui.components.LargeTitleScreen
import atom.ui.components.Pill
import atom.ui.components.SectionLabel
import atom.ui.components.SegmentedControl
import atom.ui.feature.FeatureNav
import atom.ui.theme.extras

/** Master switches (re-checked at every tool call) and a card per tool group with what it needs. */
@Composable
fun ToolListScreen(nav: FeatureNav) {
    val context = LocalContext.current
    remember { ToolSwitches.init(context) }
    val switches by ToolSwitches.state.collectAsState()
    var allView by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    val groups = remember { ToolCatalog.all }

    LargeTitleScreen(title = "Tool list", onBack = nav::back) {
        item {
            SectionLabel("Master switches")
            Group {
                ToolSwitch.entries.forEachIndexed { i, s ->
                    if (i > 0) GroupDivider(18.dp)
                    GroupRow(
                        title = s.title,
                        subtitle = s.summary,
                        onClick = { ToolSwitches.set(s, !(switches[s] ?: s.default)) },
                        trailing = { AtomSwitch(switches[s] ?: s.default, { ToolSwitches.set(s, it) }) },
                    )
                }
            }
            Text(
                "Switches take effect on the next tool call, even in the middle of a task.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 8.dp),
            )
            Spacer(Modifier.height(16.dp))
            SegmentedControl(
                options = listOf("This device" to null, "All capabilities" to null),
                selected = if (allView) 1 else 0,
                onSelect = { allView = it == 1 },
            )
        }
        val shown = groups.filter { allView || it.needs.met(context) }
        for (g in shown) {
            item(key = g.id) {
                Spacer(Modifier.height(12.dp))
                val on = g.switches.all { switches[it] ?: it.default }
                val met = g.needs.met(context)
                AtomCard(Modifier.clickable { expanded = if (g.id in expanded) expanded - g.id else expanded + g.id }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(g.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Pill(
                            when {
                                !on -> "Off"
                                !met -> "Unavailable"
                                else -> "${g.tools.size} tools"
                            },
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = if (on && met) MaterialTheme.extras.success else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(g.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (g.id in expanded || allView) {
                        Text(g.needs.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "Switch: " + g.switches.joinToString(" + ") { it.title },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(g.tools.joinToString(", "), style = MaterialTheme.extras.mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!met) CompactButton("Permissions", { nav.open(DeviceFeature.ROUTE_PERMISSIONS) }, primary = false)
                    }
                }
            }
        }
    }
}
