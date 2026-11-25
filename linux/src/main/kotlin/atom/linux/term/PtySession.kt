package atom.linux.term

import atom.linux.pty.Pty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A program on a pty, rendered by a [TerminalEmulator].
 *
 * One dedicated thread blocks in poll(2)/read(2) on the master fd, so an idle session uses no CPU. Output is
 * fed to the emulator at once, but snapshots are published at most every [FRAME_MS] ms. Writes go through a
 * channel to an IO coroutine so a full pty buffer never blocks the UI thread.
 *
 * Teardown: [close] (or the program exiting) signals a wake pipe; the reader then closes the master, which
 * makes the kernel hang up the whole session, including root children we may not signal ourselves.
 */
internal class PtySession private constructor(
    private val fd: Int,
    private val pid: Int,
    private val wakeRead: Int,
    private val wakeWrite: Int,
    cols: Int,
    rows: Int,
    /** Runs once after the terminal is gone (e.g. chroot cleanup of anything that survived the hangup). */
    private val onExit: (() -> Unit)?,
    /** Sees every raw output chunk on the reader thread (Simple mode parses blocks from it). */
    private val onOutput: ((ByteArray, Int) -> Unit)?,
) : TerminalSession {

    private val emulator = TerminalEmulator(cols, rows)
    private val lock = Any()
    private val fdLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writes = Channel<ByteArray>(Channel.UNLIMITED)
    private val closed = AtomicBoolean(false)
    private val woken = AtomicBoolean(false)
    @Volatile private var fdOpen = true
    private val publishScheduled = AtomicBoolean(false)
    @Volatile private var lastPublish = 0L

    private val _screen = MutableStateFlow(emulator.snapshot())
    override val screen: StateFlow<TerminalScreen> = _screen.asStateFlow()

    private val _exitCode = MutableStateFlow<Int?>(null)
    override val exitCode: StateFlow<Int?> = _exitCode.asStateFlow()

    private fun start() {
        Thread({ readLoop() }, "atom-pty-read-$pid").apply { isDaemon = true }.start()
        Thread({ waitLoop() }, "atom-pty-wait-$pid").apply { isDaemon = true }.start()
        scope.launch {
            for (bytes in writes) {
                val ok = synchronized(fdLock) { fdOpen && Pty.write(fd, wakeRead, bytes, 0, bytes.size) == 0 }
                if (!ok) break
            }
        }
    }

    private fun readLoop() {
        val buf = ByteArray(16384)
        while (true) {
            val n = Pty.read(fd, wakeRead, buf, buf.size)
            if (n <= 0) break
            onOutput?.invoke(buf, n)
            val reply: String?
            synchronized(lock) {
                emulator.process(buf, 0, n)
                reply = emulator.takeResponses()
            }
            if (reply != null) writeBytes(reply.toByteArray(Charsets.UTF_8))
            schedulePublish()
        }
        publishNow()
        // Release a writer stuck on a full buffer, then close: the hangup reaches every process on the pty.
        wake()
        synchronized(fdLock) {
            fdOpen = false
            Pty.close(fd)
        }
        writes.close()
        Pty.close(wakeRead)
        Pty.close(wakeWrite)
        try {
            onExit?.invoke()
        } catch (_: Throwable) {
            // cleanup is best effort
        }
        scope.cancel()
    }

    private fun waitLoop() {
        val code = Pty.waitFor(pid)
        _exitCode.value = code
        // A background job may still hold the terminal open; the session ends with its program.
        scope.launch {
            delay(100) // let the reader drain the last output first
            wake()
        }
    }

    private fun wake() {
        if (woken.compareAndSet(false, true)) Pty.wake(wakeWrite)
    }

    private fun schedulePublish() {
        val now = System.currentTimeMillis()
        val wait = FRAME_MS - (now - lastPublish)
        if (wait <= 0) {
            publishNow()
            return
        }
        if (publishScheduled.compareAndSet(false, true)) {
            scope.launch {
                delay(wait)
                publishScheduled.set(false)
                publishNow()
            }
        }
    }

    private fun publishNow() {
        lastPublish = System.currentTimeMillis()
        _screen.value = synchronized(lock) { emulator.snapshot() }
    }

    override fun write(text: String) {
        if (text.isNotEmpty()) writeBytes(text.toByteArray(Charsets.UTF_8))
    }

    override fun writeBytes(bytes: ByteArray) {
        if (!closed.get() && bytes.isNotEmpty()) writes.trySend(bytes)
    }

    override fun resize(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0 || closed.get()) return
        synchronized(lock) {
            if (cols == emulator.columns && rows == emulator.rows) return
            emulator.resize(cols, rows)
        }
        synchronized(fdLock) { if (fdOpen) Pty.setSize(fd, rows, cols) } // kernel sends SIGWINCH
        publishNow()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // SIGHUP first so shells can exit cleanly, then drop the terminal; stragglers get SIGKILL.
        // (Only while the child is unreaped: afterwards its pid could belong to someone else.)
        if (_exitCode.value == null) Pty.signal(pid, Pty.SIGHUP, group = true)
        wake()
        Thread({
            Thread.sleep(500)
            if (_exitCode.value == null) Pty.signal(pid, Pty.SIGKILL, group = true)
        }, "atom-pty-reap-$pid").apply { isDaemon = true }.start()
    }

    companion object {
        private const val FRAME_MS = 16L

        /**
         * Starts [argv] (argv[0] is the program; a bare name is searched in PATH) with exactly [env]
         * as its environment.
         */
        fun start(
            argv: List<String>,
            env: Map<String, String>,
            cwd: String?,
            cols: Int,
            rows: Int,
            onExit: (() -> Unit)? = null,
            onOutput: ((ByteArray, Int) -> Unit)? = null,
        ): TerminalSession {
            require(argv.isNotEmpty())
            val c = cols.coerceIn(1, 1000)
            val r = rows.coerceIn(1, 1000)
            val result = IntArray(3)
            val envp = env.map { (k, v) -> "$k=$v" }.toTypedArray()
            val fd = Pty.spawn(argv[0], argv.toTypedArray(), envp, cwd, r, c, result)
            return PtySession(fd, result[0], result[1], result[2], c, r, onExit, onOutput).also { it.start() }
        }
    }
}
