package atom.context.sources

import android.app.ActivityManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.os.storage.StorageManager
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolResult
import atom.core.tool.optInt
import atom.core.tool.schema
import atom.device.ShellTier
import atom.device.Shells
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Parses the per-process totals from `dumpsys meminfo`. */
object MemInfo {
    data class Proc(val name: String, val pid: Int, val kb: Long)

    private val line = Regex("^\\s*([\\d,]+)K: (\\S+) \\(pid (\\d+)")

    fun parse(out: String): List<Proc> {
        val section = out.substringAfter("Total PSS by process:", out.substringAfter("Total RSS by process:", ""))
            .substringBefore("Total PSS by OOM adjustment:").substringBefore("Total RSS by OOM adjustment:")
        return section.lineSequence().mapNotNull { l ->
            line.find(l)?.let { m -> Proc(m.groupValues[2], m.groupValues[3].toInt(), m.groupValues[1].replace(",", "").toLong()) }
        }.distinctBy { it.pid }.sortedByDescending { it.kb }.toList()
    }
}

internal class RankingTools(context: Context) {
    private val app = context.applicationContext

    fun all(): List<Tool> = listOf(memory(), storage())

    private fun memory() = CtxTool(
        name = "memory_ranking",
        description = "Memory use: total, free, pressure (PSI) and the top processes by memory (top list needs Shizuku or root).",
        risk = Risk.Read,
        parameters = schema { integer("top", "How many processes, default 12.") },
    ) { args, _ ->
        val top = (args.optInt("top") ?: 12).coerceIn(3, 40)
        val am = app.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val lines = mutableListOf(
            "memory: ${mi.availMem / MB} MB available of ${mi.totalMem / MB} MB (low-memory threshold ${mi.threshold / MB} MB${if (mi.lowMemory) ", LOW NOW" else ""})",
        )
        val sh = Shells.detect(app)
        val psi = withContext(Dispatchers.IO) { runCatching { File("/proc/pressure/memory").readText() }.getOrNull() }
            ?: if (sh.tier != ShellTier.App) sh.run("cat /proc/pressure/memory", 5_000).stdout.takeIf { it.isNotBlank() } else null
        psi?.lines()?.filter { it.isNotBlank() }?.let { lines += "pressure: " + it.joinToString("; ") { l -> l.substringBefore(" total=") } }
        if (sh.tier != ShellTier.App) {
            val procs = MemInfo.parse(sh.run("dumpsys meminfo", 45_000).stdout)
            if (procs.isNotEmpty()) {
                lines += "top processes:"
                procs.take(top).forEach { lines += "  ${it.kb / 1024} MB  ${it.name} (pid ${it.pid})" }
            }
        } else {
            lines += "(Per-process ranking needs Shizuku or root; Android hides other apps' processes.)"
        }
        ToolResult.ok(lines.joinToString("\n"))
    }

    private fun storage() = CtxTool(
        name = "storage_ranking",
        description = "Storage: free space and the apps using the most (app, data and cache sizes). Needs usage access.",
        risk = Risk.Read,
        parameters = schema { integer("top", "How many apps, default 15.") },
    ) { args, _ ->
        guarded {
            if (!Usage.granted(app)) throw Missing("Per-app storage needs usage access (Permissions page).")
            val top = (args.optInt("top") ?: 15).coerceIn(3, 50)
            val ssm = app.getSystemService(StorageStatsManager::class.java) ?: throw Missing("Storage stats unavailable.")
            val rows = withContext(Dispatchers.IO) {
                val uuid = StorageManager.UUID_DEFAULT
                val total = ssm.getTotalBytes(uuid)
                val free = ssm.getFreeBytes(uuid)
                val pm = app.packageManager
                val user = Process.myUserHandle()
                val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA).mapNotNull { ai ->
                    runCatching {
                        val s = ssm.queryStatsForPackage(uuid, ai.packageName, user)
                        Triple(pm.getApplicationLabel(ai).toString() + " (${ai.packageName})", s, s.appBytes + s.dataBytes)
                    }.getOrNull()
                }.sortedByDescending { it.third }.take(top)
                Triple(total, free, apps)
            }
            val (total, free, apps) = rows
            ToolResult.ok(
                buildString {
                    append("storage: ${gb(free)} free of ${gb(total)}\n")
                    append("largest apps (app + data, of which cache):\n")
                    apps.forEach { (name, s, sum) -> append("  ${gb(sum)}  $name — app ${gb(s.appBytes)}, data ${gb(s.dataBytes)}, cache ${gb(s.cacheBytes)}\n") }
                }.trimEnd(),
            )
        }
    }

    private fun gb(b: Long) = if (b >= 1L shl 30) String.format(Locale.US, "%.1f GB", b / (1L shl 30).toDouble()) else "${b / (1L shl 20)} MB"

    private companion object {
        const val MB = 1024L * 1024
    }
}
