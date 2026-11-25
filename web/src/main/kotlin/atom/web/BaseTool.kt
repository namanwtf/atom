package atom.web

import atom.core.llm.ToolSpec
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolArgumentException
import atom.core.tool.ToolContext
import atom.core.tool.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

internal abstract class BaseTool(
    name: String,
    description: String,
    parameters: JsonObject,
    override val risk: Risk,
    private val gate: (() -> String?)? = null,
) : Tool {
    override val spec = ToolSpec(name, description, parameters)

    final override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        gate?.invoke()?.let { return ToolResult.error(it) }
        return try {
            exec(args, ctx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ToolArgumentException) {
            ToolResult.error(e.message ?: "Bad arguments")
        } catch (e: java.io.IOException) {
            ToolResult.error("Network or file error: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            ToolResult.error(e.message ?: e.javaClass.simpleName)
        }
    }

    protected abstract suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult
}
