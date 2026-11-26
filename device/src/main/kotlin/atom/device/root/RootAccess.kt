package atom.device.root

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
 * The one shared, observable root status. Every screen collects [status] and every gate reads [granted] when it
 * runs; nothing keeps its own copy.
 *
 * When to probe is [RootProbePolicy]'s decision: once in the background at app start (when the user chose root
 * before or a root manager is installed), again when Atom returns to the foreground while root is not granted
 * (the user may just have allowed Atom in the root manager), when a tool needs root and it was never checked, and
 * on Authorize / Check again. A superuser prompt the user refused is never shown again automatically.
 */
object RootAccess {
    const val PROBE_TIMEOUT_MS = 30_000L
    private const val PREFS = "atom4_root"
    private const val KEY = "status"
    private const val KEY_CHOSEN = "chosen"
    private const val KEY_STRIKES = "strikes"

    private val _status = MutableStateFlow(RootStatus.Unknown)
    val status: StateFlow<RootStatus> = _status.asStateFlow()

    private val mutex = Mutex()

    @Volatile
    private var memory = RootMemory()

    @Volatile
    private var verifiedThisProcess = false

    @Volatile
    private var probing = false

    /** Elapsed-realtime of the last finished probe in this process; null when none finished. */
    @Volatile
    private var lastProbeAt: Long? = null

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

    /** Root manager apps (Magisk and its forks, KernelSU and its forks, APatch). KernelSU hides su from apps it has not allowed. */
    private val managerPackages = listOf(
        "com.topjohnwu.magisk", "io.github.vvb2060.magisk", "io.github.huskydg.magisk",
        "me.weishu.kernelsu", "com.rifsxd.ksunext", "com.sukisu.ultra",
        "me.bmax.apatch", "me.garfieldhan.apatch.next",
    )

    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val saved = prefs.getString(KEY, null)?.let { runCatching { RootStatus.valueOf(it) }.getOrNull() } ?: RootStatus.Unknown
            val status = if (saved == RootStatus.Checking) RootStatus.Unknown else saved
            memory = RootMemory(
                status = status,
                // Installs from before "chosen" was stored: a saved grant means the user chose root.
                chosen = prefs.getBoolean(KEY_CHOSEN, status == RootStatus.Granted),
                strikes = prefs.getInt(KEY_STRIKES, 0),
            )
            if (_status.value == RootStatus.Unknown) _status.value = status
            loaded = true
        }
    }

    /** True if Atom may use root now. Probes only when that cannot cause a repeated prompt. */
    suspend fun ensure(context: Context): Boolean {
        refresh(context, ProbeTrigger.ToolUse)
        // A check already running (at app start, say) decides for this call too.
        if (_status.value == RootStatus.Checking) status.first { it != RootStatus.Checking }
        return granted
    }

    /** The user's explicit Authorize / Check again. Waits for a check already running instead of starting another. */
    suspend fun authorize(context: Context): RootStatus {
        load(context)
        if (probing) return status.first { it != RootStatus.Checking }
        return probe(context, ProbeTrigger.Manual)
    }

    /**
     * Probes if [RootProbePolicy] says [trigger] should, and returns the status afterwards. Cheap when it does not:
     * a few file and package checks, off the main thread.
     */
    suspend fun refresh(context: Context, trigger: ProbeTrigger): RootStatus {
        load(context)
        // Looking for a root manager only when the decision depends on it.
        val present = decide(trigger, rootManagerPresent = true) && withContext(Dispatchers.IO) { rootManagerPresent(context) }
        return if (decide(trigger, present)) probe(context, trigger, present) else _status.value
    }

    private fun decide(trigger: ProbeTrigger, rootManagerPresent: Boolean, probingNow: Boolean = probing): Boolean =
        RootProbePolicy.shouldProbe(
            trigger, memory, probingNow, verifiedThisProcess, rootManagerPresent,
            sinceLastProbeMs = lastProbeAt?.let { SystemClock.elapsedRealtime() - it },
        )

    val granted: Boolean get() = _status.value == RootStatus.Granted

    private fun suBinaryPresent(): Boolean = suPaths.any { File(it).exists() } ||
        System.getenv("PATH").orEmpty().split(':').any { it.isNotBlank() && File(it, "su").exists() }

    private fun rootManagerPresent(context: Context): Boolean = suBinaryPresent() || managerPackages.any { pkg ->
        runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
    }

    private suspend fun probe(context: Context, trigger: ProbeTrigger, rootManagerPresent: Boolean = false): RootStatus = mutex.withLock {
        // An automatic check that waited for another one decides again on what that one found, so the app-start and
        // the first foreground check never show two prompts.
        if (trigger != ProbeTrigger.Manual && !decide(trigger, rootManagerPresent, probingNow = false)) return _status.value
        probing = true
        try {
            _status.value = RootStatus.Checking
            val started = SystemClock.elapsedRealtime()
            val result = withContext(Dispatchers.IO) { runProbe() }
            val took = SystemClock.elapsedRealtime() - started
            if (result == RootStatus.Granted) missingTools = withContext(Dispatchers.IO) { findMissingTools() }
            val next = RootProbePolicy.afterProbe(memory, trigger, result, took)
            memory = next
            verifiedThisProcess = true
            lastProbeAt = SystemClock.elapsedRealtime()
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, next.status.name)
                .putBoolean(KEY_CHOSEN, next.chosen)
                .putInt(KEY_STRIKES, next.strikes)
                .apply()
            _status.value = result
            result
        } finally {
            probing = false
            // Cancelled mid-probe: show the last known result instead of a check that never ends.
            if (_status.value == RootStatus.Checking) _status.value = memory.status
        }
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
            "APatch. Atom asks once; approve the superuser prompt within 30 seconds. If you allow Atom in the root " +
            "manager's list later, Atom notices when you come back to it. If you denied the prompt, tap Check again. Some tools need standard command-line applets (cp, chmod, iptables); " +
            "if any are listed as missing, install the root manager's BusyBox module."
}
