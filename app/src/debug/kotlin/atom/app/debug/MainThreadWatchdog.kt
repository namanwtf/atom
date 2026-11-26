package atom.app.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug builds only (src/debug). Logs the main thread's stack (tag AtomWatchdog) whenever the main thread has not
 * run a posted message for [STALL_MS], so a launch that hangs on the CI emulator shows where it is stuck. A Java
 * stack read like this also works when the emulator runs the app through ARM translation, where a SIGQUIT dump
 * crashes the runtime. Started from a content provider, so it is watching before Application.onCreate runs.
 */
class MainThreadWatchdog : ContentProvider() {
    override fun onCreate(): Boolean {
        val main = Handler(Looper.getMainLooper())
        val mainThread = Looper.getMainLooper().thread
        Thread({
            var reports = 0
            while (reports < MAX_REPORTS) {
                val ran = AtomicBoolean(false)
                val posted = SystemClock.uptimeMillis()
                main.post { ran.set(true) }
                Thread.sleep(STALL_MS)
                if (ran.get()) continue
                reports++
                val stack = mainThread.stackTrace.joinToString("\n") { "    at $it" }
                Log.w(TAG, "main thread has not run a message for ${SystemClock.uptimeMillis() - posted} ms (state ${mainThread.state}):\n$stack")
                while (!ran.get() && reports < MAX_REPORTS) {
                    Thread.sleep(STALL_MS)
                    if (!ran.get()) {
                        reports++
                        Log.w(TAG, "still stuck after ${SystemClock.uptimeMillis() - posted} ms:\n" + mainThread.stackTrace.take(40).joinToString("\n") { "    at $it" })
                    }
                }
            }
        }, "AtomWatchdog").apply { isDaemon = true }.start()
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        const val TAG = "AtomWatchdog"
        const val STALL_MS = 5_000L
        const val MAX_REPORTS = 12
    }
}
