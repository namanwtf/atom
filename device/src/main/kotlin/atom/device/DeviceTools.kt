package atom.device

import android.content.Context
import atom.core.tool.Tool
import atom.device.screen.UiController
import atom.device.tools.SystemTools
import atom.device.tools.UiTools

object DeviceTools {
    /**
     * Every phone-control tool. UI tools need [AtomAccessibilityService] connected and say so otherwise;
     * the shell tool's description states [shell]'s tier, so re-create the list if the tier changes.
     */
    fun create(context: Context, shell: ShellExecutor): List<Tool> {
        UiController.shell = shell
        Accessibility.remember(context) // lets settingsIntent() highlight Atom's entry
        return UiTools.all() + SystemTools(context, shell).all()
    }
}
