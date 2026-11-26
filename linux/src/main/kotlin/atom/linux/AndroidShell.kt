package atom.linux

import android.content.Context
import android.os.Process
import atom.core.io.deleteTreeNoFollow
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

    /**
     * Where the root shell's BusyBox applet links live: an app-private folder outside the cache and the agent's
     * workspaces, which no tool writes to. In the cache, a redirect there would follow those links as root.
     */
    internal fun appletDir(app: Context): String {
        retireCacheApplets(app)
        return File(app.noBackupFilesDir, APPLET_DIR).path
    }

    const val APPLET_DIR = "root-busybox"

    @Volatile
    private var retired = false

    /** 4.0.5 and earlier kept the links in the cache; remove that copy once. */
    private fun retireCacheApplets(app: Context) {
        if (retired) return
        retired = true
        runCatching { File(app.cacheDir, APPLET_DIR).deleteTreeNoFollow() }
    }
}
