package atom.characters.session

import atom.characters.data.CharacterStore
import atom.characters.prompt.Macros
import atom.core.llm.ToolSpec
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolContext
import atom.core.tool.ToolResult
import atom.core.tool.optString
import atom.core.tool.schema
import kotlinx.serialization.json.JsonObject

object CharacterTools {
    fun create(store: () -> CharacterStore, active: () -> Set<String>): List<Tool> =
        listOf(storyMemory(store, active), listCharacters(store))

    private fun tool(spec: ToolSpec, risk: Risk, body: suspend (JsonObject) -> ToolResult): Tool = object : Tool {
        override val spec = spec
        override val risk = risk
        override suspend fun run(args: JsonObject, ctx: ToolContext) = body(args)
    }

    internal fun storyMemory(store: () -> CharacterStore, active: () -> Set<String>) = tool(
        ToolSpec(
            name = CharacterSession.STORY_MEMORY_TOOL,
            description = "Story memory for a character chat: notes about the story, its events and the user's " +
                "role, shared by every chat with that character. Use it instead of the memory_* tools in character " +
                "chats. action=append adds a line, replace rewrites all of it (empty text clears it), read shows it.",
            parameters = schema {
                string("action", "What to do.", required = true, enum = listOf("append", "replace", "read"))
                string("text", "Text to append or the full new story memory.")
                string("character_id", "The character's id from the character chat instructions.")
            },
        ),
        Risk.Act,
    ) { args ->
        val s = store().also { it.load() }
        val explicit = args.optString("character_id")?.trim()?.ifBlank { null }
        val running = active()
        val id = explicit ?: running.singleOrNull()
            ?: return@tool ToolResult.error(
                if (running.isEmpty()) "No character chat is active. Pass character_id." else "Several character chats are active; pass character_id.",
            )
        if (s.get(id) == null && s.bindings.value.values.none { it.characterId == id }) {
            return@tool ToolResult.error("No character with id \"$id\". Use list_characters.")
        }
        val text = args.optString("text").orEmpty()
        when (args.optString("action")) {
            "read" -> ToolResult.ok(s.storyMemory(id).ifBlank { "(Story memory is empty.)" })
            "append" -> {
                if (text.isBlank()) return@tool ToolResult.error("Nothing to append.")
                if (s.appendStoryMemory(id, text)) ToolResult.ok("Added to story memory.") else tooLong()
            }
            "replace" -> if (s.setStoryMemory(id, text)) ToolResult.ok(if (text.isBlank()) "Story memory cleared." else "Story memory replaced.") else tooLong()
            else -> ToolResult.error("action must be append, replace or read.")
        }
    }

    private fun tooLong() = ToolResult.error("Story memory would exceed ${CharacterStore.STORY_LIMIT / 1024} KiB. Replace it with a shorter summary.")

    internal fun listCharacters(store: () -> CharacterStore) = tool(
        ToolSpec(
            name = CharacterSession.LIST_TOOL,
            description = "List the user's characters (personas for role-play chats) with id, name, tags and a short " +
                "profile. Optional query filters by name or tag (#tag for tags only).",
            parameters = schema { string("query", "Name or tag to filter by.") },
        ),
        Risk.Read,
    ) { args ->
        val s = store().also { it.load() }
        val found = CharacterStore.search(s.characters.value, args.optString("query").orEmpty())
        if (found.isEmpty()) return@tool ToolResult.ok("No characters found.")
        ToolResult.ok(
            found.joinToString("\n") { c ->
                buildString {
                    append("- ").append(c.displayName).append(" (id ").append(c.id).append(')')
                    if (c.favourite) append(" ★")
                    if (c.tags.isNotEmpty()) append(" · ").append(c.tags.joinToString(", "))
                    val profile = Macros.apply(c.profile, c.displayName, "the user").replace('\n', ' ').trim()
                    if (profile.isNotEmpty()) append("\n  ").append(if (profile.length > 160) profile.take(157) + "…" else profile)
                }
            },
        )
    }
}
