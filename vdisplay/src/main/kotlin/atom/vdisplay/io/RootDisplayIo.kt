package atom.vdisplay.io

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import atom.device.ShellExecutor
import atom.device.ShellResult
import atom.device.ShellTier
import atom.device.Shells
import atom.device.root.RootAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Root input and launching on a given display through Atom's shared root shell (one `su` process, reused).
 * Each method returns null on success or a short error for the agent.
 */
internal class RootDisplayIo(private val context: Context) {
    private suspend fun root(): ShellExecutor? {
        if (!RootAccess.granted && !RootAccess.ensure(context)) return null
        val sh = Shells.detect(context)
        return sh.takeIf { it.tier == ShellTier.Root }
    }

    suspend fun run(command: String, timeoutMs: Long = 15_000): ShellResult {
        val sh = root() ?: return ShellResult(-1, "", "Root is not available.")
        return sh.run(command, timeoutMs)
    }

    private fun ShellResult.problem(what: String): String? {
        // `am start` prints "Error:" / "Warning: Activity not started" but may exit 0.
        val text = (stdout + "\n" + stderr).trim()
        val failed = !ok || text.contains("Error", ignoreCase = false) || text.contains("Exception")
        return if (failed) "$what failed: ${text.lines().firstOrNull { it.isNotBlank() } ?: "exit $exitCode"}" else null
    }

    /** Launches [packageName]'s launcher activity on [displayId]. */
    suspend fun launch(displayId: Int, packageName: String): String? {
        val component = launcherComponent(packageName) ?: return "$packageName has no launcher activity."
        return run(Commands.startComponent(displayId, component)).problem("Launching $packageName")
    }

    suspend fun openUri(displayId: Int, uri: String, packageName: String?): String? =
        run(Commands.startView(displayId, uri, packageName)).problem("Opening the link")

    suspend fun tap(displayId: Int, x: Int, y: Int, long: Boolean): String? =
        run(if (long) Commands.longPress(displayId, x, y) else Commands.tap(displayId, x, y)).problem("Tap")

    suspend fun swipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, ms: Long): String? =
        run(Commands.swipe(displayId, x1, y1, x2, y2, ms)).problem("Swipe")

    suspend fun key(displayId: Int, code: Int): String? = run(Commands.key(displayId, code)).problem("Key")

    /** Types into the focused field on [displayId]: key events for ASCII, clipboard + paste otherwise. */
    suspend fun type(displayId: Int, text: String): String? {
        Commands.text(displayId, text)?.let { return run(it).problem("Typing") }
        withContext(Dispatchers.Main) {
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Atom", text))
        }
        return key(displayId, Commands.KEY_PASTE)
    }

    suspend fun tasks(): List<TaskEntry> = TaskList.parse(run(Commands.STACK_LIST).stdout)

    suspend fun resumedPackage(displayId: Int): String? =
        TaskList.resumedPackage(run(Commands.RESUMED, 8_000).stdout, displayId)

    /** Runs [atom.vdisplay.display.RootHelper]; null on "ok". */
    suspend fun helper(vararg args: String): String? {
        val apk = context.applicationInfo.sourceDir
        val r = run(Commands.helper(apk, *args), 10_000)
        return if (r.stdout.lines().any { it.trim() == "ok" }) null else (r.stdout + " " + r.stderr).trim().ifEmpty { "exit ${r.exitCode}" }
    }

    fun launcherComponent(packageName: String): String? =
        context.packageManager.getLaunchIntentForPackage(packageName)?.component?.flattenToShortString()

    fun launcherPackages(): Set<String> {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
        return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .mapTo(HashSet()) { it.activityInfo.packageName }
    }
}
