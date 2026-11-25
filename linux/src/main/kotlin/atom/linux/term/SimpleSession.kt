package atom.linux.term

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Simple mode: a real interactive shell on a pty whose output is split into command blocks by [SimpleTranscript].
 * Input typed while a command runs goes to that command (REPLs, prompts); otherwise it starts a new block.
 */
class SimpleSession internal constructor(cols: Int) {
    private val transcript = SimpleTranscript(cols)
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val scheduled = AtomicBoolean(false)
    @Volatile private var lastPublish = 0L

    lateinit var terminal: TerminalSession
        internal set

    private val _blocks = MutableStateFlow<List<SimpleBlock>>(emptyList())
    val blocks: StateFlow<List<SimpleBlock>> = _blocks.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** Hooked into the pty reader thread. */
    internal fun onOutput(bytes: ByteArray, length: Int) {
        synchronized(lock) { transcript.feed(bytes, 0, length) }
        val now = System.currentTimeMillis()
        val wait = 16 - (now - lastPublish)
        if (wait <= 0) publish() else if (scheduled.compareAndSet(false, true)) {
            scope.launch {
                delay(wait)
                scheduled.set(false)
                publish()
            }
        }
    }

    private fun publish() {
        lastPublish = System.currentTimeMillis()
        synchronized(lock) {
            _blocks.value = transcript.snapshot()
            _ready.value = transcript.isReady
        }
    }

    internal fun attach(session: TerminalSession) {
        terminal = session
        // Turn the prompt into the block marker; this line's own output is dropped by the transcript.
        session.write(SimpleTranscript.SHELL_SETUP + "\r")
    }

    val running: Boolean get() = synchronized(lock) { transcript.isRunning }

    /** Runs [line] as a new command, or sends it as input when a command is still running. Returns the block id. */
    fun send(line: String): Int? {
        val id = synchronized(lock) { if (transcript.isRunning) null else transcript.submit(line) }
        terminal.write(line + "\r")
        publish()
        return id
    }

    fun interrupt() = terminal.writeBytes(byteArrayOf(3))

    fun resize(cols: Int, rows: Int) {
        synchronized(lock) { transcript.resize(cols) }
        terminal.resize(cols, rows)
    }

    fun clear() {
        synchronized(lock) { transcript.clear() }
        publish()
    }

    suspend fun awaitReady(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) { ready.first { it } } ?: false

    /** Waits until block [id] finished, or [timeoutMs]; returns its latest state. */
    suspend fun awaitBlock(id: Int, timeoutMs: Long): SimpleBlock? {
        withTimeoutOrNull(timeoutMs) { blocks.first { list -> list.firstOrNull { it.id == id }?.running == false } }
        return blocks.value.firstOrNull { it.id == id }
    }

    /** Waits until output stops changing for [quietMs] (or [timeoutMs]); for input sent to a running program. */
    suspend fun awaitQuiet(quietMs: Long, timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                val before = blocks.value
                val changed = withTimeoutOrNull(quietMs) { blocks.first { it !== before } }
                if (changed == null) break
            }
        }
    }

    internal fun dispose() = scope.cancel()
}
