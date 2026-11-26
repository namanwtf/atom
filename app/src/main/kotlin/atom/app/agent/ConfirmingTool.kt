package atom.app.agent

import atom.core.tool.ApprovalRequest
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolContext
import atom.core.tool.ToolResult
import kotlinx.serialization.json.JsonObject

/**
 * Wraps an Act-risk tool, or one that judges each call (the shell), so it asks first, for users who opted into
 * confirming every step. A call the runtime already asks about ([Risk.Sensitive] for these arguments) is not asked
 * twice. Shell-type calls go through the same prompt as their Sensitive calls, so "allow all commands for this task"
 * covers them too.
 */
internal class ConfirmingTool(private val inner: Tool) : Tool by inner {
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        if (runCatching { inner.riskFor(args) }.getOrDefault(Risk.Sensitive) == Risk.Sensitive) return inner.run(args, ctx)
        val request = runCatching { inner.approvalRequest(args) }.getOrNull()?.copy(reason = null, alwaysAsk = false)
            ?: ApprovalRequest("${inner.spec.name}(${args.toString().let { if (it.length > MAX_ARGS) it.take(MAX_ARGS) + "…" else it }})")
        return if (ctx.approve(request)) {
            inner.run(args, ctx)
        } else {
            ToolResult.error("The user declined this action. Do not retry it; ask or choose another approach.")
        }
    }

    private companion object {
        /** The approval sheet scrolls; this only bounds pathological arguments. */
        const val MAX_ARGS = 8_000
    }
}
