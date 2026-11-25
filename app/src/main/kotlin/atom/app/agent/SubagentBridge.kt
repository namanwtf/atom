package atom.app.agent

import atom.app.AppGraph
import atom.app.data.EngineSelection
import atom.app.data.ModelRole
import atom.core.agent.RunControl
import atom.core.tool.Tool
import atom.extend.ContextWindowProvider
import atom.extend.SubagentEngine
import atom.extend.SubagentEngineProvider
import atom.extend.ToolScope

/**
 * Gives subagents the parent run's tools, its pause state and phone lease, and the parent's model (or the
 * subagent role's own model choice), so a subagent behaves like part of the run that spawned it.
 */
class SubagentBridge(private val graph: AppGraph) : ToolScope, SubagentEngineProvider, ContextWindowProvider {
    override fun toolsFor(taskId: String): List<Tool> = graph.tasks.toolsOf(taskId)

    override fun controlFor(taskId: String): RunControl = graph.tasks.controlOf(taskId)

    override suspend fun acquire(role: String, modelId: String?, parentTaskId: String): SubagentEngine {
        val parent = graph.tasks.selectionOf(parentTaskId)
        val routed = graph.router.acquire(ModelRole.Subagent, parent, modelId?.let(::parseSelection))
        return SubagentEngine(
            engine = routed.engine,
            model = routed.model,
            contextWindow = routed.contextWindow,
            onDevice = routed.onDevice,
            release = { graph.router.release(routed) },
        )
    }

    override fun currentContextWindow(): Int? = graph.tasks.currentContextWindow()

    /** "provider/model" picks a cloud model explicitly; anything else follows the parent. */
    private fun parseSelection(id: String): EngineSelection? {
        val slash = id.indexOf('/')
        if (slash <= 0 || slash == id.lastIndex) return null
        return EngineSelection.Cloud(providerId = id.substring(0, slash), model = id.substring(slash + 1))
    }
}
