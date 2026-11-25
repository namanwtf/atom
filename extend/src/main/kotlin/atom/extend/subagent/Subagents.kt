package atom.extend.subagent

import atom.extend.Files
import atom.extend.Frontmatter
import atom.core.llm.ReasoningEffort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

data class SubagentDef(
    val name: String,
    val description: String,
    val whenToUse: String,
    /** Tool categories (read, web, file, terminal, mcp, memory, device, phone) or exact tool names. */
    val tools: List<String>,
    val instructions: String,
    val builtIn: Boolean = false,
    /** Only tools that observe (Risk.Read) are given, whatever [tools] says. */
    val readOnly: Boolean = false,
) {
    fun render(): String = buildString {
        append("---\n")
        append("name: $name\n")
        append("description: ${Frontmatter.quote(description)}\n")
        append("when_to_use: ${Frontmatter.quote(whenToUse)}\n")
        append("tools: [${tools.joinToString(", ")}]\n")
        append("---\n\n")
        append(instructions.trim()).append('\n')
    }

    companion object {
        const val GENERAL = "general"
        val DEFAULT_TOOLS = listOf("read", "file", "terminal", "mcp")
        const val MAX_NAME = 48

        fun validateName(name: String): String? = when {
            name.isEmpty() -> "Enter a name."
            name.length > MAX_NAME -> "Use at most $MAX_NAME characters."
            !Frontmatter.KEBAB.matches(name) -> "Use kebab-case: lowercase letters, digits and hyphens, like \"code-reviewer\"."
            name in BuiltInSubagents.names || name == GENERAL -> "\"$name\" is a built-in subagent."
            else -> null
        }

        fun parse(text: String): SubagentDef? {
            val doc = Frontmatter.parse(text) ?: return null
            val name = doc.fields["name"]?.trim() ?: return null
            if (!Frontmatter.KEBAB.matches(name)) return null
            val tools = doc.lists["tools"] ?: doc.fields["tools"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            return SubagentDef(
                name = name,
                description = doc.fields["description"].orEmpty(),
                whenToUse = doc.fields["when_to_use"] ?: doc.fields["when-to-use"].orEmpty(),
                tools = tools?.map { it.lowercase() }?.takeIf { it.isNotEmpty() } ?: DEFAULT_TOOLS,
                instructions = doc.body,
            )
        }
    }
}

object BuiltInSubagents {
    val explore = SubagentDef(
        name = "explore",
        description = "Read-only exploration and multi-source search.",
        whenToUse = "Finding information across files, the web, apps or MCP servers before deciding what to do.",
        tools = listOf("read", "web", "file", "mcp"),
        instructions = "You explore and search. Look in several places, cross-check what you find, and never change " +
            "anything. Report concrete findings with their sources (paths, URLs, app names), and say what you could not find.",
        builtIn = true,
        readOnly = true,
    )
    val coder = SubagentDef(
        name = "coder",
        description = "File editing and terminal work.",
        whenToUse = "Writing or changing files, running commands, scripts or builds.",
        tools = listOf("read", "file", "terminal"),
        instructions = "You write and change files and run terminal commands. Inspect before editing, make the smallest " +
            "change that does the job, run or test it when possible, and report exactly what you changed and the result.",
        builtIn = true,
    )
    val plan = SubagentDef(
        name = "plan",
        description = "Read-only analysis and step-by-step plans.",
        whenToUse = "Breaking a larger goal into ordered, concrete steps before acting.",
        tools = listOf("read", "web", "file"),
        instructions = "You analyse and plan; you never change anything. Gather the facts you need, then return a " +
            "numbered plan: each step concrete and checkable, with risks, open questions and what to verify at the end.",
        builtIn = true,
        readOnly = true,
    )
    val all = listOf(explore, coder, plan)
    val names = all.map { it.name }.toSet()

    fun general(instructions: String?, tools: List<String>?) = SubagentDef(
        name = SubagentDef.GENERAL,
        description = "Temporary subagent.",
        whenToUse = "",
        tools = tools?.takeIf { it.isNotEmpty() } ?: SubagentDef.DEFAULT_TOOLS,
        instructions = instructions?.takeIf { it.isNotBlank() } ?: "Complete the task you are given.",
    )
}

@Serializable
data class SubagentSettings(
    val enabled: Boolean = true,
    /** Null follows the main model. */
    val modelId: String? = null,
    val modelLabel: String? = null,
    /** Null is the model default. */
    val reasoning: ReasoningEffort? = null,
)

/** Custom subagents, one `agents/<name>.md` each, plus the subagent settings. */
class SubagentStore(root: File) {
    private val dir = File(root, "agents")
    private val settingsFile = File(root, "subagents.json")
    private val json = Json { ignoreUnknownKeys = true }

    private val _custom = MutableStateFlow(loadCustom())
    val custom: StateFlow<List<SubagentDef>> = _custom.asStateFlow()

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<SubagentSettings> = _settings.asStateFlow()

    fun all(): List<SubagentDef> = BuiltInSubagents.all + _custom.value

    fun find(name: String): SubagentDef? = all().firstOrNull { it.name == name.trim().lowercase() }

    fun exists(name: String) = File(dir, "$name.md").isFile

    @Synchronized
    fun save(def: SubagentDef, previousName: String? = null) {
        SubagentDef.validateName(def.name)?.let { throw IllegalArgumentException(it) }
        Files.writeAtomic(File(dir, "${def.name}.md"), def.render())
        if (previousName != null && previousName != def.name) File(dir, "$previousName.md").delete()
        _custom.value = loadCustom()
    }

    @Synchronized
    fun delete(name: String): Boolean {
        val ok = File(dir, "$name.md").delete()
        _custom.value = loadCustom()
        return ok
    }

    fun updateSettings(change: (SubagentSettings) -> SubagentSettings) {
        val next = change(_settings.value)
        Files.writeAtomic(settingsFile, json.encodeToString(SubagentSettings.serializer(), next))
        _settings.value = next
    }

    private fun loadCustom(): List<SubagentDef> =
        dir.listFiles { f -> f.extension == "md" }.orEmpty().mapNotNull { f ->
            SubagentDef.parse(f.readText())?.takeIf { it.name == f.nameWithoutExtension && it.name !in BuiltInSubagents.names }
        }.sortedBy { it.name }

    private fun loadSettings(): SubagentSettings =
        Files.readOrNull(settingsFile)?.let { runCatching { json.decodeFromString(SubagentSettings.serializer(), it) }.getOrNull() }
            ?: SubagentSettings()
}
