package atom.device

import android.content.Context
import atom.core.tool.Tool
import atom.device.screen.UiController
import atom.device.switches.ToolSwitch
import atom.device.switches.ToolSwitches
import atom.device.switches.gated
import atom.device.tools.ScreenExtraTools
import atom.device.tools.SystemTools
import atom.device.tools.UiTools

object DeviceTools {
    /**
     * The core phone-control tools: screen reading and gestures (accessibility, with root/Shizuku UI-dump fallbacks),
     * keys, panels, waiting, apps, links, clipboard, device status and the shell. Every tool re-checks its Tool list
     * switch on each call. Controls, god-mode and undo come from [atom.device.feature.DeviceFeature.tools].
     */
    fun create(context: Context, shell: ShellExecutor): List<Tool> {
        UiController.shell = shell
        Accessibility.remember(context)
        ToolSwitches.init(context)
        val system = SystemTools(context, shell).all()
        val shellTool = system.filter { it.spec.name == "shell" }.map { it.gated(ToolSwitch.TerminalFiles) }
        val rest = system.filter { it.spec.name != "shell" }
        return gate(UiTools(context).all() + ScreenExtraTools(context).all() + rest) + shellTool
    }

    private fun gate(tools: List<Tool>): List<Tool> = tools.map { it.gated(ToolSwitch.DeviceDirect) }

    /**
     * The same tools as [create], for listing only (names and gates, e.g. for the Tool list): no shell is set up
     * or probed, and running one fails.
     */
    fun listing(context: Context): List<Tool> {
        val system = SystemTools(context, InertShell).all()
        val shellTool = system.filter { it.spec.name == "shell" }.map { it.gated(ToolSwitch.TerminalFiles) }
        val rest = system.filter { it.spec.name != "shell" }
        return gate(UiTools(context).all() + ScreenExtraTools(context).all() + rest) + shellTool
    }

    private object InertShell : ShellExecutor {
        override val tier: ShellTier = ShellTier.App
        override suspend fun run(command: String, timeoutMs: Long): ShellResult = ShellResult(1, "", "Listing only")
    }
}
