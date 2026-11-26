package atom.extend

import android.content.Context
import androidx.compose.runtime.Composable
import atom.core.tool.Tool
import atom.extend.mcp.McpServerScreen
import atom.extend.mcp.McpServersScreen
import atom.extend.mcp.McpTools
import atom.extend.memory.MemoryEdit
import atom.extend.memory.MemoryScreen
import atom.extend.memory.MemoryText
import atom.extend.memory.MemoryTools
import atom.extend.skills.SkillTools
import atom.extend.skills.SkillsScreen
import atom.extend.subagent.SubagentEditScreen
import atom.extend.subagent.SubagentRoutes
import atom.extend.subagent.SubagentRunScreen
import atom.extend.subagent.SubagentStore
import atom.extend.subagent.SubagentTools
import atom.extend.subagent.SubagentViewScreen
import atom.extend.subagent.SubagentsScreen
import atom.ui.feature.AtomFeature
import atom.ui.feature.FeatureEntry
import atom.ui.feature.FeatureNav
import atom.ui.feature.FeatureSection
import java.io.File

/** Long-term memory, skills, MCP servers and subagents. All of it is idle until a run or the user needs it. */
object ExtendFeature : AtomFeature {
    const val ROUTE_MEMORY = "extend/memory"
    const val ROUTE_SKILLS = "extend/skills"
    const val ROUTE_MCP = "extend/mcp"
    const val ROUTE_SUBAGENTS = SubagentRoutes.ROOT
    private const val MAX_LISTED_SKILLS = 40

    override val id: String = "extend"

    override fun onAppCreate(context: Context) {
        ExtendRuntime.init(context)
    }

    /** MEMORY.md, for backup and restore. Writes from outside are picked up on the next read. */
    fun memoryFile(context: Context): File {
        ExtendRuntime.init(context)
        return ExtendRuntime.memory.file
    }

    enum class MemoryImport {
        Saved,

        /** Together with the current memory it would be over [MemoryText.MAX_BYTES]; nothing was written. */
        TooLarge,

        /** The memory kept changing underneath; nothing was written. */
        Busy,
    }

    /**
     * Brings memory over from another app (the previous Atom) through the memory store: [text] becomes the whole
     * memory while it is still empty, otherwise it is added at the end under [heading] (see [MemoryText.merge]).
     */
    suspend fun importMemory(context: Context, text: String, heading: String): MemoryImport {
        ExtendRuntime.init(context)
        val store = ExtendRuntime.memory
        // A write from the agent or the editor in between only means trying again on the new content.
        repeat(3) {
            val current = store.current()
            when (store.save(MemoryText.merge(current.text, text, heading), current.hash)) {
                is MemoryEdit.Saved -> return MemoryImport.Saved
                // The store rejects a save for one reason only: the size limit.
                is MemoryEdit.Rejected -> return MemoryImport.TooLarge
                is MemoryEdit.Conflict -> Unit
            }
        }
        return MemoryImport.Busy
    }

    override val entries: List<FeatureEntry>
        get() = listOf(
            FeatureEntry(FeatureSection.Context, R.string.ext_entry_memory, R.string.ext_entry_memory_sub, ExtendIcons.Memory, ROUTE_MEMORY),
            FeatureEntry(FeatureSection.Context, R.string.ext_entry_skills, R.string.ext_entry_skills_sub, ExtendIcons.Skills, ROUTE_SKILLS),
            FeatureEntry(FeatureSection.Context, R.string.ext_entry_mcp, R.string.ext_entry_mcp_sub, ExtendIcons.Mcp, ROUTE_MCP),
            FeatureEntry(FeatureSection.Context, R.string.ext_entry_subagents, R.string.ext_entry_subagents_sub, ExtendIcons.Subagents, ROUTE_SUBAGENTS),
        )

    override fun tools(context: Context): List<Tool> {
        ExtendRuntime.init(context)
        val own = ownTools()
        val subagents = subagentTools(own)
        return own + subagents
    }

    private fun ownTools(): List<Tool> {
        val out = mutableListOf<Tool>()
        val memory = ExtendRuntime.memory
        if (memory.current().enabled) out += MemoryTools.create(memory)
        out += SkillTools.create(ExtendRuntime.skills, ExtendRuntime.installer)
        out += McpTools.create(ExtendRuntime.mcp, out.map { it.spec.name }.toSet())
        return out
    }

    private fun subagentTools(own: List<Tool>): List<Tool> {
        val store = ExtendRuntime.subagents
        if (!store.settings.value.enabled) return emptyList()
        val runner = ExtendRuntime.runner { own } ?: return emptyList()
        return SubagentTools.create(store, runner)
    }

    override fun promptHint(context: Context): String? {
        ExtendRuntime.init(context)
        val parts = listOfNotNull(memoryHint(), skillsHint(), subagentsHint(ExtendRuntime.subagents))
        return parts.joinToString("\n\n").ifEmpty { null }
    }

    private fun memoryHint(): String? {
        val state = ExtendRuntime.memory.current()
        if (!state.enabled) return null
        return MemoryText.promptBlock(state.text, Extend.contextWindow.currentContextWindow())
    }

    private fun skillsHint(): String = buildString {
        val enabled = ExtendRuntime.skills.enabled()
        append("## Skills\n")
        append("Skills are instruction packages for particular tasks. When a request matches one, call skill_read with its ")
        append("name before acting and follow it; open its other files only when it points to them. Scripts in skills never ")
        append("run by themselves: run one only through a terminal tool, which asks the user. Install new skills from GitHub ")
        append("with skill_install_github; create or improve user skills with skill_write.")
        if (enabled.isEmpty()) {
            append("\n(No skills are enabled.)")
        } else {
            enabled.take(MAX_LISTED_SKILLS).forEach { append("\n- ").append(it.name).append(": ").append(it.manifest.oneLine) }
            if (enabled.size > MAX_LISTED_SKILLS) append("\n- … ${enabled.size - MAX_LISTED_SKILLS} more (ask skill_read by name)")
        }
    }

    private fun subagentsHint(store: SubagentStore): String? {
        if (!store.settings.value.enabled || Extend.engines == null) return null
        return buildString {
            append("## Subagents\n")
            append("spawn_subagent hands one self-contained task to a helper with a fresh context and a limited set of your ")
            append("tools and returns its summary; spawn_subagents_parallel runs up to 4 independent ones at once. Use them for ")
            append("broad searches, separable work, or to keep long tool output out of this conversation, not for single quick ")
            append("steps. They cannot see this chat, so state each task completely. Save a reusable one with subagent_save ")
            append("when the user asks.")
            store.all().forEach { d ->
                append("\n- ").append(d.name).append(": ").append(d.description)
                if (d.whenToUse.isNotBlank()) append(" Use for: ").append(d.whenToUse)
            }
            append("\n- general: a temporary helper; pass its instructions.")
        }
    }

    @Composable
    override fun Screen(route: String, nav: FeatureNav) {
        when {
            route == ROUTE_MEMORY -> MemoryScreen(ExtendRuntime.memory, nav)
            route == ROUTE_SKILLS -> SkillsScreen(ExtendRuntime.skills, ExtendRuntime.installer, nav)
            route == ROUTE_MCP -> McpServersScreen(ExtendRuntime.mcp, nav) { "$ROUTE_MCP/$it" }
            route.startsWith("$ROUTE_MCP/") -> McpServerScreen(ExtendRuntime.mcp, route.removePrefix("$ROUTE_MCP/"), nav)
            route == ROUTE_SUBAGENTS -> SubagentsScreen(ExtendRuntime.subagents, nav)
            route == SubagentRoutes.NEW -> SubagentEditScreen(ExtendRuntime.subagents, null, nav)
            route.startsWith(SubagentRoutes.EDIT) -> SubagentEditScreen(ExtendRuntime.subagents, route.removePrefix(SubagentRoutes.EDIT), nav)
            route.startsWith(SubagentRoutes.RUN) -> SubagentRunScreen(route.removePrefix(SubagentRoutes.RUN), nav)
            route.startsWith(SubagentRoutes.VIEW) -> SubagentViewScreen(route.removePrefix(SubagentRoutes.VIEW), nav)
        }
    }
}
