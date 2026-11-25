package atom.device.root

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class RootStatus {
    /** Never probed on this install. */
    Unknown,
    Checking,
    Granted,
    NotDetected,

    /** The superuser prompt was not answered within [RootAccess.PROBE_TIMEOUT_MS]. */
    TimedOut,

    /** su exists but refused (denied in Magisk / KernelSU / APatch). */
    NotGranted,
    ;

    val final: Boolean get() = this != Unknown && this != Checking
}

/**
 * Root authorization, probed at most once per process on first use. A denied or unanswered prompt is remembered
 * across restarts and never re-asked automatically: only the user's Authorize / Check again does that.
 */
object RootAccess {
    const val PROBE_TIMEOUT_MS = 30_000L
    private const val PREFS = "atom4_root"
    private const val KEY = "status"

    private val _status = MutableStateFlow(RootStatus.Unknown)
    val status: StateFlow<RootStatus> = _status.asStateFlow()

    private val mutex = Mutex()

    @Volatile
    private var verifiedThisProcess = false

    @Volatile
    private var loaded = false

    /** Root helpers present after a grant; empty means the probe has not run yet. */
    @Volatile
    var missingTools: List<String> = emptyList()
        private set

    /** Common su locations, checked without spawning anything. */
    private val suPaths = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su", "/su/bin/su",
        "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su", "/data/adb/magisk/su",
    )

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val saved = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        val s = saved?.let { runCatching { RootStatus.valueOf(it) }.getOrNull() } ?: RootStatus.Unknown
        if (_status.value == RootStatus.Unknown) _status.value = if (s == RootStatus.Checking) RootStatus.Unknown else s
    }

    /** True if Atom may use root now. Probes only when that cannot cause a repeated prompt. */
    suspend fun ensure(context: Context): Boolean {
        load(context)
        if (verifiedThisProcess) return _status.value == RootStatus.Granted
        return when (_status.value) {
            RootStatus.Unknown, RootStatus.Granted -> probe(context) == RootStatus.Granted
            RootStatus.NotDetected -> if (suBinaryPresent()) probe(context) == RootStatus.Granted else false
            else -> false
        }
    }

    /** The user's explicit Authorize / Check again. */
    suspend fun authorize(context: Context): RootStatus {
        load(context)
        return probe(context)
    }

    val granted: Boolean get() = _status.value == RootStatus.Granted

    private fun suBinaryPresent(): Boolean = suPaths.any { File(it).exists() } ||
        System.getenv("PATH").orEmpty().split(':').any { it.isNotBlank() && File(it, "su").exists() }

    private suspend fun probe(context: Context): RootStatus = mutex.withLock {
        if (verifiedThisProcess && _status.value.final) return _status.value
        _status.value = RootStatus.Checking
        val result = withContext(Dispatchers.IO) { runProbe() }
        if (result == RootStatus.Granted) missingTools = withContext(Dispatchers.IO) { findMissingTools() }
        verifiedThisProcess = true
        _status.value = result
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, result.name).apply()
        result
    }

    private fun runProbe(): RootStatus {
        val p = try {
            ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        } catch (e: IOException) {
            return RootStatus.NotDetected
        } catch (e: SecurityException) {
            return RootStatus.NotDetected
        }
        return try {
            p.outputStream.close()
            if (!p.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
                return RootStatus.TimedOut
            }
            val out = p.inputStream.bufferedReader().readText()
            if (p.exitValue() == 0 && out.contains("uid=0")) RootStatus.Granted else RootStatus.NotGranted
        } catch (e: Exception) {
            p.destroyForcibly()
            RootStatus.NotGranted
        }
    }

    private fun findMissingTools(): List<String> {
        val wanted = listOf("cp", "chmod", "cat", "ls", "rm", "iptables", "ip6tables", "dumpsys", "logcat", "settings", "pm", "am")
        return try {
            val script = wanted.joinToString("; ") { "command -v $it >/dev/null 2>&1 || echo $it" }
            val p = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
            p.outputStream.close()
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return emptyList()
            }
            p.inputStream.bufferedReader().readLines().map { it.trim() }.filter { it in wanted }
        } catch (e: Exception) {
            emptyList()
        }
    }

    const val HELP =
        "Root comes from a root manager: Magisk, KernelSU (turn on metamodule support so su is mounted for apps) or " +
            "APatch. Atom asks once; approve the superuser prompt within 30 seconds. If you denied it, allow Atom in the " +
            "root manager's list, then tap Check again. Some tools need standard command-line applets (cp, chmod, iptables); " +
            "if any are listed as missing, install the root manager's BusyBox module."
}
