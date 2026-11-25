package atom.vdisplay.io

/**
 * Root shell commands for the hidden display. Pure string builders, unit tested; every argument that comes from
 * outside is single-quoted. All of these exist on Android 10+ (`input -d`, `am start --display`).
 */
object Commands {
    const val FLAG_NEW_TASK = 0x10000000
    const val WINDOWING_MODE_FULLSCREEN = 1

    const val KEY_BACK = 4
    const val KEY_HOME = 3
    const val KEY_ENTER = 66
    const val KEY_DEL = 67
    const val KEY_PASTE = 279
    const val KEY_APP_SWITCH = 187

    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** Starts [component] ("pkg/.Activity") on [displayId] in a new fullscreen task. */
    fun startComponent(displayId: Int, component: String): String =
        "am start --display $displayId --windowingMode $WINDOWING_MODE_FULLSCREEN -f 0x${FLAG_NEW_TASK.toString(16)} -n ${quote(component)}"

    /** Opens [uri] on [displayId], optionally in [packageName]. */
    fun startView(displayId: Int, uri: String, packageName: String?): String = buildString {
        append("am start --display ").append(displayId)
        append(" --windowingMode ").append(WINDOWING_MODE_FULLSCREEN)
        append(" -f 0x").append(FLAG_NEW_TASK.toString(16))
        append(" -a android.intent.action.VIEW -d ").append(quote(uri))
        if (!packageName.isNullOrBlank()) append(" -p ").append(quote(packageName))
    }

    /** Brings an existing task's top activity to [displayId] (used to present a hidden task on the main screen). */
    fun reorderToFront(displayId: Int, component: String): String =
        "am start --display $displayId --activity-reorder-to-front -n ${quote(component)}"

    fun tap(displayId: Int, x: Int, y: Int): String = "input -d $displayId tap $x $y"

    fun swipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): String =
        "input -d $displayId swipe $x1 $y1 $x2 $y2 ${durationMs.coerceIn(1, 10_000)}"

    fun longPress(displayId: Int, x: Int, y: Int, durationMs: Long = 650): String = swipe(displayId, x, y, x, y, durationMs)

    fun key(displayId: Int, keyCode: Int): String = "input -d $displayId keyevent $keyCode"

    /**
     * `input text` for printable ASCII only (it cannot type anything else); spaces become %s as `input` expects.
     * Returns null when [text] needs the clipboard + paste route instead.
     */
    fun text(displayId: Int, text: String): String? {
        if (text.isEmpty() || text.any { it.code < 0x20 || it.code > 0x7e }) return null
        return "input -d $displayId text " + quote(text.replace(" ", "%s"))
    }

    /** Lists root tasks with their display (Android 10+: `am stack list`). */
    const val STACK_LIST = "am stack list"

    /** Moves root task [rootTaskId] to [displayId], where the shell command still exists. */
    fun moveStack(rootTaskId: Int, displayId: Int): String = "am display move-stack $rootTaskId $displayId"

    /** Resumed activity per display, for "is the target already in front". */
    const val RESUMED = "dumpsys activity activities | grep -E 'Display #|ResumedActivity'"

    /** Runs [RootHelper][atom.vdisplay.display.RootHelper] from the installed APK as root. */
    fun helper(apkPath: String, vararg args: String): String =
        "CLASSPATH=${quote(apkPath)} app_process /system/bin atom.vdisplay.display.RootHelper " + args.joinToString(" ") { quote(it) }
}
