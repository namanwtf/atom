package atom.app.agent

import atom.core.tool.Tool
import atom.core.tool.ToolContext
import atom.core.tool.ToolResult
import kotlinx.serialization.json.JsonObject

/** Wraps an Act-risk tool so it asks first, for users who opted into confirming taps and typing too. */
internal class ConfirmingTool(private val inner: Tool) : Tool by inner {
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val action = "${inner.spec.name}(${args.toString().take(400)})"
        return if (ctx.confirm(action)) {
            inner.run(args, ctx)
        } else {
            ToolResult.error("The user declined this action. Do not retry it; ask or choose another approach.")
        }
    }
}
