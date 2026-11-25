package atom.linux

import atom.linux.install.InstallScripts

/** Result of a health check: one line per area, worst first in [overall]. */
data class HealthReport(val items: List<Item>, val checkedAtMs: Long) {
    enum class Level { Ok, Warning, Problem }

    data class Item(val key: String, val title: String, val level: Level, val detail: String)

    val overall: Level get() = items.maxOfOrNull { it.level } ?: Level.Ok

    /** True when Repair can fix something found here. */
    val repairable: Boolean get() = items.any { it.level != Level.Ok && it.key in REPAIRABLE }

    companion object {
        val REPAIRABLE = setOf("core", "tools", "workspace", "packages", "dns")
    }
}

/** The in-distro probe and its evaluation. The evaluation is pure, so it is unit-tested. */
object HealthCheck {
    private val CORE = listOf("sh", "ls", "cat", "env", "mkdir", "rm", "tar", "grep")

    /** Prints KEY=VALUE lines; runs as root inside the distro. */
    fun script(distro: Distro): String = buildString {
        appendLine("for c in ${CORE.joinToString(" ")}; do command -v \$c >/dev/null 2>&1 || echo MISSING_CORE=\$c; done")
        appendLine("for c in ${InstallScripts.BASE_COMMANDS.joinToString(" ")}; do command -v \$c >/dev/null 2>&1 || echo MISSING_TOOL=\$c; done")
        appendLine("if [ -d /workspace ] && touch /workspace/.atom-health 2>/dev/null; then rm -f /workspace/.atom-health; echo WORKSPACE=ok; else echo WORKSPACE=bad; fi")
        appendLine("if ls /sdcard >/dev/null 2>&1; then echo SDCARD=ok; else echo SDCARD=bad; fi")
        appendLine("if [ -s /etc/resolv.conf ]; then echo RESOLV=ok; else echo RESOLV=bad; fi")
        when (distro) {
            Distro.Debian -> appendLine("n=\$(dpkg --audit 2>/dev/null | grep -c .); echo DPKG_AUDIT=\$n")
            Distro.Alpine -> appendLine("apk audit --system >/dev/null 2>&1; echo APK_OK=\$?")
        }
        appendLine("echo DONE=1")
    }

    /**
     * Builds the report from the probe's output. [probeExit] is the probe's exit code (non-zero with no DONE line
     * means Linux did not start at all). [freeBytes] is the free space where the install lives.
     */
    fun evaluate(
        output: String,
        probeExit: Int,
        freeBytes: Long,
        toolsInstalled: Boolean,
        sharedFolders: List<SharedFolder>,
        unreadableFolders: List<SharedFolder>,
        now: Long = System.currentTimeMillis(),
    ): HealthReport {
        val lines = output.lines().mapNotNull { l -> l.trim().split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
        val values = lines.groupBy({ it.first }, { it.second })
        val items = ArrayList<HealthReport.Item>()
        val started = values["DONE"] != null

        if (!started) {
            items += HealthReport.Item("start", "Linux starts", HealthReport.Level.Problem, "The environment did not start (exit $probeExit).")
            items += spaceItem(freeBytes)
            return HealthReport(items, now)
        }
        items += HealthReport.Item("start", "Linux starts", HealthReport.Level.Ok, "Started and ran the checks.")

        val missingCore = values["MISSING_CORE"].orEmpty()
        items += if (missingCore.isEmpty()) {
            HealthReport.Item("core", "Core commands", HealthReport.Level.Ok, "All present.")
        } else {
            HealthReport.Item("core", "Core commands", HealthReport.Level.Problem, "Missing: ${missingCore.joinToString(", ")}")
        }

        val missingTools = values["MISSING_TOOL"].orEmpty()
        items += when {
            !toolsInstalled -> HealthReport.Item("tools", "Base tools", HealthReport.Level.Warning, "Not installed yet.")
            missingTools.isEmpty() -> HealthReport.Item("tools", "Base tools", HealthReport.Level.Ok, "All present.")
            else -> HealthReport.Item("tools", "Base tools", HealthReport.Level.Problem, "Missing: ${missingTools.joinToString(", ")}")
        }

        items += if (values["WORKSPACE"]?.firstOrNull() == "ok") {
            HealthReport.Item("workspace", "/workspace", HealthReport.Level.Ok, "Mounted and writable.")
        } else {
            HealthReport.Item("workspace", "/workspace", HealthReport.Level.Problem, "Not mounted or not writable.")
        }

        items += if (values["SDCARD"]?.firstOrNull() == "ok") {
            HealthReport.Item("sdcard", "/sdcard", HealthReport.Level.Ok, "Shared storage is readable.")
        } else {
            HealthReport.Item("sdcard", "/sdcard", HealthReport.Level.Warning, "Shared storage is not readable. Allow file access on the Workspace page.")
        }

        if (sharedFolders.isNotEmpty()) {
            items += if (unreadableFolders.isEmpty()) {
                HealthReport.Item("folders", "Shared folders", HealthReport.Level.Ok, "${sharedFolders.size} available.")
            } else {
                HealthReport.Item("folders", "Shared folders", HealthReport.Level.Warning, "Not readable: ${unreadableFolders.joinToString(", ") { it.name }}")
            }
        }

        items += if (values["RESOLV"]?.firstOrNull() == "ok") {
            HealthReport.Item("dns", "Name resolution", HealthReport.Level.Ok, "resolv.conf present.")
        } else {
            HealthReport.Item("dns", "Name resolution", HealthReport.Level.Problem, "resolv.conf is missing or empty.")
        }

        values["DPKG_AUDIT"]?.firstOrNull()?.toIntOrNull()?.let { n ->
            items += if (n == 0) {
                HealthReport.Item("packages", "Packages", HealthReport.Level.Ok, "No broken packages.")
            } else {
                HealthReport.Item("packages", "Packages", HealthReport.Level.Problem, "dpkg reports half-installed packages; Repair recovers them.")
            }
        }
        values["APK_OK"]?.firstOrNull()?.let { code ->
            items += if (code == "0") {
                HealthReport.Item("packages", "Packages", HealthReport.Level.Ok, "Package database is consistent.")
            } else {
                HealthReport.Item("packages", "Packages", HealthReport.Level.Warning, "apk reports changed system files; Repair runs apk fix.")
            }
        }
        items += spaceItem(freeBytes)
        return HealthReport(items, now)
    }

    const val LOW_SPACE = 500_000_000L
    const val CRITICAL_SPACE = 100_000_000L

    private fun spaceItem(free: Long): HealthReport.Item {
        val mb = free / 1_000_000
        return when {
            free < CRITICAL_SPACE -> HealthReport.Item("space", "Free space", HealthReport.Level.Problem, "Only $mb MB free; installs and updates will fail.")
            free < LOW_SPACE -> HealthReport.Item("space", "Free space", HealthReport.Level.Warning, "$mb MB free; large packages may not fit.")
            else -> HealthReport.Item("space", "Free space", HealthReport.Level.Ok, "$mb MB free.")
        }
    }
}
