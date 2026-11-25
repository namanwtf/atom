package atom.linux

import android.content.Context
import android.os.Process
import atom.linux.launch.LaunchCommands
import atom.linux.launch.RootBusyBox
import atom.linux.term.PtySession
import atom.linux.term.TerminalSession
import java.io.File

/**
 * A terminal on Android itself: /system/bin/sh as the app user, or a root shell through `su` with the root
 * manager's BusyBox (Magisk, KernelSU or APatch) on PATH when there is one.
 */
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
        val argv = if (root) listOf("su", "-c", RootBusyBox.interactive(appletDir(app), Process.myUid())) else listOf("/system/bin/sh")
        return PtySession.start(argv, env, app.filesDir.path, cols, rows, onOutput = onOutput)
    }

    /** `su` arguments that run [command] as root with BusyBox on PATH. */
    fun rootArgv(context: Context, command: String): List<String> =
        listOf("su", "-c", RootBusyBox.wrap(command, appletDir(context.applicationContext), Process.myUid()))

    private fun appletDir(app: Context): String = File(app.cacheDir, "root-busybox").path
}
