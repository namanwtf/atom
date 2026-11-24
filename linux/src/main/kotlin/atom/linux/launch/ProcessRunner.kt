package atom.linux.launch

import android.os.ParcelFileDescriptor
import atom.linux.CommandResult
import atom.linux.pty.Pty
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream

/** Runs a program without a terminal, in its own process group, with a timeout and capped output. */
internal object ProcessRunner {

    /**
     * Runs [spec]. On timeout or cancellation the process group gets SIGTERM, then SIGKILL, and [onKill] runs
     * (used for root processes the app may not signal itself).
     */
    suspend fun run(spec: LaunchSpec, timeoutMs: Long, maxChars: Int = 64_000, onKill: (() -> Unit)? = null): CommandResult =
        withContext(Dispatchers.IO) {
            val ids = IntArray(3)
            val envp = spec.env.map { (k, v) -> "$k=$v" }.toTypedArray()
            try {
                Pty.spawnPiped(spec.argv[0], spec.argv.toTypedArray(), envp, spec.cwd, ids)
            } catch (e: java.io.IOException) {
                return@withContext CommandResult(-1, "", "Could not start ${spec.argv[0]}: ${e.message}")
            }
            val pid = ids[0]
            val stdout = ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(ids[1]))
            val stderr = ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(ids[2]))
            val reaped = java.util.concurrent.atomic.AtomicBoolean(false)
            fun closeStreams() {
                // Closing unblocks the reader threads (libcore wakes reads blocked on a closed fd).
                runCatching { stdout.close() }
                runCatching { stderr.close() }
            }
            fun killGroup(sig: Int) {
                if (!reaped.get()) Pty.signal(pid, sig, group = true)
            }
            try {
                kotlin.run {
                    // Plain threads, not child coroutines: blocking native calls must never hold up cancellation.
                    val out = onThread("out") { readCapped(stdout, maxChars) }
                    val err = onThread("err") { readCapped(stderr, maxChars) }
                    val exit = onThread("wait") { Pty.waitFor(pid).also { reaped.set(true) } }
                    var timedOut = false
                    var code = withTimeoutOrNull(timeoutMs) { exit.await() }
                    if (code == null) {
                        timedOut = true
                        // proot handles SIGTERM by killing its tracees (--kill-on-exit); then make sure.
                        killGroup(Pty.SIGTERM)
                        if (withTimeoutOrNull(1_000) { exit.await() } == null) killGroup(Pty.SIGKILL)
                        onKill?.invoke()
                        code = withTimeoutOrNull(3_000) { exit.await() } ?: 124
                    }
                    // The pipes reach EOF once every writer is gone; give background jobs a moment, no more.
                    val o = withTimeoutOrNull(2_000) { out.await() }
                    val e = withTimeoutOrNull(2_000) { err.await() }
                    closeStreams()
                    CommandResult(if (timedOut && code == 0) 124 else code, o ?: "", e ?: "", timedOut)
                }
            } catch (e: CancellationException) {
                killGroup(Pty.SIGKILL)
                onKill?.invoke()
                throw e
            } finally {
                closeStreams()
            }
        }

    private fun <T> onThread(name: String, block: () -> T): Deferred<T> {
        val d = CompletableDeferred<T>()
        Thread({
            try {
                d.complete(block())
            } catch (t: Throwable) {
                d.completeExceptionally(t)
            }
        }, "atom-run-$name").apply { isDaemon = true }.start()
        return d
    }

    /** Reads to EOF, keeping the first [maxChars] bytes and noting how much was dropped. */
    private fun readCapped(input: InputStream, maxChars: Int): String {
        val keep = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var dropped = 0L
        while (true) {
            val n = try {
                input.read(buf)
            } catch (e: java.io.IOException) {
                -1
            }
            if (n < 0) break
            val room = (maxChars - keep.size()).coerceAtLeast(0)
            if (room > 0) keep.write(buf, 0, minOf(room, n))
            if (n > room) dropped += n - room
        }
        val text = keep.toString(Charsets.UTF_8.name())
        return if (dropped > 0) "$text\n… [$dropped more bytes cut]" else text
    }
}
