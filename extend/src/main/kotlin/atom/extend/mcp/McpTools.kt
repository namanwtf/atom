package atom.extend.mcp

import atom.core.llm.ContentPart
import atom.core.llm.ToolSpec
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolContext
import atom.core.tool.ToolResult
import kotlinx.serialization.json.JsonObject

/** Enabled tools of enabled servers, from the cached catalogs: building this list never touches the network. */
object McpTools {
    /** Descriptions are cut to this; big cloud windows take [atom.core.agent.ModelLimits.mcpDescriptionChars]. */
    @Volatile var maxDescription: Int = 1_000

    /** MCP calls wait up to two minutes for the server (the HTTP client's call timeout), plus a margin. */
    const val CALL_TIMEOUT_MS = 125_000L

    fun create(manager: McpManager, taken: Set<String> = emptySet()): List<Tool> {
        val picked = manager.servers.value.filter { it.enabled }.flatMap { s -> s.tools.filter { it.name in s.enabledTools }.map { s to it } }
        val names = McpNaming.assign(picked.map { (s, t) -> s.name to t.name }, taken)
        return picked.zip(names).map { (pair, name) -> McpTool(manager, pair.first, pair.second, name) }
    }

    private class McpTool(
        private val manager: McpManager,
        private val server: McpServer,
        private val entry: McpToolEntry,
        name: String,
    ) : Tool {
        override val spec = ToolSpec(
            name = name,
            description = buildString {
                append("[MCP · ${server.name}] ")
                entry.title?.takeIf { it.isNotBlank() && it != entry.name }?.let { append(it).append(". ") }
                append(entry.description.ifBlank { entry.name })
            }.let { val max = maxDescription; if (it.length > max) it.take(max - 1) + "…" else it },
            parameters = entry.inputSchema,
        )
        override val risk = if (entry.readOnly) Risk.Read else Risk.Sensitive
        override val timeoutMs: Long get() = CALL_TIMEOUT_MS

        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = try {
            val content = manager.call(server.id, entry.name, args)
            ToolResult(
                text = content.text,
                images = content.images.map { (mime, data) -> ContentPart.Image(mime, data) },
                isError = content.isError,
                sensitive = true,
            )
        } catch (e: McpException) {
            ToolResult(e.message ?: "MCP call failed.", isError = true, sensitive = true)
        }
    }
}
