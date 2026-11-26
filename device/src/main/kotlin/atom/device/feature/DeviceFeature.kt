package atom.device.feature

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import atom.core.tool.Tool
import atom.device.R
import atom.device.ShellTier
import atom.device.Shells
import atom.device.control.ControlRunner
import atom.device.control.ControlTools
import atom.device.control.Controls
import atom.device.godmode.GodModeTools
import atom.device.root.RootAccess
import atom.device.root.RootMonitor
import atom.device.switches.ToolSwitch
import atom.device.switches.ToolSwitches
import atom.device.switches.gated
import atom.device.system.SystemControlTools
import atom.device.ui.ChangesScreen
import atom.device.ui.PermissionsScreen
import atom.device.ui.PowerToolsScreen
import atom.device.ui.ToolListScreen
import atom.device.undo.UndoTools
import atom.ui.components.ScreenIcons
import atom.ui.feature.AtomFeature
import atom.ui.feature.FeatureEntry
import atom.ui.feature.FeatureNav
import atom.ui.feature.FeatureSection

/**
 * Device control beyond the core screen tools ([atom.device.DeviceTools]): system toggles with verified
 * before → after reports, the undo journal, app and settings operations, and the power tools. Shizuku/root tools are
 * listed only when that access is available, so rootless mode never advertises what it cannot do.
 */
object DeviceFeature : AtomFeature {
    override val id = "device"

    const val ROUTE_PERMISSIONS = "device/permissions"
    const val ROUTE_TOOLS = "device/tools"
    const val ROUTE_CHANGES = "device/changes"
    const val ROUTE_POWER = "device/power"

    override fun onAppCreate(context: Context) {
        RootAccess.load(context)
        (context.applicationContext as? android.app.Application)?.let(RootMonitor::install)
        ToolSwitches.init(context)
        Controls.registerAll()
        ControlRunner.installRestorer(context)
        SystemControlTools.installRestorers(context)
        GodModeTools.installRestorers(context)
    }

    override fun tools(context: Context): List<Tool> {
        ToolSwitches.init(context)
        // Read at call time: root granted after the last shell detection counts at once.
        val tier = Shells.currentTier()
        val privileged = tier != ShellTier.App
        val sys = SystemControlTools(context)
        val god = GodModeTools(context)
        val tools = mutableListOf<Tool>()
        tools += ControlTools(context).all().map { it.gated(ToolSwitch.DeviceDirect) }
        tools += UndoTools(context).all().map { it.gated(ToolSwitch.DeviceDirect) }
        tools += sys.rootless().map { it.gated(ToolSwitch.DeviceDirect) }
        tools += god.rootless().map { it.gated(ToolSwitch.SensitiveOps) }
        if (privileged) {
            sys.privileged().forEach { t ->
                tools += when (t.spec.name) {
                    "read_setting", "read_system_logs" -> t.gated(ToolSwitch.DeviceDirect, ToolSwitch.SensitiveRead)
                    else -> t.gated(ToolSwitch.DeviceDirect, ToolSwitch.SensitiveOps)
                }
            }
            god.privileged().forEach { t ->
                if (t.spec.name == "charge_limit" && tier != ShellTier.Root) return@forEach
                tools += if (t.spec.name == "battery_drain") t.gated(ToolSwitch.DeviceDirect) else t.gated(ToolSwitch.SensitiveOps)
            }
        }
        return tools
    }

    override fun promptHint(context: Context): String =
        "# Device changes\nState-changing tools report \"before → after\" and say verified or unconfirmed; repeat that " +
            "wording and never call an unconfirmed change done. Every change is journaled: undo_last_change reverts the " +
            "latest, list_changes shows them. Screen actions report done, failed, or \"submitted, unconfirmed\": on the " +
            "last, read_screen before trying again."

    override val entries: List<FeatureEntry> = listOf(
        FeatureEntry(FeatureSection.Tools, R.string.atom_device_tool_list, R.string.atom_device_tool_list_sub, ScreenIcons.Tune, ROUTE_TOOLS),
        FeatureEntry(FeatureSection.Tools, R.string.atom_device_permissions, R.string.atom_device_permissions_sub, ScreenIcons.Shield, ROUTE_PERMISSIONS),
        FeatureEntry(FeatureSection.System, R.string.atom_device_changes, R.string.atom_device_changes_sub, Icons.Filled.Refresh, ROUTE_CHANGES),
        FeatureEntry(FeatureSection.System, R.string.atom_device_power, R.string.atom_device_power_sub, Icons.Filled.Build, ROUTE_POWER),
    )

    @Composable
    override fun Screen(route: String, nav: FeatureNav) {
        when (route) {
            ROUTE_PERMISSIONS -> PermissionsScreen(nav)
            ROUTE_TOOLS -> ToolListScreen(nav)
            ROUTE_CHANGES -> ChangesScreen(nav)
            ROUTE_POWER -> PowerToolsScreen(nav)
            else -> LaunchedEffect(route) { nav.back() }
        }
    }
}
