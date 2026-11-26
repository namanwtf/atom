package atom.ui.feature

import kotlinx.coroutines.flow.StateFlow

/** Who asked for a run; drives presentation (e.g. the bubble shows its own runs) and per-source model choice. */
enum class RunSource { Chat, Bubble, Assistant, Voice, Automation, Schedule }

enum class RunPhase {
    Starting,
    Running,
    /** Paused by the user; they have control of the phone until [AgentHost.resume]. */
    Paused,
    /** Waiting for another run to release phone control. */
    WaitingForControl,
    /** Waiting for the user to approve a sensitive action. */
    WaitingForApproval,
    Done,
    Failed,
    Cancelled,
    ;

    val active: Boolean get() = this != Done && this != Failed && this != Cancelled
}

enum class StepState { Running, Completed, Failed, Unknown }

data class HostStep(
    val id: String,
    val tool: String,
    val summary: String,
    val state: StepState,
    val durationMs: Long? = null,
)

data class HostApproval(val id: Long, val action: String, val expiresAtMillis: Long)

/** A live or just-finished agent run, as seen from outside the chat screen. */
data class HostRun(
    val conversationId: String,
    val title: String,
    val source: RunSource,
    val phase: RunPhase,
    /** Streamed answer text so far. */
    val text: String,
    val steps: List<HostStep>,
    val pending: HostApproval? = null,
    /** User messages queued to steer this run at its next safe point. */
    val queued: List<String> = emptyList(),
    val error: String? = null,
)

/**
 * The single entry point for starting and controlling agent runs from anywhere in the app: the bubble, the
 * assistant overlay, voice, automations and scheduled tasks all go through this, so every run gets the same
 * tools, approvals, phone-control lease and battery behaviour as a chat run.
 */
interface AgentHost {
    /** All active runs plus the most recent finished run per conversation. */
    val runs: StateFlow<List<HostRun>>

    /**
     * Starts a run in [conversationId] (a new conversation when null) and returns its conversation id, or null
     * if that conversation is already running (use [steer] instead).
     */
    fun start(
        prompt: String,
        conversationId: String? = null,
        source: RunSource = RunSource.Chat,
        attachments: List<String> = emptyList(),
    ): String?

    /** Queues [text] for a running conversation; it is delivered at the next safe point without cancelling. */
    fun steer(conversationId: String, text: String)

    fun pause(conversationId: String)
    fun resume(conversationId: String)
    fun stop(conversationId: String)
    fun answer(approvalId: Long, approved: Boolean)

    /** Approves and allows the run's shell commands for the rest of that run (where the run offers it). */
    fun allowForTask(approvalId: Long) = answer(approvalId, true)
}

/** Set once by the app in Application.onCreate; feature modules read it. */
object Atom {
    @Volatile
    private var current: AgentHost? = null

    val host: AgentHost get() = checkNotNull(current) { "AgentHost not installed yet" }

    fun install(host: AgentHost) {
        current = host
    }
}
