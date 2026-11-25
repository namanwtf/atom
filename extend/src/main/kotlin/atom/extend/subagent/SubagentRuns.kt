package atom.extend.subagent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

enum class SubagentStatus { Running, Done, Partial, Failed, Cancelled }

enum class SubagentStepState { Running, Done, Failed }

data class SubagentStep(
    val id: String,
    val tool: String,
    val arguments: String,
    val state: SubagentStepState,
    val durationMs: Long? = null,
)

/** One subagent run, for the chat's details sheet. Kept in memory only, never saved. */
data class SubagentRun(
    val id: String,
    val parentTaskId: String,
    val agent: String,
    val prompt: String,
    val status: SubagentStatus,
    val model: String? = null,
    val text: String = "",
    val steps: List<SubagentStep> = emptyList(),
    val summary: String? = null,
    val toolCalls: Int = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    /** Input tokens served from the provider's prompt cache, summed over the run's model calls. */
    val cachedTokens: Int = 0,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val error: String? = null,
)

/** Live and recent subagent runs (the newest [MAX_KEPT]). The chat filters by [SubagentRun.parentTaskId]. */
object SubagentRuns {
    private const val MAX_KEPT = 40
    private val _runs = MutableStateFlow<List<SubagentRun>>(emptyList())
    val runs: StateFlow<List<SubagentRun>> = _runs.asStateFlow()

    fun forTask(taskId: String): List<SubagentRun> = _runs.value.filter { it.parentTaskId == taskId }

    fun get(id: String): SubagentRun? = _runs.value.firstOrNull { it.id == id }

    internal fun start(parentTaskId: String, agent: String, prompt: String): String {
        val id = UUID.randomUUID().toString()
        _runs.update { (it + SubagentRun(id, parentTaskId, agent, prompt, SubagentStatus.Running)).takeLast(MAX_KEPT) }
        return id
    }

    internal fun update(id: String, change: SubagentRun.() -> SubagentRun) {
        _runs.update { list -> list.map { if (it.id == id) it.change() else it } }
    }

    fun clear() {
        _runs.update { list -> list.filter { it.status == SubagentStatus.Running } }
    }
}

/** What the details sheet shows for a run (pure, unit-tested). */
object SubagentRunLabels {
    /** Steps shown in full; earlier ones fold into "N earlier steps folded". */
    const val VISIBLE_STEPS = 6

    fun status(run: SubagentRun): String = when (run.status) {
        SubagentStatus.Running -> "Running…"
        SubagentStatus.Cancelled -> "Stopped"
        SubagentStatus.Partial -> "Incomplete"
        SubagentStatus.Failed -> "Failed"
        SubagentStatus.Done -> "Completed · ${run.toolCalls} tool call" + if (run.toolCalls == 1) "" else "s"
    }

    /** The steps to show and how many earlier ones are folded away. */
    fun visibleSteps(steps: List<SubagentStep>, keep: Int = VISIBLE_STEPS): Pair<Int, List<SubagentStep>> =
        if (steps.size <= keep) 0 to steps else (steps.size - keep) to steps.takeLast(keep)
}
