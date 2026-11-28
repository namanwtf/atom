package atom.app.agent

import atom.automation.task.ScheduledTask
import atom.core.agent.LimitOverrides
import atom.core.agent.ModelLimits

/**
 * The Settings > Advanced limits as last saved, for the places that cannot read settings when they need them (the
 * subagent time budget, a new scheduled task's duration). The task runner keeps it current from the settings flow;
 * nothing polls.
 */
internal object AdvancedLimits {
    @Volatile
    var current: LimitOverrides = LimitOverrides()
        private set

    fun apply(overrides: LimitOverrides) {
        current = overrides
        ScheduledTask.newTaskMinutes = (overrides.taskMinutes ?: DEFAULT_TASK_MINUTES).coerceIn(1, ScheduledTask.MAX_MINUTES_LIMIT)
    }

    /** A cloud subagent's budget under [limits] with the saved overrides (on the phone it stays short). */
    fun subagentBudgetMs(limits: ModelLimits = ModelLimits.Default): Long = limits.copy(overrides = current).subagentBudgetMs

    const val DEFAULT_TASK_MINUTES = 15
}
