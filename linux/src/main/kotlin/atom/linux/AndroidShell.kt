package atom.linux

import android.content.Context
import atom.linux.launch.LaunchCommands
import atom.linux.term.PtySession
import atom.linux.term.TerminalSession

/** A terminal on Android itself: /system/bin/sh as the app user, or a root shell through `su`. */
object AndroidShell {
    fun openSession(
        context: Context,
        cols: Int,
        rows: Int,
        root: Boolean,
        onOutput: ((ByteArray, Int) -> Unit)? = null,
    ): TerminalSession {
        val app = context.applicationContext
        val env = LinkedHashMap(System.getenv()).apply {
            put("TERM", LaunchCommands.TERM)
            put("COLORTERM", "truecolor")
            put("HOME", app.filesDir.path)
            put("TMPDIR", app.cacheDir.path)
        }
        // `su` is looked up in PATH (Magisk and KernelSU both answer at /system/bin/su).
        val argv = if (root) listOf("su") else listOf("/system/bin/sh")
        return PtySession.start(argv, env, app.filesDir.path, cols, rows, onOutput = onOutput)
    }
}
