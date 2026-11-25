package atom.device.tools

import atom.core.llm.ToolSpec
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolContext
import atom.core.tool.ToolResult
import kotlinx.serialization.json.JsonObject

/**
 * Marks a tool that asks the user itself with a plain-English preview before changing protected state (root and
 * Shizuku changes). A runtime that wraps Act tools in an extra confirmation can skip these.
 */
interface ConfirmsItself

internal open class SimpleTool(
    name: String,
    description: String,
    override val risk: Risk,
    parameters: JsonObject,
    override val usesPhoneUi: Boolean = false,
    private val body: suspend (JsonObject, ToolContext) -> ToolResult,
) : Tool {
    override val spec = ToolSpec(name, description, parameters)

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = body(args, ctx)
}

internal class SelfConfirmingTool(
    name: String,
    description: String,
    parameters: JsonObject,
    body: suspend (JsonObject, ToolContext) -> ToolResult,
) : SimpleTool(name, description, Risk.Act, parameters, false, body), ConfirmsItself

internal fun capText(s: String, max: Int): String =
    if (s.length <= max) s else s.take(max) + "\n… [${s.length - max} more chars cut]"

/** Asks with [preview]; a no becomes the standard declined result. */
internal suspend fun ToolContext.approve(preview: String): ToolResult? =
    if (confirm(preview)) null else ToolResult.error("The user declined: $preview Do not retry; ask what they want instead.")
