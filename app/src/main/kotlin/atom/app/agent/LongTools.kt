package atom.app.agent

import atom.core.agent.ModelLimits
import atom.core.tool.Tool

/**
 * Tools that legitimately run for minutes get their own time limit, so the run's default tool timeout (60 s) does not
 * cut them off: subagents (their own time budget plus a margin), and image generation, whose HTTP client waits up to
 * 330 s for the provider. A tool that declares [Tool.timeoutMs] itself keeps its own value (MCP calls declare two
 * minutes, matching their client).
 */
internal object LongTools {
    private const val MINUTE = 60_000L

    val LIMITS: Map<String, Long> = mapOf(
        "spawn_subagent" to 15 * MINUTE,
        "spawn_subagents_parallel" to 20 * MINUTE,
        // The image client's call timeout (330 s) plus a margin, so its own clear error arrives first.
        "generate_image" to 340_000L,
        "edit_image" to 340_000L,
    )

    /** Limits that follow the subagent time budget: never shorter than [LIMITS], always a minute above the budget. */
    fun limitFor(name: String, limits: ModelLimits = ModelLimits.Default): Long? {
        val base = LIMITS[name] ?: return null
        if (!name.startsWith("spawn_subagent")) return base
        return maxOf(base, AdvancedLimits.subagentBudgetMs(limits) + MINUTE)
    }

    fun apply(tool: Tool): Tool = apply(tool, ModelLimits.Default)

    fun apply(tool: Tool, limits: ModelLimits): Tool {
        if (tool.timeoutMs != null) return tool
        val limit = limitFor(tool.spec.name, limits) ?: return tool
        return WithTimeout(tool, limit)
    }

    private class WithTimeout(inner: Tool, override val timeoutMs: Long) : Tool by inner
}
