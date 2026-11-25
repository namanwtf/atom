package atom.linux.jobs

import atom.linux.ShellEnv
import kotlinx.serialization.Serializable

@Serializable
enum class JobKind { Job, Daemon }

@Serializable
enum class JobState {
    Running, Completed, Failed, TimedOut, Stopped,

    /** The app (or phone) restarted while it ran, so it was ended and its result is unknown. */
    Interrupted,
    ;

    val finished: Boolean get() = this != Running
}

/** Why a running job ended. */
enum class EndReason { Exited, TimedOut, Stopped, Interrupted }

/** A background command or daemon, as stored on disk. */
@Serializable
data class JobRecord(
    val id: String,
    val kind: JobKind,
    /** Daemon name, or a short title for a job. */
    val name: String,
    val command: String,
    val env: ShellEnv,
    val startedAtMs: Long,
    /** 0 means no time limit. */
    val timeoutMs: Long,
    val state: JobState = JobState.Running,
    val exitCode: Int? = null,
    val endedAtMs: Long? = null,
    val pid: Int = 0,
    /** Start time of [pid] in clock ticks (from /proc/pid/stat), to never confuse it with a reused pid. */
    val pidStartTicks: Long = 0,
    val logPath: String,
    /** Chroot session token, when it ran as root in the distro. */
    val chrootToken: String? = null,
    /** Who started it: "user" or "agent". */
    val source: String = "agent",
)

/** Pure job lifecycle rules (unit-tested). */
object JobRules {
    const val DEFAULT_CAP_MINUTES = 10
    const val KEEP_FINISHED = 50
    const val MAX_RUNNING = 16

    fun finish(record: JobRecord, exitCode: Int?, reason: EndReason, now: Long): JobRecord {
        if (record.state.finished) return record
        val state = when (reason) {
            EndReason.TimedOut -> JobState.TimedOut
            EndReason.Stopped -> JobState.Stopped
            EndReason.Interrupted -> JobState.Interrupted
            EndReason.Exited -> if (exitCode == 0) JobState.Completed else JobState.Failed
        }
        return record.copy(state = state, exitCode = exitCode, endedAtMs = now)
    }

    /**
     * After the app process restarted, nothing waits on old jobs any more: every one still marked running is ended
     * as interrupted. [stillAlive] tells whether its process (same pid and start time) is still there, in which
     * case [kill] is called first so nothing keeps running untracked.
     */
    fun reconcileAfterRestart(
        records: List<JobRecord>,
        now: Long,
        stillAlive: (JobRecord) -> Boolean,
        kill: (JobRecord) -> Unit,
    ): List<JobRecord> = records.map { r ->
        if (r.state != JobState.Running) {
            r
        } else {
            if (stillAlive(r)) kill(r)
            finish(r, null, EndReason.Interrupted, now)
        }
    }

    /** Keeps every running job and the newest [keep] finished ones; returns (kept, dropped). */
    fun prune(records: List<JobRecord>, keep: Int = KEEP_FINISHED): Pair<List<JobRecord>, List<JobRecord>> {
        val finished = records.filter { it.state.finished }.sortedByDescending { it.endedAtMs ?: it.startedAtMs }
        val drop = finished.drop(keep).map { it.id }.toSet()
        return records.filter { it.id !in drop } to records.filter { it.id in drop }
    }

    /** The time limit to apply: the requested one, never above the cap unless the cap is off (0). */
    fun effectiveTimeoutMs(kind: JobKind, requestedMinutes: Int?, capMinutes: Int): Long {
        if (kind == JobKind.Daemon) return 0
        val cap = capMinutes.coerceAtLeast(0)
        val minutes = when {
            requestedMinutes == null -> cap
            cap == 0 -> requestedMinutes.coerceAtLeast(0)
            requestedMinutes <= 0 -> cap
            else -> minOf(requestedMinutes, cap)
        }
        return minutes * 60_000L
    }

    fun describe(r: JobRecord): String {
        val what = if (r.kind == JobKind.Daemon) "Daemon ${r.name}" else r.name
        return when (r.state) {
            JobState.Running -> "$what is running"
            JobState.Completed -> "$what completed (exit 0)"
            JobState.Failed -> "$what failed (exit ${r.exitCode ?: "?"})"
            JobState.TimedOut -> "$what timed out after ${r.timeoutMs / 60_000} min and was stopped"
            JobState.Stopped -> "$what was stopped"
            JobState.Interrupted -> "$what was interrupted (Atom restarted)"
        }
    }

    /** A short title for a command: its first line, at most 60 characters. */
    fun titleFor(command: String): String {
        val first = command.trim().lineSequence().firstOrNull()?.trim().orEmpty()
        return if (first.length <= 60) first else first.take(57) + "…"
    }

    private val NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$")
    fun validDaemonName(name: String) = NAME.matches(name)
}
