package atom.app.agent

import atom.automation.CapabilityTools
import atom.automation.ToolFamily
import atom.automation.ToolGroups
import atom.automation.ToolOrigin
import atom.core.tool.Tool
import atom.core.tool.ToolLoadout

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

    /**
     * Features whose prompt hint is about tools a run loads on demand. Their hint goes out once with the first group
     * of their tools the model loads, not with every request. The rest (memory, skills, characters) stay in the
     * system prompt.
     */
    val DEFERRED_HINTS = setOf("web", "device", "context", "vdisplay", "automation", "linux")

    /**
     * The run's tools as a core set plus on-demand groups ([ToolGroups]). [tools] is the final, filtered list:
     * switches and capabilities have already been applied. [featureOf] maps a tool name to the feature that gave it,
     * [featureNotes] holds the deferred features' hints, and [toolNotes] notes keyed by tool name.
     */
    fun loadout(
        tools: List<Tool>,
        featureOf: Map<String, String>,
        featureNotes: Map<String, String>,
        toolNotes: Map<String, String>,
    ): ToolLoadout {
        val groupsOf = { t: Tool -> ToolGroups.groupsOf(t.spec.name, t.spec.description) }
        val notes = LinkedHashMap<String, MutableList<String>>()
        for (t in tools) for (g in groupsOf(t)) {
            val list = notes.getOrPut(g) { mutableListOf() }
            featureOf[t.spec.name]?.let(featureNotes::get)?.let { if (it !in list) list += it }
            toolNotes[t.spec.name]?.let { if (it !in list) list += it }
        }
        val summaries = tools.flatMap(groupsOf).toSet().associateWith { ToolGroups.summary(it).orEmpty() }
        return ToolLoadout(tools, groupsOf, summaries, notes)
    }

    /** Label of a tool's group for the request-size log: "core" for the always-sent set. */
    fun groupLabel(name: String, description: String): String =
        if (name == ToolLoadout.LOADER) "loader" else ToolGroups.groupsOf(name, description).firstOrNull() ?: "core"
}
