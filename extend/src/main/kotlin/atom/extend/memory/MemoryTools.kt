package atom.extend.memory

import atom.core.llm.ToolSpec
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolContext
import atom.core.tool.ToolResult
import atom.core.tool.optInt
import atom.core.tool.optString
import atom.core.tool.schema
import atom.core.tool.string
import kotlinx.serialization.json.JsonObject

/** The agent's memory tools. Every result is sensitive: memory is personal and never kept in saved chats. */
object MemoryTools {
    private const val DEFAULT_LINES = 200
    private const val MAX_LINES = 400

    fun create(store: MemoryStore): List<Tool> = listOf(read(store), append(store), update(store), clear(store))

    private fun tool(spec: ToolSpec, risk: Risk, body: suspend (JsonObject, ToolContext) -> ToolResult): Tool = object : Tool {
        override val spec = spec
        override val risk = risk
        override suspend fun run(args: JsonObject, ctx: ToolContext) = body(args, ctx)
    }

    private fun secret(text: String, error: Boolean = false) = ToolResult(text, isError = error, sensitive = true)

    private fun outcome(edit: MemoryEdit, done: String): ToolResult = when (edit) {
        is MemoryEdit.Saved -> secret("$done Memory hash is now ${edit.state.shortHash}.")
        is MemoryEdit.Conflict -> secret(
            "MEMORY.md changed since you read it (now ${edit.current.shortHash}). Nothing was written; call memory_read and try again.",
            error = true,
        )
        is MemoryEdit.Rejected -> secret(edit.reason, error = true)
    }

    private fun read(store: MemoryStore) = tool(
        ToolSpec(
            name = "memory_read",
            description = "Read the user's long-term memory (MEMORY.md). Without arguments: the section outline and the " +
                "first lines. Give 'section' to read one section, or 'offset'/'limit' (lines) to page through the file.",
            parameters = schema {
                string("section", "Section title to read, e.g. \"Core memory\" or \"Notes\".")
                integer("offset", "First line to return, starting at 1.")
                integer("limit", "Number of lines (default $DEFAULT_LINES, max $MAX_LINES).")
            },
        ),
        Risk.Read,
    ) { args, _ ->
        val state = store.current()
        val lines = state.text.lines()
        val sections = MemoryText.sections(state.text)
        val sectionName = args.optString("section")?.takeIf { it.isNotBlank() }
        val range = if (sectionName != null) {
            val s = MemoryText.findSection(state.text, sectionName)
                ?: return@tool secret(
                    "No section \"$sectionName\". Sections: ${sections.joinToString { it.title }.ifEmpty { "none" }}.",
                    error = true,
                )
            s.startLine until s.endLine
        } else {
            0 until lines.size
        }
        val offset = ((args.optInt("offset") ?: 1) - 1).coerceAtLeast(0)
        val limit = (args.optInt("limit") ?: DEFAULT_LINES).coerceIn(1, MAX_LINES)
        val from = (range.first + offset).coerceAtMost(range.last + 1)
        val to = minOf(from + limit, range.last + 1)
        val body = lines.subList(from, to).joinToString("\n")
        secret(buildString {
            append("MEMORY.md · ${lines.size} lines · ${MemoryText.byteSize(state.text) / 1024} KiB · hash ${state.shortHash}\n")
            append("Sections: ")
            append(sections.joinToString { "${it.title} (lines ${it.startLine + 1}-${it.endLine})" }.ifEmpty { "none" })
            append("\n--- lines ${from + 1}-$to ---\n")
            append(body)
            if (to <= range.last) append("\n--- more: offset=${to - range.first + 1} ---")
        })
    }

    private fun append(store: MemoryStore) = tool(
        ToolSpec(
            name = "memory_append",
            description = "Add a durable fact or preference to the user's long-term memory. Prefer short bullet lines " +
                "(\"- Prefers metric units\"). Use section \"Core memory\" only for what matters in every conversation.",
            parameters = schema {
                string("text", "Text to add, usually one or more bullet lines.", required = true)
                string("section", "Section to add it to (created if missing). Omit to add at the end of the file.")
            },
        ),
        Risk.Act,
    ) { args, _ ->
        outcome(store.append(args.string("text"), args.optString("section")), "Saved to memory.")
    }

    private fun update(store: MemoryStore) = tool(
        ToolSpec(
            name = "memory_update",
            description = "Replace exact text in the user's memory. old_text must appear exactly once; copy it from " +
                "memory_read. Use an empty new_text to delete it. Pass the hash from memory_read to make sure nobody " +
                "changed the file in between.",
            parameters = schema {
                string("old_text", "Exact text to replace.", required = true)
                string("new_text", "Replacement text (may be empty).", required = true)
                string("hash", "Hash shown by memory_read.")
            },
        ),
        Risk.Act,
    ) { args, _ ->
        outcome(store.update(args.string("old_text"), args.optString("new_text").orEmpty(), args.optString("hash")), "Memory updated.")
    }

    private fun clear(store: MemoryStore) = tool(
        ToolSpec(
            name = "memory_clear",
            description = "Erase the user's entire long-term memory and restore the empty template. Only when the user " +
                "explicitly asks to forget everything.",
            parameters = schema { string("hash", "Hash shown by memory_read.") },
        ),
        Risk.Sensitive,
    ) { args, _ ->
        outcome(store.clear(args.optString("hash")), "Memory cleared.")
    }
}
