package atom.device.tools

import atom.core.llm.ToolSpec
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolContext
import atom.core.tool.ToolResult
import kotlinx.serialization.json.JsonObject

internal class SimpleTool(
    name: String,
    description: String,
    override val risk: Risk,
    parameters: JsonObject,
    private val body: suspend (JsonObject, ToolContext) -> ToolResult,
) : Tool {
    override val spec = ToolSpec(name, description, parameters)

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = body(args, ctx)
}

internal fun capText(s: String, max: Int): String =
    if (s.length <= max) s else s.take(max) + "\n… [${s.length - max} more chars cut]"
