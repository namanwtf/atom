package atom.app.agent

import atom.core.tool.Tool

/**
 * Tools that legitimately run for minutes (subagents) get their own time limit, so the run's default tool timeout
 * (60 s) does not cut them off. A tool that declares [Tool.timeoutMs] itself keeps its own value.
 */
internal object LongTools {
    private const val MINUTE = 60_000L

    val LIMITS: Map<String, Long> = mapOf(
        "spawn_subagent" to 15 * MINUTE,
        "spawn_subagents_parallel" to 20 * MINUTE,
    )

    fun apply(tool: Tool): Tool {
        if (tool.timeoutMs != null) return tool
        val limit = LIMITS[tool.spec.name] ?: return tool
        return WithTimeout(tool, limit)
    }

    private class WithTimeout(inner: Tool, override val timeoutMs: Long) : Tool by inner
}
