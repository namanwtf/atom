package atom.linux

import android.annotation.SuppressLint
import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import atom.linux.install.LinuxInstallWorker
import atom.linux.install.RootfsSetup
import atom.linux.launch.ChrootConfig
import atom.linux.launch.LaunchCommands
import atom.linux.launch.ProcessRunner
import atom.linux.launch.ProotConfig
import atom.linux.launch.RootSupport
import atom.linux.term.PtySession
import atom.linux.term.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.Properties
import java.util.UUID

/**
 * The on-device Linux environment: one installed distro, run with chroot (root) or proot (no root).
 *
 * Nothing runs while idle: there is no daemon or service. Each session or command is a process tree that
 * dies with it (proot --kill-on-exit; chroot sessions live in a private mount namespace that is emptied
 * and so unmounted when they end).
 */
class LinuxEnv private constructor(context: Context) {

    companion object {
        @SuppressLint("StaticFieldLeak") // application context only
        @Volatile
        private var instance: LinuxEnv? = null

        fun get(context: Context): LinuxEnv =
            instance ?: synchronized(this) { instance ?: LinuxEnv(context.applicationContext).also { instance = it } }

        /** Upper bound on captured stdout/stderr per stream for [run]. */
        const val OUTPUT_CAP = 64_000

        private const val TAG_PREFIX = "atom-linux-distro:"
    }

    private val app = context.applicationContext
    internal val paths = LinuxPaths(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val workManager by lazy { WorkManager.getInstance(app) }
    private val probeMutex = Mutex()
    private val uninstallMutex = Mutex()

    @Volatile private var probe: RootSupport.Probe? = null
    @Volatile private var lastWork: WorkInfo? = null

    private val _state = MutableStateFlow(computeState())
    val state: StateFlow<LinuxState> = _state.asStateFlow()

    init {
        scope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(LinuxInstallWorker.UNIQUE_NAME).collect { infos ->
                lastWork = infos.firstOrNull()
                _state.value = computeState()
            }
        }
    }

    // ================================================================ install / uninstall

    /** Starts (or keeps) the one-tap install. Ignored while an install is already running. */
    fun install(distro: Distro) {
        if (_state.value is LinuxState.Installing) return
        val request = OneTimeWorkRequestBuilder<LinuxInstallWorker>()
            .setInputData(workDataOf(LinuxInstallWorker.KEY_DISTRO to distro.name))
            .addTag(TAG_PREFIX + distro.name)
            .build()
        workManager.enqueueUniqueWork(LinuxInstallWorker.UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        _state.value = LinuxState.Installing(distro, LinuxInstallWorker.STEP_RESOLVE, 0f)
    }

    fun cancelInstall() {
        workManager.cancelUniqueWork(LinuxInstallWorker.UNIQUE_NAME)
    }

    /**
     * Removes the installed distro. In chroot mode every session process is killed first and the mount
     * table is checked (app and global namespace); nothing is deleted while anything is still mounted under
     * the install, and deletion never crosses file systems.
     */
    suspend fun uninstall() = uninstallMutex.withLock {
        val distro = installedDistro() ?: return@withLock
        try {
            withContext(Dispatchers.IO) {
                val dir = paths.distroDir(distro)
                val rootfs = paths.rootfs(distro)
                val p = probe ?: probeRoot()
                if (p.root) {
                    killAllChrootSessions()
                    unmountAllUnder(paths.base)
                    val left = mountsUnder(paths.base)
                    check(left.isEmpty()) {
                        "Linux is still mounted at ${left.first()}; not deleting anything. Reboot the phone, then uninstall."
                    }
                    // Files created as root inside the chroot cannot be removed by the app itself.
                    RootSupport.su("rm -rf ${LaunchCommands.shQuote(rootfs.path)}", hostEnv(), 120_000)
                }
                paths.marker(distro).delete()
                check(SafeDelete.deleteTree(dir)) { "Some Linux files could not be removed." }
                SafeDelete.deleteTree(paths.guestTmp)
                SafeDelete.deleteTree(paths.prootTmp)
                SafeDelete.deleteTree(paths.fakeProc)
            }
        } finally {
            _state.value = computeState()
        }
    }

    // ================================================================ running

    /** Re-checks root/chroot support and disk use. Call when showing Linux UI; may prompt for root once. */
    suspend fun refresh() {
        probeRoot(force = true)
        val distro = installedDistro()
        if (distro != null) {
            val size = withContext(Dispatchers.IO) { diskUsage(paths.rootfs(distro)) }
            val props = readMarker(distro)
            if (props != null && props.getProperty("sizeBytes") != size.toString()) {
                props.setProperty("sizeBytes", size.toString())
                runCatching { paths.marker(distro).outputStream().use { props.store(it, "Atom Linux install") } }
            }
        }
        _state.value = computeState()
    }

    /**
     * Runs [command] non-interactively in a login shell inside the distro. stdout/stderr are captured
     * (each capped at [OUTPUT_CAP]); on timeout the whole process tree is killed.
     */
    suspend fun run(command: String, timeoutMs: Long = 120_000): CommandResult {
        val ready = _state.value as? LinuxState.Ready
            ?: return CommandResult(127, "", "Linux is not installed. Install it from Atom's Linux screen first.")
        val p = probe ?: probeRoot()
        val rootfs = paths.rootfs(ready.distro)
        prepareRuntime()
        return if (p.chrootCapable) {
            val token = newToken()
            val spec = LaunchCommands.chroot(chrootConfig(rootfs, token), command)
            try {
                ProcessRunner.run(spec, timeoutMs, OUTPUT_CAP) { scope.launch(Dispatchers.IO) { cleanupSession(token) } }
                    .let(::explainChrootFailure)
            } finally {
                cleanupSession(token)
            }
        } else {
            missingBinaries()?.let { return CommandResult(126, "", it) }
            ProcessRunner.run(LaunchCommands.proot(prootConfig(rootfs), command), timeoutMs, OUTPUT_CAP)
        }
    }

    /** An interactive login shell in the distro. Close it to end every process it started. */
    fun openSession(cols: Int, rows: Int): TerminalSession {
        val ready = _state.value as? LinuxState.Ready ?: throw IllegalStateException("Linux is not installed.")
        val rootfs = paths.rootfs(ready.distro)
        prepareRuntime()
        return if (ready.mode == RunMode.Chroot && probe?.chrootCapable == true) {
            val token = newToken()
            val spec = LaunchCommands.chroot(chrootConfig(rootfs, token), null)
            PtySession.start(spec.argv, spec.env, spec.cwd, cols, rows) {
                scope.launch(Dispatchers.IO) { cleanupSession(token) }
            }
        } else {
            missingBinaries()?.let { throw IllegalStateException(it) }
            val spec = LaunchCommands.proot(prootConfig(rootfs), null)
            PtySession.start(spec.argv, spec.env, spec.cwd, cols, rows)
        }
    }

    // ================================================================ state

    private fun computeState(): LinuxState {
        val work = lastWork
        if (work != null && !work.state.isFinished) {
            val distro = work.tags.firstNotNullOfOrNull { tag ->
                Distro.entries.firstOrNull { TAG_PREFIX + it.name == tag }
            } ?: Distro.Alpine
            val step = work.progress.getString(LinuxInstallWorker.P_STEP) ?: LinuxInstallWorker.STEP_RESOLVE
            val progress = work.progress.getFloat(LinuxInstallWorker.P_PROGRESS, 0f)
            return LinuxState.Installing(distro, step, progress)
        }
        installedDistro()?.let { d ->
            val size = readMarker(d)?.getProperty("sizeBytes")?.toLongOrNull() ?: 0L
            val mode = if (probe?.chrootCapable == true) RunMode.Chroot else RunMode.Proot
            return LinuxState.Ready(d, size, mode)
        }
        if (work != null && work.state == WorkInfo.State.FAILED) {
            return LinuxState.Failed(work.outputData.getString(LinuxInstallWorker.KEY_ERROR) ?: "Install failed.")
        }
        return LinuxState.NotInstalled
    }

    private fun installedDistro(): Distro? = Distro.entries.firstOrNull { paths.marker(it).isFile && paths.rootfs(it).isDirectory }

    private fun readMarker(d: Distro): Properties? = runCatching {
        Properties().apply { FileInputStream(paths.marker(d)).use { load(it) } }
    }.getOrNull()

    private suspend fun probeRoot(force: Boolean = false): RootSupport.Probe = probeMutex.withLock {
        probe?.takeIf { !force }?.let { return it }
        val p = runCatching { RootSupport.probe(hostEnv()) }.getOrDefault(RootSupport.Probe(false, false))
        probe = p
        p
    }

    // ================================================================ launch plumbing

    private fun hostEnv(): Map<String, String> = LinkedHashMap(System.getenv()).apply {
        put("TERM", LaunchCommands.TERM)
        put("HOME", app.filesDir.path)
        put("TMPDIR", app.cacheDir.path)
    }

    private fun newToken() = UUID.randomUUID().toString().replace("-", "").take(16)

    private fun missingBinaries(): String? =
        if (paths.proot.isFile && paths.prootLoader.isFile) {
            null
        } else {
            "The proot binaries are not on disk (${paths.proot.parent}). The app must be packaged with " +
                "extracted native libraries (jniLibs useLegacyPackaging = true)."
        }

    private fun prootConfig(rootfs: File) = ProotConfig(
        proot = paths.proot.path,
        loader = paths.prootLoader.path,
        rootfs = rootfs.path,
        prootTmp = paths.prootTmp.path,
        guestTmp = paths.guestTmp.path,
        fakeProc = fakeProcBindings(),
        sdcard = paths.sdcardOrNull(),
        shell = RootfsSetup.loginShell(rootfs),
    )

    private fun chrootConfig(rootfs: File, token: String) = ChrootConfig(
        script = paths.chrootScript.path,
        rootfs = rootfs.path,
        stateDir = paths.run.path,
        token = token,
        guestTmp = paths.guestTmp.path,
        sdcard = paths.sdcardOrNull() ?: "/storage/emulated/0", // root can read it even if the app cannot
        shell = RootfsSetup.loginShell(rootfs),
        hostEnv = hostEnv(),
    )

    /** Creates the scratch dirs and the launcher script; cheap and idempotent. */
    private fun prepareRuntime() {
        for (d in listOf(paths.guestTmp, paths.prootTmp, paths.run, paths.fakeProc)) d.mkdirs()
        runCatching { android.system.Os.chmod(paths.guestTmp.path, "1777".toInt(8)) }
        val script = LaunchCommands.chrootScript()
        if (!paths.chrootScript.isFile || paths.chrootScript.readText() != script) paths.chrootScript.writeText(script)
    }

    /**
     * Android hides some /proc files from apps (SELinux), and tools such as top/htop/free break without
     * them. Under proot each unreadable one is replaced by a plausible static file.
     */
    private fun fakeProcBindings(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((name, content) in FakeProc.files()) {
            if (runCatching { File("/proc/$name").readBytes().isNotEmpty() }.getOrDefault(false)) continue
            val host = File(paths.fakeProc, name)
            if (!host.isFile || host.readText() != content) host.writeText(content)
            out["/proc/$name"] = host.path
        }
        return out
    }

    private suspend fun cleanupSession(token: String) {
        if (!File(paths.run, "$token.ns").exists()) return
        RootSupport.su(LaunchCommands.cleanupScript(paths.run.path, token), hostEnv(), 15_000)
    }

    private suspend fun killAllChrootSessions() {
        val tokens = paths.run.listFiles()?.filter { it.name.endsWith(".ns") }?.map { it.name.removeSuffix(".ns") }.orEmpty()
        for (t in tokens) cleanupSession(t)
    }

    private suspend fun mountsUnder(dir: File): List<String> {
        val script = LaunchCommands.listMountsScript(dir.path)
        val here = RootSupport.su(script, hostEnv(), 10_000).stdout.lines()
        val global = RootSupport.suGlobal(script, hostEnv(), 10_000).stdout.lines()
        return (here + global).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    /** Lazily unmounts anything left under [dir], deepest first, in both namespaces su can reach. */
    private suspend fun unmountAllUnder(dir: File) {
        val mounts = mountsUnder(dir).sortedByDescending { it.length }
        if (mounts.isEmpty()) return
        val script = mounts.joinToString("\n") { "umount -l ${LaunchCommands.shQuote(it)} 2>/dev/null" }
        RootSupport.su(script, hostEnv(), 15_000)
        RootSupport.suGlobal(script, hostEnv(), 15_000)
    }

    private fun explainChrootFailure(r: CommandResult): CommandResult =
        if (r.exitCode == LaunchCommands.EXIT_NO_PRIVATE_MOUNTS) {
            r.copy(stderr = r.stderr + "\nChroot needs a private mount namespace, which this root setup refused.")
        } else {
            r
        }

    private fun diskUsage(dir: File): Long {
        var total = 0L
        val stack = ArrayDeque<File>().apply { add(dir) }
        while (stack.isNotEmpty()) {
            val f = stack.removeLast()
            if (AndroidFsOps.isSymlink(f)) continue
            if (f.isDirectory) f.listFiles()?.let { stack.addAll(it) } else total += f.length()
        }
        return total
    }
}

/** Static stand-ins for /proc files apps may not read. */
internal object FakeProc {
    fun files(): Map<String, String> = mapOf(
        "loadavg" to "0.12 0.08 0.03 1/128 1000\n",
        "uptime" to "10000.00 40000.00\n",
        "version" to "Linux version ${System.getProperty("os.version") ?: "6.1.0"} (atom@android) #1 SMP PREEMPT\n",
        "stat" to buildString {
            val cpus = Runtime.getRuntime().availableProcessors()
            append("cpu  1000 0 1000 100000 0 0 0 0 0 0\n")
            for (i in 0 until cpus) append("cpu$i 100 0 100 10000 0 0 0 0 0 0\n")
            append("intr 0\nctxt 0\nbtime 1700000000\nprocesses 1000\nprocs_running 1\nprocs_blocked 0\n")
        },
        "vmstat" to "nr_free_pages 100000\npgpgin 0\npgpgout 0\npswpin 0\npswpout 0\n",
    )
}
