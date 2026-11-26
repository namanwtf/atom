package atom.linux.jobs

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import atom.linux.LinuxEnv
import atom.linux.LinuxPaths
import atom.linux.LinuxState
import atom.linux.ShellEnv
import atom.linux.launch.LaunchCommands
import atom.linux.launch.LaunchSpec
import atom.linux.launch.RootSupport
import atom.linux.pty.Pty
import atom.linux.runtime.LinuxRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/**
 * Background commands and daemons. Each runs in its own process group with output going straight to a log file;
 * one thread per job blocks in waitpid, so there is no polling. A job holds the runtime service only while it runs.
 * Jobs end on their own, at their time limit (default 10 minutes, can be switched off), or when stopped. Daemons have
 * no limit and live until stopped or until the app process ends (never across a reboot).
 */
class JobManager private constructor(context: Context) {
    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: JobManager? = null

        fun get(context: Context): JobManager =
            instance ?: synchronized(this) { instance ?: JobManager(context.applicationContext).also { instance = it } }

        private const val CHANNEL = "atom_linux_jobs"
        const val LOG_TAIL = 64 * 1024

        /**
         * At app start: if the last process left jobs marked running, load the manager now (on a background
         * thread) so they are reconciled and the user is told they were interrupted. Otherwise nothing happens.
         */
        fun reconcileOnStart(context: Context) {
            val app = context.applicationContext
            Thread({
                val f = File(LinuxPaths(app).jobs, "jobs.json")
                val pending = runCatching { f.isFile && f.readText().contains("\"Running\"") }.getOrDefault(false)
                if (pending) get(app)
            }, "atom-jobs-reconcile").apply { isDaemon = true }.start()
        }
    }

    private val app = context.applicationContext
    private val paths = LinuxPaths(app)
    private val dir = paths.jobs
    private val file = File(dir, "jobs.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val timers = HashMap<String, Job>()

    private val _jobs = MutableStateFlow(load())
    val jobs: StateFlow<List<JobRecord>> = _jobs.asStateFlow()

    // ------------------------------------------------------------------ start / stop

    /**
     * Starts [command] in the background. For a daemon, [name] must be unique among running daemons. Throws
     * IllegalStateException with a user-readable reason when it cannot start.
     */
    suspend fun start(
        kind: JobKind,
        env: ShellEnv,
        command: String,
        name: String? = null,
        timeoutMinutes: Int? = null,
        source: String = "agent",
    ): JobRecord {
        require(command.isNotBlank()) { "Empty command." }
        val linux = LinuxEnv.get(app)
        val title = when (kind) {
            JobKind.Daemon -> {
                val n = name?.trim().orEmpty()
                check(JobRules.validDaemonName(n)) { "Daemon names use letters, digits, dots, dashes and underscores (max 40)." }
                check(_jobs.value.none { it.kind == JobKind.Daemon && it.name == n && it.state == JobState.Running }) { "A daemon named $n is already running." }
                n
            }
            JobKind.Job -> name?.takeIf { it.isNotBlank() } ?: JobRules.titleFor(command)
        }
        check(_jobs.value.count { it.state == JobState.Running } < JobRules.MAX_RUNNING) { "Too many background commands are running." }
        val timeout = JobRules.effectiveTimeoutMs(kind, timeoutMinutes, linux.config.value.jobCapMinutes)
        val (spec, token) = specFor(linux, env, command)
        val id = UUID.randomUUID().toString().take(8)
        dir.mkdirs()
        val log = File(dir, "$id.log")
        log.writeText("$ $command\n")
        val envp = spec.env.map { (k, v) -> "$k=$v" }.toTypedArray()
        val pid = try {
            Pty.spawnToFile(spec.argv[0], spec.argv.toTypedArray(), envp, spec.cwd, log.path)
        } catch (e: java.io.IOException) {
            throw IllegalStateException("Could not start: ${e.message}")
        }
        val record = JobRecord(
            id = id, kind = kind, name = title, command = command, env = env,
            startedAtMs = System.currentTimeMillis(), timeoutMs = timeout, pid = pid,
            pidStartTicks = startTicks(pid), logPath = log.path, chrootToken = token, source = source,
        )
        update { it + record }
        LinuxRuntime.acquire(app, "job:$id", if (kind == JobKind.Daemon) LinuxRuntime.Claim.Daemon else LinuxRuntime.Claim.Job)
        Thread({
            val code = Pty.waitFor(pid)
            onExit(id, code)
        }, "atom-job-$id").apply { isDaemon = true }.start()
        if (timeout > 0) {
            synchronized(timers) {
                timers[id] = scope.launch {
                    delay(timeout)
                    end(id, EndReason.TimedOut)
                }
            }
        }
        return record
    }

    fun stop(id: String) {
        scope.launch { end(id, EndReason.Stopped) }
    }

    fun stopAll() {
        _jobs.value.filter { it.state == JobState.Running }.forEach { stop(it.id) }
    }

    fun findDaemon(name: String): JobRecord? =
        _jobs.value.filter { it.kind == JobKind.Daemon && it.name == name }.maxByOrNull { it.startedAtMs }

    fun find(id: String): JobRecord? = _jobs.value.firstOrNull { it.id == id }

    /** The last [maxBytes] of the job's log. */
    fun log(id: String, maxBytes: Int = LOG_TAIL): String {
        val r = find(id) ?: return ""
        val f = File(r.logPath)
        if (!f.isFile) return ""
        return RandomAccessFile(f, "r").use { raf ->
            val len = raf.length()
            val start = (len - maxBytes).coerceAtLeast(0)
            raf.seek(start)
            val buf = ByteArray((len - start).toInt())
            raf.readFully(buf)
            val text = String(buf, Charsets.UTF_8)
            if (start > 0) "… [${start} earlier bytes not shown]\n" + text.substringAfter('\n') else text
        }
    }

    /** Removes finished records (and their logs). */
    fun clearFinished() {
        val gone = _jobs.value.filter { it.state.finished }
        gone.forEach { File(it.logPath).delete() }
        update { list -> list.filter { !it.state.finished } }
    }

    // ------------------------------------------------------------------ internals

    private suspend fun specFor(linux: LinuxEnv, env: ShellEnv, command: String): Pair<LaunchSpec, String?> {
        val hostEnv = linux.hostEnv()
        return when (env) {
            ShellEnv.Android -> LaunchSpec(listOf("/system/bin/sh", "-c", command), hostEnv, app.filesDir.path) to null
            ShellEnv.Root -> {
                check(linux.probeRoot().root) { "Root access is not available." }
                LaunchSpec(atom.linux.AndroidShell.rootArgv(app, command), hostEnv, "/") to null
            }
            ShellEnv.Linux -> {
                val ready = linux.state.value as? LinuxState.Ready ?: throw IllegalStateException("Linux is not installed.")
                check(!ready.rootLost) { "Root mode is selected but root access is gone; switch Linux to Standard mode." }
                linux.commandSpec(ready.distro, command) ?: throw IllegalStateException("Linux cannot start right now.")
            }
        }
    }

    private suspend fun end(id: String, reason: EndReason) {
        val r = find(id) ?: return
        if (r.state.finished) return
        // Mark first so the exit that follows is attributed to this reason.
        pendingReason[id] = reason
        Pty.signal(r.pid, Pty.SIGTERM, group = true)
        if (r.env == ShellEnv.Root) RootSupport.su("kill -TERM -${r.pid} 2>/dev/null", LinuxEnv.get(app).hostEnv(), 5_000)
        delay(1_500)
        if (find(id)?.state == JobState.Running) {
            Pty.signal(r.pid, Pty.SIGKILL, group = true)
            if (r.env == ShellEnv.Root) RootSupport.su("kill -KILL -${r.pid} 2>/dev/null", LinuxEnv.get(app).hostEnv(), 5_000)
            r.chrootToken?.let { LinuxEnv.get(app).cleanupSession(it) }
        }
    }

    private val pendingReason = java.util.concurrent.ConcurrentHashMap<String, EndReason>()

    private fun onExit(id: String, code: Int) {
        synchronized(timers) { timers.remove(id)?.cancel() }
        val reason = pendingReason.remove(id) ?: EndReason.Exited
        var finished: JobRecord? = null
        update { list ->
            list.map { if (it.id == id) JobRules.finish(it, code, reason, System.currentTimeMillis()).also { f -> finished = f } else it }
        }
        LinuxRuntime.release("job:$id")
        val r = finished ?: return
        r.chrootToken?.let { t -> scope.launch { LinuxEnv.get(app).cleanupSession(t) } }
        File(r.logPath).appendText("\n[${JobRules.describe(r)}]\n")
        notifyFinished(r)
    }

    private fun update(block: (List<JobRecord>) -> List<JobRecord>) {
        synchronized(lock) {
            val (kept, dropped) = JobRules.prune(block(_jobs.value))
            dropped.forEach { File(it.logPath).delete() }
            _jobs.value = kept
            save(kept)
        }
    }

    private fun load(): List<JobRecord> {
        val records = runCatching { json.decodeFromString(ListSerializer(JobRecord.serializer()), file.readText()) }.getOrDefault(emptyList())
        val reconciled = JobRules.reconcileAfterRestart(
            records,
            System.currentTimeMillis(),
            stillAlive = { r -> r.pid > 0 && r.pidStartTicks > 0 && startTicks(r.pid) == r.pidStartTicks },
            kill = { r -> Pty.signal(r.pid, Pty.SIGKILL, group = true) },
        )
        if (reconciled != records) {
            save(reconciled)
            // Tell the user about work that the restart cut short.
            val wasRunning = records.filter { it.state == JobState.Running }.map { it.id }.toSet()
            reconciled.filter { it.id in wasRunning && it.state == JobState.Interrupted }.forEach { r ->
                runCatching { File(r.logPath).appendText("\n[${JobRules.describe(r)}]\n") }
                notifyFinished(r)
            }
        }
        return reconciled
    }

    private fun save(list: List<JobRecord>) {
        runCatching {
            dir.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(ListSerializer(JobRecord.serializer()), list))
            tmp.renameTo(file)
        }
    }

    /** Field 22 of /proc/<pid>/stat (start time in clock ticks), or 0. */
    private fun startTicks(pid: Int): Long = runCatching {
        val stat = File("/proc/$pid/stat").readText()
        stat.substringAfterLast(')').trim().split(' ')[19].toLong()
    }.getOrDefault(0L)

    private fun notifyFinished(r: JobRecord) {
        if (r.state == JobState.Stopped && r.source == "user") return // the user did it; no need to tell them
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Background commands", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "When a background command or daemon finishes"
                },
            )
        }
        // The app's launcher opens the route named by this extra: tapping lands on Background tasks.
        val open = app.packageManager.getLaunchIntentForPackage(app.packageName)?.apply {
            putExtra("atom.app.EXTRA_ROUTE", "linux/jobs")
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }?.let {
            PendingIntent.getActivity(app, r.id.hashCode(), it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val n = Notification.Builder(app, CHANNEL)
            .setSmallIcon(atom.ui.R.drawable.ic_stat_atom)
            .setColor(android.graphics.Color.BLACK)
            .setContentTitle(JobRules.notificationTitle(r))
            .setContentText(JobRules.notificationText(r).substringBefore('\n'))
            .setStyle(Notification.BigTextStyle().bigText(JobRules.notificationText(r)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(0x4C1B0000 or (r.id.hashCode() and 0xFFFF), n) }
    }
}
