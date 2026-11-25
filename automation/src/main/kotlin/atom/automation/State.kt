package atom.automation

import atom.automation.rule.Rule
import atom.automation.rule.RuleRun
import atom.automation.task.ScheduledTask
import atom.automation.task.TaskRun
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

@Serializable
data class Snapshot(
    val tasks: List<ScheduledTask> = emptyList(),
    val taskRuns: List<TaskRun> = emptyList(),
    val rules: List<Rule> = emptyList(),
    val ruleRuns: List<RuleRun> = emptyList(),
)

/** All automation data, in memory, saved whole on every change (it is small). */
class AutomationState(initial: Snapshot = Snapshot(), private val persist: (Snapshot) -> Unit = {}) {
    private val state = MutableStateFlow(initial)
    val flow: StateFlow<Snapshot> = state
    val value: Snapshot get() = state.value

    fun task(id: String) = value.tasks.firstOrNull { it.id == id }
    fun rule(id: String) = value.rules.firstOrNull { it.id == id }
    fun runsOf(taskId: String) = value.taskRuns.filter { it.taskId == taskId }
    fun historyOf(ruleId: String) = value.ruleRuns.filter { it.ruleId == ruleId }

    fun edit(block: (Snapshot) -> Snapshot) {
        state.update(block)
        persist(state.value)
    }

    fun upsertTask(task: ScheduledTask) = edit { s ->
        s.copy(tasks = if (s.tasks.any { it.id == task.id }) s.tasks.map { if (it.id == task.id) task else it } else s.tasks + task)
    }

    fun updateTask(id: String, change: (ScheduledTask) -> ScheduledTask) = edit { s ->
        s.copy(tasks = s.tasks.map { if (it.id == id) change(it) else it })
    }

    fun deleteTask(id: String) = edit { s -> s.copy(tasks = s.tasks.filterNot { it.id == id }, taskRuns = s.taskRuns.filterNot { it.taskId == id }) }

    fun putRun(run: TaskRun) = edit { s ->
        val others = s.taskRuns.filterNot { it.id == run.id }
        val mine = (listOf(run) + others.filter { it.taskId == run.taskId }).sortedByDescending { it.startedAt }.take(RUNS_PER_TASK)
        s.copy(taskRuns = others.filterNot { it.taskId == run.taskId } + mine)
    }

    fun upsertRule(rule: Rule) = edit { s ->
        s.copy(rules = if (s.rules.any { it.id == rule.id }) s.rules.map { if (it.id == rule.id) rule else it } else s.rules + rule)
    }

    fun updateRule(id: String, change: (Rule) -> Rule) = edit { s -> s.copy(rules = s.rules.map { if (it.id == id) change(it) else it }) }

    fun deleteRule(id: String) = edit { s -> s.copy(rules = s.rules.filterNot { it.id == id }, ruleRuns = s.ruleRuns.filterNot { it.ruleId == id }) }

    fun putRuleRun(run: RuleRun) = edit { s ->
        val others = s.ruleRuns.filterNot { it.id == run.id }
        val mine = (listOf(run) + others.filter { it.ruleId == run.ruleId }).sortedByDescending { it.at }.take(RUNS_PER_RULE)
        s.copy(ruleRuns = others.filterNot { it.ruleId == run.ruleId } + mine)
    }

    companion object {
        const val RUNS_PER_TASK = 30
        const val RUNS_PER_RULE = 30
    }
}
