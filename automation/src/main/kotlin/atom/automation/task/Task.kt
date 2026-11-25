package atom.automation.task

import atom.automation.Capability
import atom.automation.schedule.MissedRunPolicy
import atom.automation.schedule.QuietHours
import atom.automation.schedule.Schedule
import kotlinx.serialization.Serializable

@Serializable
enum class NotifyMode(val label: String) { EveryRun("Every run"), OnChange("Only on change"), Silent("Archive silently") }

@Serializable
enum class Destination(val label: String) { Archive("New chat per run"), Conversation("One conversation") }

@Serializable
data class ScheduledTask(
    val id: String,
    val name: String,
    val prompt: String,
    val schedule: Schedule,
    val enabled: Boolean = true,
    val notify: NotifyMode = NotifyMode.EveryRun,
    val progressNotification: Boolean = false,
    val modelId: String? = null,
    val reasoning: String? = null,
    val capabilities: Set<Capability> = Capability.DEFAULT,
    val destination: Destination = Destination.Archive,
    val conversationId: String? = null,
    val missedPolicy: MissedRunPolicy = MissedRunPolicy.CatchUp,
    val quietHours: QuietHours = QuietHours(),
    val maxMinutes: Int = 5,
    /** The last result, compared with the next one for "only on change" and given to the agent as context. */
    val memo: String? = null,
    val nextRunAt: Long? = null,
    val consecutiveFailures: Int = 0,
    val pausedReason: String? = null,
    val createdAt: Long = 0,
) {
    val usesPhoneUi: Boolean get() = Capability.AppUi in capabilities

    companion object {
        val MAX_MINUTES_OPTIONS = listOf(2, 5, 10, 15)
        const val AUTO_PAUSE_AFTER = 3
    }
}

@Serializable
enum class RunStatus(val label: String) {
    Running("Running"),
    Succeeded("Done"),
    Unchanged("No change"),
    Failed("Failed"),
    TimedOut("Timed out"),
    Cancelled("Stopped"),
    Skipped("Skipped"),
    WaitingUnlock("Waiting for unlock"),
    Interrupted("Interrupted"),
    ;

    val terminal: Boolean get() = this != Running
    val failure: Boolean get() = this == Failed || this == TimedOut
}

@Serializable
data class TaskRun(
    val id: String,
    val taskId: String,
    val startedAt: Long,
    val status: RunStatus,
    val endedAt: Long? = null,
    val conversationId: String? = null,
    val summary: String = "",
    val manual: Boolean = false,
)
