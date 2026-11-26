package atom.app.agent

import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolContext
import atom.core.tool.ToolResult
import kotlinx.serialization.json.JsonObject

/**
 * Wraps an Act-risk tool, or one that judges each call (the shell), so it asks first, for users who opted into
 * confirming every step. A call the runtime already asks about ([Risk.Sensitive] for these arguments) is not asked
 * twice.
 */
internal class ConfirmingTool(private val inner: Tool) : Tool by inner {
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        if (runCatching { inner.riskFor(args) }.getOrDefault(Risk.Sensitive) == Risk.Sensitive) return inner.run(args, ctx)
        val action = "${inner.spec.name}(${args.toString().take(400)})"
        return if (ctx.confirm(action)) {
            inner.run(args, ctx)
        } else {
            ToolResult.error("The user declined this action. Do not retry it; ask or choose another approach.")
        }
    }
}
