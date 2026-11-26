package atom.linux.install

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.storage.StorageManager
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import atom.linux.AndroidFsOps
import atom.linux.Distro
import atom.linux.LinuxEnv
import atom.linux.LinuxPaths
import atom.linux.SafeDelete
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream

/**
 * One-tap install: resolve the image, download (resuming), verify SHA-256, extract, configure, then mark
 * installed. Runs as a WorkManager foreground worker (dataSync) so it survives the app going to the
 * background, like a package installer; it exits as soon as the install is done.
 */
class LinuxInstallWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val UNIQUE_NAME = "atom-linux-install"
        const val KEY_DISTRO = "distro"
        const val KEY_STEPS = "steps"
        const val KEY_ERROR = "error"
        const val P_STEP = "step"
        const val P_PROGRESS = "progress"
        const val P_STAGE = "stage"
        const val P_STAGE_INDEX = "stageIndex"
        const val P_STAGE_COUNT = "stageCount"
        private const val CHANNEL_ID = "atom_linux_install"
        private const val NOTIFICATION_ID = 0x4C1A0001

        // Share of the overall progress bar per phase.
        private const val DOWNLOAD_END = 0.55f
        private const val VERIFY_END = 0.6f
        private const val EXTRACT_END = 0.95f

        const val STEP_RESOLVE = "Finding the image"
        const val STEP_DOWNLOAD = "Downloading"
        const val STEP_VERIFY = "Verifying"
        const val STEP_EXTRACT = "Unpacking"
        const val STEP_CONFIGURE = "Setting up"
    }

    private class Failure(message: String) : Exception(message)

    private val paths = LinuxPaths(applicationContext)
    private var foregroundOk = true
    private var lastStep = ""
    private var lastPct = -1

    private var stageIndex = 0
    private var stageCount = 1
    private var stageTitle = ""

    override suspend fun doWork(): Result {
        val distro = inputData.getString(KEY_DISTRO)?.let { n -> Distro.entries.firstOrNull { it.name == n } }
            ?: return Result.failure(workDataOf(KEY_ERROR to "Invalid install request."))
        val steps = (inputData.getString(KEY_STEPS) ?: InstallStep.Base.key).split(',').mapNotNull(InstallStep::parse)
        if (steps.isEmpty()) return Result.failure(workDataOf(KEY_ERROR to "Invalid install request."))
        val env = LinuxEnv.get(applicationContext)
        stageCount = steps.size
        var current: InstallStep = steps.first()
        return try {
            withContext(Dispatchers.IO) {
                for ((i, step) in steps.withIndex()) {
                    current = step
                    stageIndex = i
                    stageTitle = step.title
                    runStep(env, distro, step)
                    env.updateConfig { c -> c.copy(pendingSteps = steps.drop(i + 1).map { it.key }) }
                }
            }
            env.updateConfig {
                it.copy(pendingSteps = emptyList(), lastResult = "${steps.last().title} finished", lastResultOk = true, lastResultAtMs = System.currentTimeMillis())
            }
            postDone(distro, steps)
            Result.success()
        } catch (e: CancellationException) {
            if (current == InstallStep.Base) cleanupPartial(distro, keepDownload = false)
            env.updateConfig { it.copy(lastResult = "${current.title} cancelled", lastResultOk = false, lastResultAtMs = System.currentTimeMillis()) }
            throw e
        } catch (e: Exception) {
            if (current == InstallStep.Base) cleanupPartial(distro, keepDownload = e is IOException)
            val message = when (e) {
                is Failure -> e.message ?: "Install failed."
                is IOException -> "${current.title} failed: ${e.message ?: e.javaClass.simpleName}. Tap Continue installation to try again."
                else -> "${current.title} failed: ${e.message ?: e.javaClass.simpleName}"
            }
            env.updateConfig { it.copy(lastResult = message, lastResultOk = false, lastResultAtMs = System.currentTimeMillis()) }
            Result.failure(workDataOf(KEY_ERROR to message))
        }
    }

    private suspend fun runStep(env: LinuxEnv, distro: Distro, step: InstallStep) {
        when (step) {
            InstallStep.Base -> installBase(distro)
            InstallStep.Tools -> {
                checkSpace(env.toolsBytes(distro))
                guest(env, distro, InstallScripts.tools(distro), "Installing packages")
                env.updateConfig { it.copy(toolsInstalled = true) }
            }
            is InstallStep.ProfileStep -> {
                checkSpace(step.profile.approxBytes)
                guest(env, distro, InstallScripts.profile(step.profile, distro), "Installing ${step.profile.title}")
                val version = env.runInDistro(distro, InstallScripts.versionProbe(step.profile), 60_000).stdout.trim().lines().lastOrNull().orEmpty()
                env.updateConfig { it.copy(profiles = it.profiles + step.profile, versions = it.versions + (step.profile.name to version)) }
            }
            InstallStep.Upgrade -> guest(env, distro, InstallScripts.upgrade(distro), "Upgrading packages")
            InstallStep.Repair -> {
                report(distro, "Rewriting configuration", 0.05f)
                RootfsSetup.configure(paths.rootfs(distro), distro, AndroidFsOps)
                paths.workspace.mkdirs()
                guest(env, distro, InstallScripts.repair(distro), "Recovering packages", failOnError = false)
                if (env.config.value.toolsInstalled) guest(env, distro, InstallScripts.tools(distro), "Reinstalling base tools")
            }
        }
    }

    /** Runs an installer script in the distro, turning package-manager output into progress. */
    private suspend fun guest(env: LinuxEnv, distro: Distro, script: String, step: String, failOnError: Boolean = true) {
        report(distro, step, 0.02f)
        val progress = PackageProgress()
        val tail = ArrayDeque<String>()
        var lastReport = 0L
        val r = env.runInDistro(distro, script, 45 * 60_000L) { line ->
            synchronized(tail) {
                tail.addLast(line)
                while (tail.size > 12) tail.removeFirst()
            }
            val f = progress.line(line) ?: return@runInDistro
            val now = System.currentTimeMillis()
            if (now - lastReport >= 500) {
                lastReport = now
                reportBlocking(distro, step, 0.05f + 0.9f * f)
            }
        }
        if (r.timedOut) throw IOException("it took longer than 45 minutes")
        if (failOnError && r.exitCode != 0) {
            val last = synchronized(tail) { tail.joinToString("\n") }.ifBlank { r.stderr.takeLast(800) }
            val space = last.contains("No space left", ignoreCase = true)
            throw Failure(
                if (space) "Not enough space to finish $stageTitle. Free some storage, then tap Continue installation."
                else "$stageTitle failed (exit ${r.exitCode}). Last output:\n$last",
            )
        }
        report(distro, step, 1f)
    }

    private suspend fun installBase(distro: Distro) {
        report(distro, STEP_RESOLVE, 0f)
        val source = try {
            DistroSources.resolve(distro)
        } catch (e: IOException) {
            throw IOException("could not reach the image server (${e.message})")
        }

        val partial = paths.partialRootfs(distro)
        val rootfs = paths.rootfs(distro)
        paths.distroDir(distro).mkdirs()
        paths.downloads.mkdirs()
        checkSpace(distro.downloadBytes + distro.installedBytes)

        val archive = paths.download(distro)
        download(distro, source, archive)

        report(distro, STEP_VERIFY, DOWNLOAD_END)
        if (!sha256(archive).equals(source.sha256, ignoreCase = true)) {
            archive.delete()
            throw Failure("The download was corrupted (checksum mismatch) and has been deleted. Tap install to try again.")
        }

        report(distro, STEP_EXTRACT, VERIFY_END)
        if (partial.exists()) SafeDelete.deleteTree(partial)
        extract(distro, source, archive, partial)

        report(distro, STEP_CONFIGURE, EXTRACT_END)
        RootfsSetup.configure(partial, distro, AndroidFsOps)
        if (rootfs.exists() && !SafeDelete.deleteTree(rootfs)) throw Failure("An older Linux install could not be removed.")
        if (!partial.renameTo(rootfs)) throw Failure("Could not finish the install (rename failed).")
        archive.delete()

        val size = du(rootfs)
        Properties().apply {
            setProperty("distro", distro.name)
            setProperty("release", source.release)
            setProperty("source", source.url)
            setProperty("sha256", source.sha256)
            setProperty("sizeBytes", size.toString())
            setProperty("installedAt", System.currentTimeMillis().toString())
        }.let { props -> FileOutputStream(paths.marker(distro)).use { props.store(it, "Atom Linux install") } }

        report(distro, STEP_CONFIGURE, 1f)
    }

    // ------------------------------------------------------------------ download

    private suspend fun download(distro: Distro, source: RootfsSource, part: File) {
        // A partial file only resumes against the very same image (Debian builds change daily).
        val origin = File(part.path + ".url")
        if (part.exists() && (!origin.isFile || origin.readText() != source.url)) part.delete()
        origin.writeText(source.url)
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val offset = if (part.exists()) part.length() else 0L
            val done = try {
                fetchOnce(distro, source, part, offset)
            } catch (e: Failure) {
                throw e
            } catch (e: IOException) {
                if (++attempt >= 4 || isStopped) throw e
                kotlinx.coroutines.delay(2_000L * attempt)
                false
            }
            if (done) {
                origin.delete()
                return
            }
        }
    }

    private suspend fun fetchOnce(distro: Distro, source: RootfsSource, part: File, offset: Long): Boolean =
        withConnection(source.url, offset) { conn ->
                val code = conn.responseCode
                when {
                    code == 416 && offset > 0 -> true // already complete
                    code == 206 || code == 200 -> {
                        val append = code == 206 && offset > 0
                        val start = if (append) offset else 0L
                        val total = when {
                            append -> conn.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L
                            else -> conn.contentLengthLong
                        }
                        FileOutputStream(part, append).use { out ->
                            conn.inputStream.use { input -> copy(distro, input, out, start, total) }
                            out.fd.sync()
                        }
                        if (total > 0 && part.length() != total) throw IOException("connection closed early")
                        true
                    }
                    code in 500..599 || code == 429 -> throw IOException("server busy (HTTP $code)")
                    else -> throw Failure("The image server answered HTTP $code for ${URL(source.url).host}.")
                }
            }

    private suspend fun copy(distro: Distro, input: InputStream, out: FileOutputStream, start: Long, total: Long) {
        val buf = ByteArray(128 * 1024)
        var have = start
        var lastReport = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            have += n
            val now = System.currentTimeMillis()
            if (now - lastReport >= 500) {
                lastReport = now
                val frac = if (total > 0) have.toFloat() / total else 0f
                report(distro, STEP_DOWNLOAD, DOWNLOAD_END * frac.coerceIn(0f, 1f), "${mb(have)} of ${if (total > 0) mb(total) else "?"}")
            }
        }
    }

    /** Disconnects from another thread on cancellation, which unblocks a socket read in progress. */
    private suspend fun <T> withConnection(url: String, offset: Long, block: suspend (HttpURLConnection) -> T): T =
        coroutineScope {
            val active = AtomicReference<HttpURLConnection?>(null)
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    active.get()?.disconnect()
                }
            }
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Atom-Linux/1")
                    if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
                }
                active.set(conn)
                try {
                    block(conn)
                } finally {
                    conn.disconnect()
                }
            } finally {
                active.set(null)
                watcher.cancel()
            }
        }

    // ------------------------------------------------------------------ verify / extract

    private suspend fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun extract(distro: Distro, source: RootfsSource, archive: File, target: File) {
        val total = archive.length().coerceAtLeast(1)
        val counting = CountingInputStream(BufferedInputStream(FileInputStream(archive), 256 * 1024))
        val ctx = currentCoroutineContext()
        var lastReport = 0L
        val decompressed: InputStream = when (source.compression) {
            Compression.Gzip -> GZIPInputStream(counting, 64 * 1024)
            Compression.Xz -> XZInputStream(counting)
        }
        decompressed.use { input ->
            val progressInput = object : FilterInputStream(input) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    val now = System.currentTimeMillis()
                    if (now - lastReport >= 500) {
                        lastReport = now
                        val frac = counting.count.toFloat() / total
                        reportBlocking(distro, STEP_EXTRACT, VERIFY_END + (EXTRACT_END - VERIFY_END) * frac.coerceIn(0f, 1f))
                    }
                    return n
                }
            }
            TarExtractor(target, AndroidFsOps).extract(progressInput) { !ctx.isActive }
        }
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        @Volatile var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }

    // ------------------------------------------------------------------ helpers

    private fun cleanupPartial(distro: Distro, keepDownload: Boolean) {
        runCatching { SafeDelete.deleteTree(paths.partialRootfs(distro)) }
        if (!keepDownload) {
            paths.download(distro).delete()
            File(paths.download(distro).path + ".url").delete()
        }
    }

    private fun checkSpace(need: Long) {
        val free = runCatching {
            val sm = applicationContext.getSystemService(StorageManager::class.java)
            sm.getAllocatableBytes(sm.getUuidForPath(paths.base.apply { mkdirs() }))
        }.getOrElse { paths.base.usableSpace }
        if (need > free) throw Failure("Not enough storage: this needs about ${mb(need)} free but only ${mb(free)} is available.")
    }

    private fun du(dir: File): Long {
        var total = 0L
        val stack = ArrayDeque<File>().apply { add(dir) }
        while (stack.isNotEmpty()) {
            val f = stack.removeLast()
            if (AndroidFsOps.isSymlink(f)) continue
            if (f.isDirectory) f.listFiles()?.let { stack.addAll(it) } else total += f.length()
        }
        return total
    }

    /** [local] is the progress within the current step; the published value covers the whole plan. */
    private suspend fun report(distro: Distro, step: String, local: Float, detail: String? = null) {
        val progress = overall(local)
        setProgress(progressData(step, progress))
        notifyForeground(distro, step, progress, detail)
    }

    private fun overall(local: Float) = ((stageIndex + local.coerceIn(0f, 1f)) / stageCount).coerceIn(0f, 1f)

    private fun progressData(step: String, progress: Float) = workDataOf(
        P_STEP to step,
        P_PROGRESS to progress,
        P_STAGE to stageTitle,
        P_STAGE_INDEX to stageIndex,
        P_STAGE_COUNT to stageCount,
    )

    /** For progress from inside blocking stream callbacks (WorkManager's progress store is thread-safe). */
    private fun reportBlocking(distro: Distro, step: String, local: Float) {
        val progress = overall(local)
        setProgressAsync(progressData(step, progress))
        val pct = (progress * 100).toInt()
        if (foregroundOk && pct != lastPct) {
            lastPct = pct
            runCatching { setForegroundAsync(foregroundInfo(distro, step, progress, null)) }
        }
    }

    private suspend fun notifyForeground(distro: Distro, step: String, progress: Float, detail: String?) {
        if (!foregroundOk) return
        val pct = (progress * 100).toInt()
        if (step == lastStep && pct == lastPct && detail == null) return
        lastStep = step
        lastPct = pct
        try {
            setForeground(foregroundInfo(distro, step, progress, detail))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Android may refuse a foreground start from the background; carry on as a normal worker.
            foregroundOk = false
        }
    }

    private fun foregroundInfo(distro: Distro, step: String, progress: Float, detail: String?): ForegroundInfo {
        ensureChannel()
        val ctx = applicationContext
        val cancel = WorkManager.getInstance(ctx).createCancelPendingIntent(id)
        val cancelAction = Notification.Action.Builder(
            Icon.createWithResource(ctx, android.R.drawable.ic_menu_close_clear_cancel),
            "Cancel",
            cancel,
        ).build()
        val builder = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(atom.ui.R.drawable.ic_stat_atom)
            .setContentTitle(if (stageCount > 1) "${distro.displayName}: $stageTitle (${stageIndex + 1}/$stageCount)" else "${distro.displayName}: $stageTitle")
            .setContentText(if (detail != null) "$step · $detail" else step)
            .setProgress(100, (progress * 100).toInt().coerceIn(0, 100), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .addAction(cancelAction)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return ForegroundInfo(NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun postDone(distro: Distro, steps: List<InstallStep>) {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel()
        val n = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(atom.ui.R.drawable.ic_stat_atom)
            .setContentTitle(if (steps.first() == InstallStep.Base) "${distro.displayName} is ready" else "${steps.last().title} finished")
            .setContentText("Open the terminal in Atom to use it.")
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID + 1, n) }
    }

    private fun ensureChannel() {
        val nm = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Linux install", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress while a Linux distribution is installed"
                    setShowBadge(false)
                },
            )
        }
    }

    private fun mb(bytes: Long): String = "%.1f MB".format(bytes / 1e6)
}
