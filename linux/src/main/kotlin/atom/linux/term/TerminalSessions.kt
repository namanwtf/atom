package atom.linux.term

import android.content.Context
import atom.linux.AndroidShell
import atom.linux.LinuxEnv
import atom.linux.ShellEnv
import atom.linux.runtime.LinuxRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

enum class SessionMode { Console, Simple }

/** The labels of a row in the Sessions dialog. */
enum class SessionStatus { Current, Running, Idle, Exited }

object SessionStatuses {
    /** "Exited" wins; otherwise the current session is marked Current and every live one Running or Idle. */
    fun of(current: Boolean, exited: Boolean, busy: Boolean): List<SessionStatus> = when {
        exited -> if (current) listOf(SessionStatus.Current, SessionStatus.Exited) else listOf(SessionStatus.Exited)
        else -> listOfNotNull(SessionStatus.Current.takeIf { current }, if (busy) SessionStatus.Running else SessionStatus.Idle)
    }
}

/** Who opened a session; the agent has its own small pool so it can never use up the user's slots. */
enum class SessionOwner { User, Agent }

/** One open terminal. [terminal] changes on restart; observe [generation]. */
class SessionEntry internal constructor(
    val id: String,
    val mode: SessionMode,
    val env: ShellEnv,
    val owner: SessionOwner,
    val number: Int,
) {
    @Volatile var terminal: TerminalSession? = null
        internal set
    @Volatile var simple: SimpleSession? = null
        internal set

    internal val generationFlow = MutableStateFlow(0)
    val generation: StateFlow<Int> get() = generationFlow

    /** When the program last wrote to the screen (wall clock), for the Running / Idle label. */
    @Volatile var lastOutputAtMs: Long = 0L
        internal set

    /** True while a Simple-mode command runs, or while a console printed something in the last [BUSY_WINDOW_MS]. */
    fun busy(nowMs: Long = System.currentTimeMillis()): Boolean {
        simple?.let { s -> return s.blocks.value.lastOrNull()?.running == true }
        return nowMs - lastOutputAtMs < BUSY_WINDOW_MS
    }

    companion object {
        const val BUSY_WINDOW_MS = 2_000L
    }

    val title: String get() = if (owner == SessionOwner.Agent) "Agent ${env.label} $number" else "${env.label} $number"
}

/**
 * The open terminal sessions, owned by the process rather than a screen, so they survive leaving the page. Up to
 * [MAX_PER_MODE] per mode for the user plus [MAX_AGENT] for the agent. Each live session holds the runtime service
 * (see [LinuxRuntime]); it is released as soon as the session's program ends.
 */
object TerminalSessions {
    const val MAX_PER_MODE = 6
    const val MAX_AGENT = 4

    private val _sessions = MutableStateFlow<List<SessionEntry>>(emptyList())
    val sessions: StateFlow<List<SessionEntry>> = _sessions.asStateFlow()
    private val counter = AtomicInteger(0)
    private val numbers = HashMap<String, Int>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun canOpen(mode: SessionMode, owner: SessionOwner = SessionOwner.User): Boolean {
        val list = _sessions.value
        return if (owner == SessionOwner.Agent) list.count { it.owner == owner } < MAX_AGENT
        else list.count { it.owner == owner && it.mode == mode } < MAX_PER_MODE
    }

    /** Opens a session; throws IllegalStateException (limit reached, Linux missing, su missing...). */
    fun open(
        context: Context,
        mode: SessionMode,
        env: ShellEnv,
        cols: Int,
        rows: Int,
        owner: SessionOwner = SessionOwner.User,
    ): SessionEntry {
        check(canOpen(mode, owner)) {
            if (owner == SessionOwner.Agent) "The agent already has $MAX_AGENT sessions open; close one first."
            else "At most $MAX_PER_MODE ${mode.name.lowercase()} sessions; close one first."
        }
        val key = "$owner/$mode/$env"
        val number = synchronized(numbers) { (numbers[key] ?: 0).plus(1).also { numbers[key] = it } }
        val entry = SessionEntry("s${counter.incrementAndGet()}", mode, env, owner, number)
        start(context, entry, cols, rows)
        _sessions.value = _sessions.value + entry
        return entry
    }

    fun find(id: String): SessionEntry? = _sessions.value.firstOrNull { it.id == id }

    /** Ends the session's program (and everything it started) and starts a fresh one in the same slot. */
    fun restart(context: Context, id: String, cols: Int, rows: Int) {
        val entry = find(id) ?: return
        stop(entry)
        start(context, entry, cols, rows)
    }

    fun close(id: String) {
        val entry = find(id) ?: return
        _sessions.value = _sessions.value - entry
        stop(entry)
    }

    fun closeAll() = _sessions.value.forEach { close(it.id) }

    private fun start(context: Context, entry: SessionEntry, cols: Int, rows: Int) {
        val app = context.applicationContext
        val simple = if (entry.mode == SessionMode.Simple) SimpleSession(cols) else null
        val hook: ((ByteArray, Int) -> Unit)? = simple?.let { s -> { b, n -> s.onOutput(b, n) } }
        val session = when (entry.env) {
            ShellEnv.Android -> AndroidShell.openSession(app, cols, rows, root = false, onOutput = hook)
            ShellEnv.Root -> AndroidShell.openSession(app, cols, rows, root = true, onOutput = hook)
            ShellEnv.Linux -> LinuxEnv.get(app).openSession(cols, rows, onOutput = hook)
        }
        simple?.attach(session)
        entry.terminal = session
        entry.simple = simple
        entry.generationFlow.value += 1
        val claim = "session:${entry.id}:${entry.generationFlow.value}"
        LinuxRuntime.acquire(app, claim, LinuxRuntime.Claim.Session)
        // The screen only changes when the program prints, so each new frame marks activity.
        val activity = scope.launch { session.screen.drop(1).collect { entry.lastOutputAtMs = System.currentTimeMillis() } }
        // Release the hold as soon as the program ends, even if the user keeps the tab open.
        scope.launch {
            session.exitCode.first { it != null }
            activity.cancel()
            LinuxRuntime.release(claim)
        }
    }

    private fun stop(entry: SessionEntry) {
        entry.terminal?.close()
        entry.simple?.dispose()
    }
}
