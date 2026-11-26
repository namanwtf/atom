package atom.linux

import android.annotation.SuppressLint
import android.content.Context
import android.os.StatFs
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import atom.linux.install.InstallScripts
import atom.linux.install.InstallStep
import atom.linux.install.LinuxInstallWorker
import atom.linux.install.Profile
import atom.linux.install.RootfsSetup
import atom.linux.launch.ChrootConfig
import atom.linux.launch.LaunchCommands
import atom.linux.launch.LaunchSpec
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
 * The on-device Linux environment: one installed distro, run with chroot (Root mode) or proot (Standard mode).
 *
 * Nothing runs while idle: there is no daemon or service of its own. Each session or command is a process tree
 * that dies with it (proot --kill-on-exit; chroot sessions live in a private mount namespace that is emptied, and
 * so unmounted, when they end). Background jobs and daemons are separate ([atom.linux.jobs.JobManager]).
 */
class LinuxEnv private constructor(context: Context) {

    companion object {
        @SuppressLint("StaticFieldLeak") // application context only
        @Volatile
        private var instance: LinuxEnv? = null

        fun get(context: Context): LinuxEnv =
            instance ?: synchronized(this) { instance ?: LinuxEnv(context.applicationContext).also { instance = it } }

        /** The app's shared root status; set once at app start, before [get] is first called. */
        @Volatile
        var rootLink: RootLink? = null

        /** Upper bound on captured stdout/stderr per stream for [run]. */
        const val OUTPUT_CAP = 64_000

        private const val TAG_PREFIX = "atom-linux-distro:"
    }

    private val app = context.applicationContext
    internal val paths = LinuxPaths(app)
    private val store = LinuxConfigStore(paths.config)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val workManager by lazy { WorkManager.getInstance(app) }
    private val probeMutex = Mutex()
    private val uninstallMutex = Mutex()

    @Volatile private var probe: RootSupport.Probe? = null
    @Volatile private var lastWork: WorkInfo? = null

    private val _config = MutableStateFlow(store.get())
    val config: StateFlow<LinuxConfig> = _config.asStateFlow()

    private val _state = MutableStateFlow(computeState())
    val state: StateFlow<LinuxState> = _state.asStateFlow()

    private val _health = MutableStateFlow<HealthReport?>(null)
    val health: StateFlow<HealthReport?> = _health.asStateFlow()

    private val _rootAvailable = MutableStateFlow<Boolean?>(null)

    /** Whether root works, live from the shared root status; null until known. */
    val rootAvailable: StateFlow<Boolean?> = _rootAvailable.asStateFlow()

    init {
        scope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(LinuxInstallWorker.UNIQUE_NAME).collect { infos ->
                lastWork = infos.firstOrNull()
                _config.value = store.get()
                _state.value = computeState()
            }
        }
        // Root granted or lost anywhere in the app: Root mode and the root terminal follow at once.
        rootLink?.let { link ->
            scope.launch {
                link.granted.collect { granted ->
                    if (granted) {
                        // Learn whether a private mount namespace works (su is silent once granted).
                        if (probe?.root != true) runCatching { probeRoot(force = true) }
                    } else if (probe != null || _rootAvailable.value != null) {
                        probeMutex.withLock {
                            probe = RootSupport.Probe(root = false, privateMounts = false)
                            _rootAvailable.value = false
                            _state.value = computeState()
                        }
                    }
                }
            }
        }
    }

    /** The root check result, once known. */
    val rootProbe: RootSupport.Probe? get() = probe

    // ================================================================ install / upgrade / repair

    /** One tap: base system, base tools, then [profiles]. Already installed parts are skipped. */
    fun install(distro: Distro, profiles: Set<Profile> = emptySet()) {
        val installed = installedDistro()
        if (installed != null && installed != distro) return
        val cfg = store.get()
        val steps = buildList {
            if (installed == null) add(InstallStep.Base)
            if (installed == null || !cfg.toolsInstalled) add(InstallStep.Tools)
            profiles.filter { it !in cfg.profiles || installed == null }.forEach { add(InstallStep.ProfileStep(it)) }
        }
        enqueue(distro, steps)
    }

    /** Runs what is left of an interrupted or failed install. */
    fun continueInstall() {
        val distro = installedDistro() ?: (lastWorkDistro() ?: return)
        val steps = store.get().pendingSteps.mapNotNull(InstallStep::parse)
        if (steps.isEmpty()) install(distro) else enqueue(distro, steps)
    }

    fun installProfiles(profiles: Set<Profile>) {
        val distro = installedDistro() ?: return
        val cfg = store.get()
        val steps = buildList {
            if (!cfg.toolsInstalled) add(InstallStep.Tools)
            profiles.forEach { add(InstallStep.ProfileStep(it)) }
        }
        enqueue(distro, steps)
    }

    /** Upgrades the distro's packages and refreshes installed profiles to their latest releases. */
    fun upgrade() {
        val distro = installedDistro() ?: return
        enqueue(distro, listOf(InstallStep.Upgrade) + store.get().profiles.map { InstallStep.ProfileStep(it) })
    }

    fun repair() {
        val distro = installedDistro() ?: return
        enqueue(distro, listOf(InstallStep.Repair))
    }

    fun cancelInstall() {
        workManager.cancelUniqueWork(LinuxInstallWorker.UNIQUE_NAME)
    }

    private fun enqueue(distro: Distro, steps: List<InstallStep>) {
        if (steps.isEmpty() || _state.value is LinuxState.Installing) return
        store.update { it.copy(pendingSteps = steps.map { s -> s.key }) }
        val request = OneTimeWorkRequestBuilder<LinuxInstallWorker>()
            .setInputData(
                workDataOf(
                    LinuxInstallWorker.KEY_DISTRO to distro.name,
                    LinuxInstallWorker.KEY_STEPS to steps.joinToString(",") { it.key },
                ),
            )
            .addTag(TAG_PREFIX + distro.name)
            .build()
        workManager.enqueueUniqueWork(LinuxInstallWorker.UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        _state.value = LinuxState.Installing(distro, LinuxInstallWorker.STEP_RESOLVE, 0f, steps.first().title, 0, steps.size)
    }

    /** Called by the installer as it goes. */
    internal fun updateConfig(block: (LinuxConfig) -> LinuxConfig) {
        _config.value = store.update(block)
        _state.value = computeState()
    }

    /**
     * Removes the installed distro (the /workspace files stay). In chroot mode every session process is killed first
     * and the mount table is checked (app and global namespace); nothing is deleted while anything is still mounted
     * under the install, and deletion never crosses file systems.
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
                check(SafeDelete.deleteTree(dir)) {
                    if (store.get().usedChroot && !p.root) {
                        "Some files were created in Root mode and need root to delete. Restore root access, then uninstall."
                    } else {
                        "Some Linux files could not be removed."
                    }
                }
                SafeDelete.deleteTree(paths.guestTmp)
                SafeDelete.deleteTree(paths.prootTmp)
                SafeDelete.deleteTree(paths.fakeProc)
                store.update { it.copy(toolsInstalled = false, profiles = emptySet(), versions = emptyMap(), pendingSteps = emptyList(), usedChroot = false) }
            }
        } finally {
            _config.value = store.get()
            _health.value = null
            _state.value = computeState()
        }
    }

    // ================================================================ mode, settings, folders

    /** Re-checks root and disk use. Call when showing Linux UI; may prompt the root manager once. */
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

    /** Switches between Standard (proot) and Root (chroot). Returns an error message, or null when done. */
    suspend fun setMode(mode: RunMode): String? {
        if (mode == RunMode.Chroot) {
            val p = probeRoot(force = true, ask = true)
            if (!p.root) return "Root access is not available (su did not grant root)."
            if (!p.privateMounts) return "This root setup cannot create a private mount namespace (unshare -m), which Root mode needs."
        }
        updateConfig { it.copy(preferredMode = mode) }
        return null
    }

    fun setJobCapMinutes(minutes: Int) = updateConfig { it.copy(jobCapMinutes = minutes.coerceIn(0, 24 * 60)) }

    fun setTerminalFontSp(sp: Float) {
        val v = TerminalText.clamp(sp)
        if (store.get().terminalFontSp != v) updateConfig { it.copy(terminalFontSp = v) }
    }

    /** Adds a shared folder (applies from the next session). Returns an error message, or null. */
    fun addSharedFolder(name: String, hostPath: String): String? {
        val folder = SharedFolder(name.trim(), SharedFolderRules.normalize(hostPath.trim()) ?: hostPath.trim())
        val problem = SharedFolderRules.validate(folder, store.get().sharedFolders, paths.base.path)
        if (problem != null) return describe(problem)
        val dir = File(folder.hostPath)
        if (!dir.isDirectory) return "That folder does not exist."
        updateConfig { it.copy(sharedFolders = it.sharedFolders + folder) }
        return null
    }

    fun removeSharedFolder(name: String) = updateConfig { c -> c.copy(sharedFolders = c.sharedFolders.filterNot { it.name == name }) }

    private fun describe(p: SharedFolderRules.Problem): String = when (p) {
        SharedFolderRules.Problem.BadName -> "Use 1-32 letters, digits, dots, dashes or underscores, starting with a letter or digit."
        SharedFolderRules.Problem.DuplicateName -> "A shared folder with that name already exists."
        SharedFolderRules.Problem.DuplicatePath -> "That folder is already shared."
        SharedFolderRules.Problem.NotAbsolute -> "Enter a full path starting with /."
        SharedFolderRules.Problem.Forbidden -> "That system location cannot be shared."
        SharedFolderRules.Problem.InsideLinux -> "Linux's own files cannot be shared into itself."
        SharedFolderRules.Problem.TooMany -> "At most ${SharedFolderRules.MAX_FOLDERS} shared folders."
    }

    /** A read-only view of the installed system, or null when nothing is installed. */
    fun guestFs(): GuestFs? {
        val distro = installedDistro() ?: return null
        return GuestFs(paths.rootfs(distro), guestMounts())
    }

    private fun guestMounts(): Map<String, File> = buildMap {
        put("/workspace", paths.workspace)
        put("/tmp", paths.guestTmp)
        paths.sdcardOrNull()?.let { put("/sdcard", File(it)) }
        for (f in store.get().sharedFolders) put(f.guestPath, File(f.hostPath))
    }

    /** Free bytes on the storage that holds the install. */
    fun freeBytes(): Long = runCatching { StatFs(paths.base.apply { mkdirs() }.path).availableBytes }.getOrDefault(0L)

    // ================================================================ health

    suspend fun healthCheck(): HealthReport {
        val distro = installedDistro()
        val cfg = store.get()
        val report = if (distro == null) {
            HealthReport(listOf(HealthReport.Item("start", "Linux", HealthReport.Level.Problem, "Not installed.")), System.currentTimeMillis())
        } else {
            val r = runInDistro(distro, HealthCheck.script(distro), 60_000)
            val unreadable = cfg.sharedFolders.filter { !File(it.hostPath).canRead() }
            HealthCheck.evaluate(r.stdout, r.exitCode, freeBytes(), cfg.toolsInstalled, cfg.sharedFolders, unreadable)
        }
        _health.value = report
        return report
    }

    // ================================================================ running

    /**
     * Runs [command] non-interactively in a login shell inside the distro. stdout/stderr are captured (each capped at
     * [OUTPUT_CAP]); on timeout the whole process tree is killed.
     */
    suspend fun run(command: String, timeoutMs: Long = 120_000): CommandResult {
        val ready = _state.value as? LinuxState.Ready
            ?: return CommandResult(127, "", "Linux is not installed. Install it from Atom's Linux screen first.")
        if (ready.rootLost) return CommandResult(126, "", rootLostMessage())
        return runInDistro(ready.distro, command, timeoutMs)
    }

    /** Runs [command] in [distro] regardless of the published state (the installer uses this mid-install). */
    internal suspend fun runInDistro(
        distro: Distro,
        command: String,
        timeoutMs: Long,
        onLine: ((String) -> Unit)? = null,
    ): CommandResult {
        val (spec, token) = commandSpec(distro, command) ?: return CommandResult(126, "", missingBinaries() ?: rootLostMessage())
        return try {
            ProcessRunner.run(spec, timeoutMs, OUTPUT_CAP, onLine) {
                if (token != null) scope.launch(Dispatchers.IO) { cleanupSession(token) }
            }.let(::explainChrootFailure)
        } finally {
            if (token != null) cleanupSession(token)
        }
    }

    /**
     * The command line for running [command] in the distro now, plus the chroot token to clean up afterwards (null
     * under proot). Null when it cannot run (binaries missing, or Root mode chosen and root lost).
     */
    internal suspend fun commandSpec(distro: Distro, command: String?): Pair<LaunchSpec, String?>? {
        if (store.get().preferredMode == RunMode.Chroot && probe == null) probeRoot()
        val rootfs = paths.rootfs(distro)
        prepareRuntime()
        return when (effectiveMode()) {
            RunMode.Chroot -> {
                val token = newToken()
                LaunchCommands.chroot(chrootConfig(rootfs, token), command) to token
            }
            RunMode.Proot -> {
                if (store.get().preferredMode == RunMode.Chroot) return null // root lost: never fall back silently
                if (missingBinaries() != null) return null
                LaunchCommands.proot(prootConfig(rootfs), command) to null
            }
        }
    }

    /** An interactive login shell in the distro. Close it to end every process it started. */
    fun openSession(cols: Int, rows: Int, onOutput: ((ByteArray, Int) -> Unit)? = null): TerminalSession {
        val ready = _state.value as? LinuxState.Ready ?: throw IllegalStateException("Linux is not installed.")
        if (ready.rootLost) throw IllegalStateException(rootLostMessage())
        val rootfs = paths.rootfs(ready.distro)
        prepareRuntime()
        return if (ready.mode == RunMode.Chroot) {
            val token = newToken()
            val spec = LaunchCommands.chroot(chrootConfig(rootfs, token), null)
            PtySession.start(spec.argv, spec.env, spec.cwd, cols, rows, onExit = {
                scope.launch(Dispatchers.IO) { cleanupSession(token) }
            }, onOutput = onOutput)
        } else {
            missingBinaries()?.let { throw IllegalStateException(it) }
            val spec = LaunchCommands.proot(prootConfig(rootfs), null)
            PtySession.start(spec.argv, spec.env, spec.cwd, cols, rows, onOutput = onOutput)
        }
    }

    private fun rootLostMessage() =
        "Root mode is selected but root access is gone. Your Linux files are kept; switch to Standard mode (PRoot) " +
            "on the Linux screen, or restore root."

    // ================================================================ state

    private fun effectiveMode(): RunMode {
        val wantsChroot = store.get().preferredMode == RunMode.Chroot
        return if (wantsChroot && probe?.chrootCapable == true) RunMode.Chroot else RunMode.Proot
    }

    private fun computeState(): LinuxState {
        val work = lastWork
        if (work != null && !work.state.isFinished) {
            val distro = work.tags.firstNotNullOfOrNull { tag ->
                Distro.entries.firstOrNull { TAG_PREFIX + it.name == tag }
            } ?: Distro.Alpine
            val p = work.progress
            return LinuxState.Installing(
                distro = distro,
                step = p.getString(LinuxInstallWorker.P_STEP) ?: LinuxInstallWorker.STEP_RESOLVE,
                progress = p.getFloat(LinuxInstallWorker.P_PROGRESS, 0f),
                stage = p.getString(LinuxInstallWorker.P_STAGE) ?: "",
                stageIndex = p.getInt(LinuxInstallWorker.P_STAGE_INDEX, 0),
                stageCount = p.getInt(LinuxInstallWorker.P_STAGE_COUNT, 1),
            )
        }
        val cfg = store.get()
        installedDistro()?.let { d ->
            val props = readMarker(d)
            val size = props?.getProperty("sizeBytes")?.toLongOrNull() ?: 0L
            val p = probe
            val rootLost = cfg.preferredMode == RunMode.Chroot && p != null && !p.chrootCapable
            return LinuxState.Ready(
                distro = d,
                sizeBytes = size,
                mode = effectiveMode().let { if (cfg.preferredMode == RunMode.Chroot && p == null) RunMode.Chroot else it },
                release = props?.getProperty("release").orEmpty(),
                toolsInstalled = cfg.toolsInstalled,
                profiles = cfg.profiles,
                rootLost = rootLost,
                pendingSteps = cfg.pendingSteps,
            )
        }
        if (work != null && work.state == WorkInfo.State.FAILED) {
            return LinuxState.Failed(work.outputData.getString(LinuxInstallWorker.KEY_ERROR) ?: "Install failed.")
        }
        return LinuxState.NotInstalled
    }

    private fun lastWorkDistro(): Distro? = lastWork?.tags?.firstNotNullOfOrNull { tag ->
        Distro.entries.firstOrNull { TAG_PREFIX + it.name == tag }
    }

    internal fun installedDistro(): Distro? = Distro.entries.firstOrNull { paths.marker(it).isFile && paths.rootfs(it).isDirectory }

    private fun readMarker(d: Distro): Properties? = runCatching {
        Properties().apply { FileInputStream(paths.marker(d)).use { load(it) } }
    }.getOrNull()

    /**
     * Checks su and private-mount support once (or again with [force]). Whether root is granted comes from the
     * app's shared status ([rootLink]): su is only run here once root is granted, so Linux never shows a superuser
     * prompt of its own. [ask] is the user's own request (choosing Root mode) and may ask again.
     */
    suspend fun probeRoot(force: Boolean = false, ask: Boolean = false): RootSupport.Probe = probeMutex.withLock {
        val link = rootLink
        val granted = link?.let { runCatching { if (ask) it.authorize() else it.ensure() }.getOrDefault(false) }
        probe?.takeIf { !force && (granted == null || it.root == granted) }?.let { return it }
        val p = if (granted == false) {
            RootSupport.Probe(root = false, privateMounts = false)
        } else {
            runCatching { RootSupport.probe(hostEnv()) }.getOrDefault(RootSupport.Probe(false, false))
        }
        probe = p
        _rootAvailable.value = p.root
        _state.value = computeState()
        p
    }

    // ================================================================ launch plumbing

    internal fun hostEnv(): Map<String, String> = LinkedHashMap(System.getenv()).apply {
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

    /** Workspace first, then each shared folder below it (mount points are created on the app side). */
    private fun workspaceBinds(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        out += paths.workspace.path to "/workspace"
        for (f in store.get().sharedFolders) {
            if (!File(f.hostPath).isDirectory) continue
            File(paths.workspace, "mounts/${f.name}").mkdirs()
            out += f.hostPath to f.guestPath
        }
        return out
    }

    private fun prootConfig(rootfs: File) = ProotConfig(
        proot = paths.proot.path,
        loader = paths.prootLoader.path,
        rootfs = rootfs.path,
        prootTmp = paths.prootTmp.path,
        guestTmp = paths.guestTmp.path,
        fakeProc = fakeProcBindings(),
        sdcard = paths.sdcardOrNull(),
        binds = workspaceBinds(),
        shell = RootfsSetup.loginShell(rootfs),
    )

    private fun chrootConfig(rootfs: File, token: String): ChrootConfig {
        val binds = ArrayList<Pair<String, String>>()
        binds += paths.guestTmp.path to "/tmp"
        binds += (paths.sdcardOrNull() ?: "/storage/emulated/0") to "/sdcard" // root can read it even if the app cannot
        binds += workspaceBinds()
        val file = File(paths.run, "$token.binds")
        file.writeText(LaunchCommands.bindsFile(binds))
        if (!store.get().usedChroot) updateConfig { it.copy(usedChroot = true) }
        return ChrootConfig(
            script = paths.chrootScript.path,
            rootfs = rootfs.path,
            stateDir = paths.run.path,
            token = token,
            bindsFile = file.path,
            shell = RootfsSetup.loginShell(rootfs),
            hostEnv = hostEnv(),
        )
    }

    /** Creates the scratch dirs, workspace and the launcher script; cheap and idempotent. */
    private fun prepareRuntime() {
        for (d in listOf(paths.guestTmp, paths.prootTmp, paths.run, paths.fakeProc, paths.workspace)) d.mkdirs()
        installedDistro()?.let { File(paths.rootfs(it), "workspace").mkdirs() }
        runCatching { android.system.Os.chmod(paths.guestTmp.path, "1777".toInt(8)) }
        val script = LaunchCommands.chrootScript()
        if (!paths.chrootScript.isFile || paths.chrootScript.readText() != script) paths.chrootScript.writeText(script)
    }

    /**
     * Android hides some /proc files from apps (SELinux), and tools such as top/htop/free break without them. Under
     * proot each unreadable one is replaced by a plausible static file.
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

    /** Kills whatever is left of one chroot session (its whole mount namespace) and removes its records. */
    internal suspend fun cleanupSession(token: String) {
        File(paths.run, "$token.binds").delete()
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
            r.copy(stderr = r.stderr + "\nRoot mode needs a private mount namespace, which this root setup refused. Use Standard mode.")
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

    internal fun toolsBytes(distro: Distro) = InstallScripts.approxToolsBytes(distro)
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
