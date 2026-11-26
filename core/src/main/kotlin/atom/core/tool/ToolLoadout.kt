package atom.core.tool

import atom.core.llm.ChatMessage
import atom.core.llm.ToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Which of a run's tools the model is offered on the next request. */
interface ToolExposure {
    fun specs(): List<ToolSpec>

    /** Called before a call to [name] runs, so a lazily loaded set can add a tool the model reached for directly. */
    fun onCall(name: String) {}

    /** Every tool, every request: the behaviour without a [ToolLoadout]. */
    class All(private val registry: ToolRegistry) : ToolExposure {
        override fun specs(): List<ToolSpec> = registry.specs
    }
}

/**
 * Sends a small core set of tools with every request and the rest on demand. Each tool belongs to zero or more
 * groups ([groupsOf]; none means "always sent"). A tool in groups is offered once one of its groups is loaded,
 * either by the model calling [LOADER] or by the model calling the tool directly. Loaded groups stay for the rest
 * of the run.
 *
 * Only [tools] can ever be offered: build it after the per-tool switches and capability filters, which stay
 * authoritative. The offered list keeps [tools]' order and the loader's description lists every group, so the
 * request prefix stays identical from step to step (and run to run) until a group is loaded, keeping provider
 * prompt caches warm.
 */
class ToolLoadout(
    tools: List<Tool>,
    groupsOf: (Tool) -> Set<String>,
    /** One line per group for the loader's catalog. */
    private val summaries: Map<String, String> = emptyMap(),
    /** Guidance sent once with the first load of a group (instead of in every system prompt). */
    private val notes: Map<String, List<String>> = emptyMap(),
) : ToolExposure {
    private val tools: List<Tool> = tools.distinctBy { it.spec.name }
    private val groupsByTool: Map<String, Set<String>> = this.tools.associate { it.spec.name to groupsOf(it) }

    /** Group -> its tool names, groups in order of first appearance. */
    private val members: Map<String, List<String>> = LinkedHashMap<String, MutableList<String>>().also { map ->
        this.tools.forEach { t -> groupsByTool.getValue(t.spec.name).forEach { g -> map.getOrPut(g) { mutableListOf() } += t.spec.name } }
    }

    private val loaded = LinkedHashSet<String>()
    private val sentNotes = HashSet<String>()

    /** The loader, or null when every tool is always sent. */
    val loader: Tool? = if (members.isEmpty()) null else Loader()

    /** Everything the run may call: the tools plus the loader. */
    val all: List<Tool> get() = tools + listOfNotNull(loader)

    val groups: Set<String> get() = members.keys

    @Synchronized
    fun loadedGroups(): Set<String> = loaded.toSet()

    @Synchronized
    override fun specs(): List<ToolSpec> = tools.filter { visible(it.spec.name) }.map { it.spec } + listOfNotNull(loader?.spec)

    @Synchronized
    override fun onCall(name: String) {
        if (!visible(name)) groupsByTool[name]?.firstOrNull()?.let { load(listOf(it)) }
    }

    /**
     * Loads the groups of tools already called in [history] (an earlier turn of the same conversation), so the
     * model sees the tools its own history refers to. Their notes went out with that earlier load.
     */
    @Synchronized
    fun preload(history: List<ChatMessage>) {
        for (m in history) for (c in m.toolCalls) {
            val gs = groupsByTool[c.name] ?: continue
            if (gs.isEmpty() || gs.any { it in loaded }) continue
            val g = gs.first()
            loaded += g
            notes[g]?.let { sentNotes += it }
        }
    }

    /** Loads [requested] (group names, or tool names standing for their group) and says what was added. */
    @Synchronized
    fun load(requested: Collection<String>): ToolResult {
        val wanted = requested.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        if (wanted.isEmpty()) return ToolResult.error("Name at least one group: ${members.keys.joinToString()}.")
        val added = LinkedHashSet<String>()
        val already = LinkedHashSet<String>()
        val unknown = ArrayList<String>()
        for (w in wanted) {
            val group = if (w in members) w else groupsByTool[w]?.firstOrNull { it in loaded } ?: groupsByTool[w]?.firstOrNull()
            when {
                group == null -> if (groupsByTool[w]?.isEmpty() == true) already += w else unknown += w
                group in loaded -> already += group
                else -> {
                    loaded += group
                    added += group
                }
            }
        }
        if (added.isEmpty() && unknown.isNotEmpty()) {
            return ToolResult.error("Unknown group ${unknown.joinToString { "'$it'" }}. Groups: ${members.keys.joinToString()}.")
        }
        return ToolResult.ok(
            buildString {
                if (added.isNotEmpty()) {
                    append("Loaded. Use these from your next step:")
                    added.forEach { g -> append("\n- ").append(g).append(": ").append(members.getValue(g).joinToString()) }
                }
                if (already.isNotEmpty()) {
                    if (isNotEmpty()) append('\n')
                    append("Already available: ").append(already.joinToString()).append('.')
                }
                if (unknown.isNotEmpty()) append("\nUnknown, ignored: ").append(unknown.joinToString()).append('.')
                val fresh = added.flatMap { notes[it].orEmpty() }.distinct().filter { sentNotes.add(it) }
                fresh.forEach { append("\n\n").append(it) }
            },
        )
    }

    private fun visible(name: String): Boolean {
        val gs = groupsByTool[name] ?: return name == LOADER
        return gs.isEmpty() || gs.any { it in loaded }
    }

    private fun catalog(): String = buildString {
        append("Load more tools when the task needs them; only the tools you see now are loaded. Loaded tools are ")
        append("available from your next step and stay for the rest of the task. Groups:")
        members.forEach { (g, names) ->
            append("\n- ").append(g)
            summaries[g]?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
            append(" [").append(names.joinToString(", ")).append(']')
        }
    }

    private inner class Loader : Tool {
        override val spec = ToolSpec(
            name = LOADER,
            description = catalog(),
            parameters = schema { string("groups", "Group names from the list, comma-separated (a tool name loads its group).", required = true) },
        )
        override val risk = Risk.Read

        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val raw = args["groups"]
            val names = if (raw is JsonArray) raw.mapNotNull { (it as? JsonPrimitive)?.content } else args.string("groups").split(',', ' ', ';', '\n')
            return load(names)
        }
    }

    companion object {
        const val LOADER = "enable_tools"
    }
}
