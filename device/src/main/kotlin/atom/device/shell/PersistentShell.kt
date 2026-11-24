package atom.device.shell

import atom.device.ShellExecutor
import atom.device.ShellResult
import atom.device.ShellTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.Writer
import java.util.UUID

/**
 * One long-lived shell process (`su` or a Shizuku `sh`) reused for every command. Spawning `su` is the
 * expensive part (the root manager authorises each spawn), so it happens at most once per burst of work;
 * the process is killed after [idleTimeoutMs] without commands so nothing lingers while idle.
 *
 * Each command runs in its own subshell via `eval`, so `exit`, `cd` and syntax errors cannot break the
 * session. Completion is detected by a per-session marker line on both stdout and stderr.
 */
internal class PersistentShell(
    override val tier: ShellTier,
    private val idleTimeoutMs: Long = 120_000,
    private val spawn: () -> Process,
) : ShellExecutor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var session: Session? = null
    private var idleJob: Job? = null

    /** How many processes have been spawned; exposed for tests. */
    @Volatile
    var spawnCount = 0
        private set

    override suspend fun run(command: String, timeoutMs: Long): ShellResult = mutex.withLock {
        idleJob?.cancel()
        try {
            try {
                execOnce(command, timeoutMs)
            } catch (e: StaleSession) {
                // The idle process died between commands (killed externally); nothing ran, so retry once.
                kill()
                execOnce(command, timeoutMs)
            }
        } catch (e: StaleSession) {
            kill()
            ShellResult(-1, "", "The shell process could not accept commands: ${e.cause?.message}")
        } catch (e: Throwable) {
            kill() // cancelled mid-command or I/O failure: the session state is unknown
            if (e is kotlinx.coroutines.CancellationException) throw e
            ShellResult(-1, "", "Shell error: ${e.message ?: e::class.simpleName}")
        } finally {
            scheduleIdleKill()
        }
    }

    private suspend fun execOnce(command: String, timeoutMs: Long): ShellResult {
        val s = session?.takeIf { !it.dead } ?: try {
            kill()
            Session(spawn().also { spawnCount++ }, scope).also { session = it }
        } catch (e: Exception) {
            return ShellResult(-1, "", "Could not start the ${tier.name.lowercase()} shell: ${e.message}")
        }
        val result = s.exec(command, timeoutMs)
        return if (result == null) {
            kill()
            ShellResult(124, "", "Timed out after ${timeoutMs / 1000.0}s; the command was killed.")
        } else {
            if (result.ended) kill()
            result.toShellResult()
        }
    }

    /** Kills the shell process now; the next command starts a fresh one. */
    fun close() {
        idleJob?.cancel()
        kill()
    }

    private fun scheduleIdleKill() {
        if (session == null) return
        idleJob = scope.launch {
            delay(idleTimeoutMs)
            mutex.withLock { kill() }
        }
    }

    private fun kill() {
        session?.destroy()
        session = null
    }

    private class Read(val text: String, val exitCode: Int?)

    private class Outcome(val stdout: Read, val stderr: Read) {
        /** The shell process died before printing the marker. */
        val ended get() = stdout.exitCode == null || stderr.exitCode == null

        fun toShellResult(): ShellResult {
            val err = stderr.text.trimEnd()
            return if (ended) {
                ShellResult(-1, stdout.text.trimEnd(), (err + "\n[shell session ended unexpectedly]").trim())
            } else {
                ShellResult(stdout.exitCode!!, stdout.text.trimEnd(), err)
            }
        }
    }

    private class StaleSession(cause: Throwable) : Exception(cause)

    /**
     * Liveness is tracked by the protocol rather than Process.isAlive, which a Shizuku remote process cannot
     * answer reliably over binder. Readers run in the shell's own scope, not as children of [exec], so a
     * timeout or cancellation returns at once; destroying the process ends the orphaned reads.
     */
    private class Session(private val process: Process, private val scope: CoroutineScope) {
        private val marker = "__ATOM_" + UUID.randomUUID().toString().replace("-", "").take(12) + "__"
        private val stdin: Writer = process.outputStream.bufferedWriter()
        private val stdout: BufferedReader = process.inputStream.bufferedReader()
        private val stderr: BufferedReader = process.errorStream.bufferedReader()

        @Volatile
        var dead = false
            private set

        /** Returns null on timeout. */
        suspend fun exec(command: String, timeoutMs: Long): Outcome? {
            try {
                stdin.write(ShellFraming.frame(command, marker))
                stdin.flush()
            } catch (e: java.io.IOException) {
                destroy()
                throw StaleSession(e)
            }
            val out = scope.async { readUntilMarker(stdout) }
            val err = scope.async { readUntilMarker(stderr) }
            try {
                return withTimeoutOrNull(timeoutMs) { Outcome(out.await(), err.await()) }
                    .also { if (it == null) destroy() }
            } catch (e: Throwable) {
                destroy()
                throw e
            }
        }

        private fun readUntilMarker(reader: BufferedReader): Read {
            val sb = StringBuilder()
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    ShellFraming.parseMarker(line, marker)?.let { return Read(ShellFraming.stripFrameNewline(sb), it) }
                    if (sb.length < MAX_CAPTURE) sb.append(line).append('\n')
                }
            } catch (e: Exception) {
                // stream closed by destroy()
            }
            return Read(sb.toString(), null)
        }

        fun destroy() {
            dead = true
            runCatching { stdin.close() }
            runCatching { process.destroy() }
            runCatching { stdout.close() }
            runCatching { stderr.close() }
        }
    }

    private companion object {
        const val MAX_CAPTURE = 64 * 1024
    }
}

/** Pure command framing for [PersistentShell]; separated so it can be unit tested. */
internal object ShellFraming {
    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    fun frame(command: String, marker: String): String = buildString {
        append("( eval ").append(quote(command)).append(" ) </dev/null\n")
        append("printf '\\n%s:%d\\n' '").append(marker).append("' \"$?\"\n")
        append("printf '\\n%s:0\\n' '").append(marker).append("' >&2\n")
    }

    /** Exit code if [line] is the end marker, else null. */
    fun parseMarker(line: String, marker: String): Int? {
        if (!line.startsWith(marker)) return null
        return line.substring(marker.length).removePrefix(":").trim().toIntOrNull() ?: -1
    }

    /** The frame prints a newline before the marker so it always starts a line; drop exactly that one. */
    fun stripFrameNewline(sb: StringBuilder): String {
        // Lines were re-joined with '\n'; the frame's extra newline shows up as one trailing empty line.
        val s = sb.toString()
        return if (s.endsWith("\n\n")) s.dropLast(1) else if (s == "\n") "" else s
    }
}
