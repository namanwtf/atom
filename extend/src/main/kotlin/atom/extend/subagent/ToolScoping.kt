package atom.extend.subagent

import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.extend.ToolCategory

/** Which of the parent's tools a subagent gets. */
object ToolScoping {
    /** Tools no subagent ever gets: nesting, self-modification of the app, and the parent's phone lease. */
    val INTERNAL = setOf(
        "spawn_subagent", "spawn_subagents_parallel", "subagent_save", "subagent_delete",
        "skill_install_github", "skill_write", "memory_clear", "release_phone_control",
    )

    private val TERMINAL = Regex("(^|_)(shell|terminal|linux|bash|exec|command|proot|termux)(_|$)")
    private val FILE = Regex("(^|_)(file|files|dir|directory|folder|workspace|path)(_|$)")
    private val WEB = Regex("(^|_)(web|browser|browse|fetch|http|url|page|search)(_|$)")

    private val CATEGORY_NAMES = mapOf(
        "read" to ToolCategory.Read,
        "web" to ToolCategory.Web,
        "file" to ToolCategory.File,
        "files" to ToolCategory.File,
        "terminal" to ToolCategory.Terminal,
        "mcp" to ToolCategory.Mcp,
        "memory" to ToolCategory.Memory,
        "device" to ToolCategory.Device,
        "phone" to ToolCategory.PhoneUi,
    )

    val categoryNames: Set<String> get() = CATEGORY_NAMES.keys

    fun categories(tool: Tool, override: ToolCategory? = null): Set<ToolCategory> {
        val name = tool.spec.name
        if (name in INTERNAL) return setOf(ToolCategory.Internal)
        if (override != null) return setOf(override) + if (tool.risk == Risk.Read && override != ToolCategory.PhoneUi) setOf(ToolCategory.Read) else emptySet()
        if (tool.usesPhoneUi) return setOf(ToolCategory.PhoneUi)
        val out = mutableSetOf<ToolCategory>()
        when {
            name.startsWith("mcp_") -> out += ToolCategory.Mcp
            name.startsWith("memory_") -> out += ToolCategory.Memory
            TERMINAL.containsMatchIn(name) -> out += ToolCategory.Terminal
            FILE.containsMatchIn(name) -> out += ToolCategory.File
            WEB.containsMatchIn(name) -> out += ToolCategory.Web
        }
        if (tool.risk == Risk.Read && !name.startsWith("mcp_")) out += ToolCategory.Read
        if (out.isEmpty()) out += ToolCategory.Device
        return out
    }

    /**
     * The subset of [parent] allowed by [allowed] (category names or exact tool names). Phone-UI tools need an
     * explicit "phone" entry or their exact name; [readOnly] keeps only observing tools.
     */
    fun select(
        parent: List<Tool>,
        allowed: List<String>,
        readOnly: Boolean,
        override: (Tool) -> ToolCategory? = { null },
    ): List<Tool> {
        val wanted = allowed.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val cats = wanted.mapNotNull { CATEGORY_NAMES[it] }.toSet()
        val names = wanted.filter { it !in CATEGORY_NAMES }.toSet()
        return parent.filter { tool ->
            val c = categories(tool, override(tool))
            when {
                ToolCategory.Internal in c -> false
                readOnly && tool.risk != Risk.Read -> false
                tool.spec.name.lowercase() in names -> true
                ToolCategory.PhoneUi in c -> ToolCategory.PhoneUi in cats
                else -> c.any { it in cats }
            }
        }.distinctBy { it.spec.name }
    }
}
