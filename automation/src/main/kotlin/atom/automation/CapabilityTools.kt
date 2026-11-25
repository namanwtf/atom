package atom.automation

import atom.core.tool.Risk

/** Which family a tool belongs to, as far as a task's [Capability] set is concerned. */
enum class ToolFamily {
    Web,
    DeviceRead,
    DeviceControl,
    Terminal,
    Mcp,
    AppUi,

    /** The run's own helpers: memory, skills, subagents, history search, giving phone control back. */
    Assistant,
}

/** Where the app gathered a tool from. Lets the classifier tell a web "read" from a device "read". */
enum class ToolOrigin { Device, Linux, Web, Extend, Other }

/**
 * Maps a scheduled task's (or an automation agent step's) capabilities to the tools its run may see. Applied to
 * the real tool set before the run starts, so a tool outside the allowed families is not offered to the model at
 * all. An empty capability set is "Chat only": no tools whatsoever.
 */
object CapabilityTools {
    /** Families a capability set opens. The assistant helpers come with any non-empty set. */
    fun families(capabilities: Set<Capability>): Set<ToolFamily> {
        if (capabilities.isEmpty()) return emptySet()
        val out = mutableSetOf(ToolFamily.Assistant)
        capabilities.forEach { c ->
            out += when (c) {
                Capability.Web -> ToolFamily.Web
                Capability.DeviceRead -> ToolFamily.DeviceRead
                Capability.DeviceControl -> ToolFamily.DeviceControl
                Capability.Terminal -> ToolFamily.Terminal
                Capability.Mcp -> ToolFamily.Mcp
                Capability.AppUi -> ToolFamily.AppUi
            }
        }
        return out
    }

    fun allows(capabilities: Set<Capability>, family: ToolFamily): Boolean = family in families(capabilities)

    /**
     * The family of one tool. [description] is only used to recognise MCP tools ("[MCP · server] ..."); tools that
     * drive the phone UI are always [ToolFamily.AppUi], whatever module they come from.
     */
    fun classify(name: String, description: String, origin: ToolOrigin, risk: Risk, usesPhoneUi: Boolean): ToolFamily {
        if (usesPhoneUi) return ToolFamily.AppUi
        if (description.startsWith(MCP_MARK)) return ToolFamily.Mcp
        if (name in ASSISTANT_NAMES) return ToolFamily.Assistant
        if (isTerminal(name)) return ToolFamily.Terminal
        return when (origin) {
            ToolOrigin.Linux -> ToolFamily.Terminal
            ToolOrigin.Web -> ToolFamily.Web
            ToolOrigin.Extend -> if (name in NETWORK_EXTEND) ToolFamily.Web else ToolFamily.Assistant
            ToolOrigin.Device, ToolOrigin.Other -> if (risk == Risk.Read) ToolFamily.DeviceRead else ToolFamily.DeviceControl
        }
    }

    /** Keeps the items whose family [capabilities] allows. */
    fun <T> filter(items: List<T>, capabilities: Set<Capability>, familyOf: (T) -> ToolFamily): List<T> {
        val open = families(capabilities)
        if (open.isEmpty()) return emptyList()
        return items.filter { familyOf(it) in open }
    }

    private fun isTerminal(name: String): Boolean =
        name == "shell" || name == "view_image" || TERMINAL_PREFIXES.any { name.startsWith(it) }

    private const val MCP_MARK = "[MCP"
    private val TERMINAL_PREFIXES = listOf("terminal_", "file_", "linux_")
    private val ASSISTANT_NAMES = setOf("history_search", "search_history", "release_phone_control")

    /** Extend tools that reach the network. */
    private val NETWORK_EXTEND = setOf("skill_install_github")
}
