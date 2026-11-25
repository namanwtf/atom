package atom.vdisplay.io

/** One task from `am stack list`. */
data class TaskEntry(val rootTaskId: Int, val taskId: Int, val displayId: Int, val component: String, val visible: Boolean?) {
    val packageName: String get() = component.substringBefore('/')
}

/** Parsers for activity-manager dumps. Tolerant: unknown lines are skipped, never thrown on. */
object TaskList {
    private val rootLine = Regex("""^\s*(?:RootTask|Stack|Task)\s+id=(\d+)\b.*?\bdisplayId=(\d+)""")
    private val taskLine = Regex("""^\s*taskId=(\d+):\s*([\w.$]+/[\w.$]+)(.*)$""")
    private val visibleAttr = Regex("""\bvisible=(true|false)\b""")

    /**
     * Parses `am stack list` (Android 10-17 formats):
     * ```
     * RootTask id=12 bounds=[0,0][1080,2400] displayId=0 userId=0
     *   taskId=12: com.foo/.Main bounds=[0,0][1080,2400] userId=0 visible=true topActivity=...
     * ```
     */
    fun parse(output: String): List<TaskEntry> {
        val out = ArrayList<TaskEntry>()
        var root = -1
        var display = -1
        for (line in output.lineSequence()) {
            rootLine.find(line)?.let {
                root = it.groupValues[1].toInt()
                display = it.groupValues[2].toInt()
                return@let
            }
            val t = taskLine.find(line) ?: continue
            if (display < 0) continue
            val visible = visibleAttr.find(t.groupValues[3])?.groupValues?.get(1)?.toBooleanStrictOrNull()
            out += TaskEntry(root, t.groupValues[1].toInt(), display, t.groupValues[2], visible)
        }
        return out
    }

    /** The task to present from [displayId]: the target package's task if there, else the top task. */
    fun pick(tasks: List<TaskEntry>, displayId: Int, packageName: String?): TaskEntry? {
        val on = tasks.filter { it.displayId == displayId }
        return on.firstOrNull { packageName != null && it.packageName == packageName } ?: on.firstOrNull()
    }

    private val displayHeader = Regex("""Display #(\d+)""")
    private val resumed = Regex("""ResumedActivity[:=]\s*ActivityRecord\{[^}]*?\s(?:u\d+\s+)?([\w.]+)/""")

    /** Package of the resumed activity on [displayId] from [Commands.RESUMED] output, or null. */
    fun resumedPackage(output: String, displayId: Int = 0): String? {
        var current = -1
        for (line in output.lineSequence()) {
            displayHeader.find(line)?.let { current = it.groupValues[1].toInt() }
            if (current != displayId) continue
            resumed.find(line)?.let { return it.groupValues[1] }
        }
        return null
    }
}
