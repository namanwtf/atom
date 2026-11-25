package atom.device.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import atom.device.caps.Needs
import atom.device.caps.ToolCatalog
import atom.device.caps.ToolRow
import atom.device.caps.ToolSection
import atom.device.caps.ToolSections
import atom.device.feature.DeviceFeature
import atom.device.switches.ToolGates
import atom.device.switches.ToolSwitches
import atom.ui.components.ArrowRow
import atom.ui.components.AtomDialog
import atom.ui.components.AtomSwitch
import atom.ui.components.AtomTextButton
import atom.ui.components.CompactButton
import atom.ui.components.Group
import atom.ui.components.GroupRow
import atom.ui.components.LargeTitleScreen
import atom.ui.components.ListGap
import atom.ui.feature.FeatureNav
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val ROUTE_SYSTEM = "vdisplay/system"
private const val ROUTE_WEB = "web/search"
private const val ROUTE_IMAGES = "web/images"
private const val ROUTE_BROWSER = "web/browser"

/**
 * Tool list: one switch per tool, grouped in the classic order, with Enable all / Disable all per group and an
 * "All n/m" chip strip to show one group. "Current device" leaves out tools this phone cannot run right now.
 * Switches take effect on the next tool call, even in the middle of a task.
 */
@Composable
fun ToolListScreen(nav: FeatureNav) {
    val context = LocalContext.current
    remember { ToolSwitches.init(context) }
    val disabled by ToolSwitches.disabledTools.collectAsState()
    var allView by rememberSaveable { mutableStateOf(false) }
    var chip by rememberSaveable { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<ToolRow?>(null) }
    val groups = remember {
        // Building this module's tools records their gates, so newer tools without a card still get a switch.
        runCatching { atom.device.DeviceTools.listing(context) + DeviceFeature.tools(context) }
        val cards = ToolCatalog.all
        cards + listOfNotNull(ToolCatalog.uncardedGroup(cards, ToolGates.snapshot()))
    }
    val isOn: (String) -> Boolean = { it !in disabled }
    // "Current device" leaves out what this phone cannot gain (root or Shizuku missing); tools that only need a
    // permission or the accessibility service stay, with their requirement line.
    val sections = ToolSections.build(groups, isOn) { allView || it.needs.met(context) || !ToolSections.needsShell(it.needs) }
    val selected = chip?.let { name -> ToolSection.entries.firstOrNull { it.name == name } }?.takeIf { s -> sections.any { it.section == s } }
    val shown = ToolSections.filter(sections, selected)
    val (on, total) = ToolSections.totals(sections)

    LargeTitleScreen(title = "Tools", onBack = nav::back) {
        item(key = "tabs") {
            TabRow(
                tabs = listOf("Current device", "All capabilities"),
                selectedTabIndex = if (allView) 1 else 0,
                onTabSelected = { allView = it == 1 },
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
        item(key = "chips") {
            LazyRow(
                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "all") { FilterChip("All $on/$total", selected == null) { chip = null } }
                items(sections, key = { it.section.name }) { s ->
                    FilterChip("${s.section.title} ${s.enabled}/${s.total}", selected == s.section) {
                        chip = if (selected == s.section) null else s.section.name
                    }
                }
            }
        }
        if (selected == null) {
            item(key = "system") {
                Group {
                    ArrowRow(
                        title = "Root and system enhancements",
                        summary = "Root authorization and framework status",
                        onClick = { nav.open(ROUTE_SYSTEM) },
                    )
                }
            }
        }
        for (s in shown) {
            item(key = "h-${s.section.name}") {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${s.section.title} · ${s.enabled}/${s.total}",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        modifier = Modifier.weight(1f),
                    )
                    val allOn = s.enabled == s.total
                    CompactButton(
                        if (allOn) "Disable all" else "Enable all",
                        onClick = { ToolSwitches.setTools(s.rows.map { it.tool }, on = !allOn) },
                        primary = false,
                    )
                }
            }
            item(key = "g-${s.section.name}") {
                Group {
                    s.rows.forEach { row ->
                        val off = ToolCatalog.featureOff(row.group.id)
                        if (off != null) {
                            FeatureOffRow(row, off, onOpen = { nav.open(if (row.group.id == "image") ROUTE_IMAGES else ROUTE_WEB) }, onInfo = { info = row })
                        } else {
                            ToolSwitchRow(row, isOn(row.tool), met = row.group.needs.met(context), onInfo = { info = row })
                        }
                    }
                }
            }
        }
        item(key = "note") {
            Text(
                "Switches take effect on the next tool call, even in the middle of a task. Switching off web search also stops provider-hosted search.",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            ListGap()
        }
    }

    val open = info
    AtomDialog(show = open != null, onDismiss = { info = null }, title = open?.title) {
        if (open != null) {
            Text(
                open.group.description,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            requirement(open.group.needs, met = true)?.let {
                Text(
                    it,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            val link = actionLink(open)
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (link != null) {
                    AtomTextButton(link.first, onClick = {
                        info = null
                        nav.open(link.second)
                    }, modifier = Modifier.weight(1f))
                }
                AtomTextButton("Got it", onClick = { info = null }, modifier = Modifier.weight(1f), primary = true)
            }
        }
    }
}

@Composable
private fun ToolSwitchRow(row: ToolRow, on: Boolean, met: Boolean, onInfo: () -> Unit) {
    GroupRow(
        title = row.title,
        subtitle = requirement(row.group.needs, met),
        onClick = { ToolSwitches.setTool(row.tool, !on) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onInfo) {
                    Icon(Icons.Outlined.Info, "About ${row.title}", Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantActions)
                }
                AtomSwitch(on, { ToolSwitches.setTool(row.tool, it) })
            }
        },
    )
}

/** A tool whose feature is off in its own page: shown off, tapping opens that page. */
@Composable
private fun FeatureOffRow(row: ToolRow, reason: String, onOpen: () -> Unit, onInfo: () -> Unit) {
    GroupRow(
        title = row.title,
        subtitle = reason,
        onClick = onOpen,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onInfo) {
                    Icon(Icons.Outlined.Info, "About ${row.title}", Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantActions)
                }
                AtomSwitch(false, null, enabled = false)
            }
        },
    )
}

/** The line under a tool that cannot run yet (or, with [met] = true, always: for the description dialog). */
private fun requirement(needs: Needs, met: Boolean): String? {
    if (met && needs != Needs.Accessibility && needs != Needs.ShizukuOrRoot && needs != Needs.Root) return null
    return when (needs) {
        Needs.Accessibility -> if (met) "Uses the accessibility service" else "Available after enabling accessibility"
        Needs.ShizukuOrRoot -> "Shizuku or root required"
        Needs.Root -> "Root access required"
        Needs.Permission -> "Uses the permissions on the Permissions page"
        Needs.Nothing -> null
    }
}

/** The settings page a tool links to from its description, as label to route. */
private fun actionLink(row: ToolRow): Pair<String, String>? = when (row.group.id) {
    "web" -> "Configure service" to ROUTE_WEB
    "image" -> "Configure image generation" to ROUTE_IMAGES
    "browser" -> "Open browser" to ROUTE_BROWSER
    else -> if (row.group.needs == Needs.Nothing) null else "Manage permissions" to DeviceFeature.ROUTE_PERMISSIONS
}

/** Choice chip: accent tint when selected, quiet grey otherwise; colours ease over 160 ms. */
@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = MiuixTheme.colorScheme
    val bg by animateColorAsState(if (selected) c.primary.copy(alpha = 0.18f) else c.surfaceContainer, tween(160), label = "chipBg")
    val fg by animateColorAsState(if (selected) c.primary else c.onSurfaceVariantSummary, tween(160), label = "chipFg")
    Text(
        label,
        style = MiuixTheme.textStyles.footnote1,
        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        color = fg,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}
