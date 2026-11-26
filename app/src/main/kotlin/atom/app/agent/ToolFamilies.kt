package atom.app.agent

import atom.automation.CapabilityTools
import atom.automation.ToolFamily
import atom.automation.ToolOrigin
import atom.core.tool.Tool

/** Where the app's tools come from and which capability family each falls in; shared by runs and automation steps. */
internal object ToolFamilies {
    /** The origin of the tools a feature module contributes. */
    fun originOf(featureId: String): ToolOrigin = when (featureId) {
        "device", "context", "vdisplay" -> ToolOrigin.Device
        "linux" -> ToolOrigin.Linux
        "web" -> ToolOrigin.Web
        "extend" -> ToolOrigin.Extend
        else -> ToolOrigin.Other
    }

    fun familyOf(tool: Tool, origin: ToolOrigin): ToolFamily =
        CapabilityTools.classify(tool.spec.name, tool.spec.description, origin, tool.risk, tool.usesPhoneUi)
}
