package atom.models

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
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicReference

/**
 * Downloads one model file into `filesDir/atom4_models/<id>.<ext>.part`, resuming with HTTP Range,
 * then verifies SHA-256, renames atomically and records it in the manifest.
 */
class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val MAX_ATTEMPTS = 5

        internal const val KEY_ERROR = "error"
        private const val KEY_ID = "id"
        private const val KEY_NAME = "name"
        private const val KEY_FORMAT = "format"
        private const val KEY_URL = "url"
        private const val KEY_SIZE = "size"
        private const val KEY_SHA = "sha256"
        private const val KEY_VISION = "vision"
        private const val KEY_GATED = "gated"

        private const val P_PHASE = "phase"
        private const val P_DONE = "done"
        private const val P_TOTAL = "total"
        private const val PHASE_DOWNLOAD = "download"
        private const val PHASE_VERIFY = "verify"

        private const val CHANNEL_ID = "atom_model_downloads"

        const val TOKEN_REQUIRED_MESSAGE =
            "This model needs a Hugging Face token. Create a read token at huggingface.co/settings/tokens, " +
                "accept the model's license on its page, add the token in Atom, then tap download again."

        /** Everything the worker needs, minus the token (which never enters WorkManager's database). */
        internal fun inputFor(model: CatalogModel): Data = workDataOf(
            KEY_ID to model.id,
            KEY_NAME to model.name,
            KEY_FORMAT to model.format.name,
            KEY_URL to model.url,
            KEY_SIZE to model.sizeBytes,
            KEY_SHA to model.sha256,
            KEY_VISION to model.supportsVision,
            KEY_GATED to model.gated,
        )

        internal fun progressState(progress: Data): DownloadState =
            if (progress.getString(P_PHASE) == PHASE_VERIFY) {
                DownloadState.Verifying
            } else {
                DownloadState.Running(progress.getLong(P_DONE, 0L), progress.getLong(P_TOTAL, 0L))
            }
    }

    private data class Spec(
        val id: String,
        val name: String,
        val format: ModelFormat,
        val url: String,
        val size: Long,
        val sha256: String?,
        val vision: Boolean,
        val gated: Boolean,
    )

    /** A permanent problem: reported to the UI as [DownloadState.Failed], never retried. */
    private class Failure(message: String) : Exception(message)

    private val store = ModelStore.get(applicationContext)
    private val notificationId: Int = 0x4D000000 or ((inputData.getString(KEY_ID) ?: "").hashCode() and 0x00FFFFFF)
    private var foregroundOk = true

    override suspend fun doWork(): Result {
        val spec = readSpec() ?: return Result.failure(workDataOf(KEY_ERROR to "Invalid download request."))
        val part = store.partFileFor(spec.id, spec.format)
        val target = store.fileFor(spec.id, spec.format)

        return try {
            withContext(Dispatchers.IO) { run(spec, part, target) }
            Result.success()
        } catch (e: Failure) {
            Result.failure(workDataOf(KEY_ERROR to e.message))
        } catch (e: CancellationException) {
            discardIfUserCancelled(part)
            throw e
        } catch (e: IOException) {
            if (isStopped) {
                // Our own disconnect() after a stop surfaces as an IOException; WorkManager ignores the result.
                discardIfUserCancelled(part)
                Result.retry()
            } else if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
                Result.failure(
                    workDataOf(
                        KEY_ERROR to "Network problem (${e.message ?: e.javaClass.simpleName}). " +
                            "Gave up after $MAX_ATTEMPTS tries; tap download to resume.",
                    ),
                )
            } else {
                Result.retry() // exponential backoff; the next attempt resumes from the .part file
            }
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Download failed.")))
        }
    }

    /** A user cancel discards the partial file; a system stop (lost Wi-Fi, low storage) keeps it for resume. */
    private fun discardIfUserCancelled(part: File) {
        // On API 30 the reason is unavailable; ModelStore.cancel()/delete() remove the .part themselves.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP) {
            part.delete()
        }
    }

    /**
     * Opens the connection and runs [block] on it. If this coroutine is cancelled, the connection is
     * disconnected from another thread, which unblocks any socket read in progress.
     */
    private suspend fun <T> withConnection(
        url: String,
        offset: Long,
        token: String?,
        block: suspend (HttpURLConnection) -> T,
    ): T = coroutineScope {
        val active = AtomicReference<HttpURLConnection?>(null)
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                active.get()?.disconnect()
            }
        }
        try {
            val conn = ModelHttp.open(url, offset, token) { active.set(it) }
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

    private suspend fun run(spec: Spec, part: File, target: File) {
        store.modelsDir.mkdirs()
        // Recover from a crash between the rename and the manifest write.
        if (target.isFile && (spec.size <= 0 || target.length() == spec.size) && DownloadIo.verifySha256(target, spec.sha256)) {
            install(spec, target)
            return
        }

        var offset = DownloadIo.resumeOffset(if (part.exists()) part.length() else 0L, spec.size)
        if (offset == 0L && part.exists()) part.delete()

        val token = store.tokenFor(spec.id)
        if (spec.gated && token == null) throw Failure(TOKEN_REQUIRED_MESSAGE)

        checkSpace(spec.size, offset) // early check when the size is known up front
        publish(spec, offset, spec.size) // also promotes the worker to a foreground service

        var restarted = false
        while (true) {
            val complete: Boolean = withConnection(spec.url, offset, token) { conn ->
                val plan = DownloadIo.planFor(
                    requestedOffset = offset,
                    code = conn.responseCode,
                    contentRange = conn.getHeaderField("Content-Range"),
                    contentLength = conn.contentLengthLong,
                )
                when (plan) {
                    is DownloadIo.Plan.Write -> {
                        val total = if (plan.total > 0) plan.total else spec.size
                        checkSpace(total, plan.offset)
                        writeBody(spec, conn, part, plan.offset, total)
                        val have = part.length()
                        if (total > 0 && have != total) throw IOException("connection closed early ($have of $total bytes)")
                        true
                    }
                    is DownloadIo.Plan.AlreadyComplete -> true
                    DownloadIo.Plan.RestartFromZero -> {
                        if (restarted) throw Failure("The server keeps sending the wrong part of the file.")
                        restarted = true
                        part.delete()
                        offset = 0L
                        false
                    }
                    is DownloadIo.Plan.Unauthorized -> throw Failure(
                        if (token == null) {
                            TOKEN_REQUIRED_MESSAGE
                        } else {
                            "Hugging Face refused the token (HTTP ${plan.code}). Accept the model's license on its " +
                                "Hugging Face page and check that the token has read access."
                        },
                    )
                    is DownloadIo.Plan.Transient -> throw IOException("server busy, HTTP ${plan.code}")
                    is DownloadIo.Plan.Fatal -> throw Failure(plan.message)
                }
            }
            if (complete) {
                finish(spec, part, target)
                return
            }
        }
    }

    private suspend fun writeBody(spec: Spec, conn: HttpURLConnection, part: File, offset: Long, total: Long) {
        val throttle = DownloadIo.Throttle(DownloadIo.PROGRESS_INTERVAL_MS)
        // offset == 0 truncates; otherwise append to the bytes already on disk.
        FileOutputStream(part, offset > 0).use { out ->
            conn.inputStream.use { input ->
                DownloadIo.copyWithProgress(input, out, offset, throttle) { done -> publish(spec, done, total) }
            }
            out.fd.sync()
        }
    }

    private suspend fun finish(spec: Spec, part: File, target: File) {
        if (spec.sha256 != null) {
            setProgress(workDataOf(P_PHASE to PHASE_VERIFY))
            notifyForeground(spec, "Checking file integrity…", 0, 0, indeterminate = true)
            currentCoroutineContext().ensureActive()
            if (!DownloadIo.verifySha256(part, spec.sha256)) {
                part.delete()
                throw Failure("The download was corrupted (checksum mismatch) and has been deleted. Tap download to try again.")
            }
        }
        DownloadIo.promote(part, target)
        install(spec, target)
    }

    private fun install(spec: Spec, target: File) {
        store.markInstalled(
            ManifestEntry(
                id = spec.id,
                name = spec.name,
                format = spec.format,
                fileName = target.name,
                sizeBytes = target.length(),
                supportsVision = spec.vision,
                sha256 = spec.sha256,
                sourceUrl = spec.url,
                installedAtMs = System.currentTimeMillis(),
            ),
        )
        postResult("${spec.name} is ready", "Downloaded and verified. It works offline now.")
    }

    private fun checkSpace(total: Long, have: Long) {
        val need = DownloadIo.requiredFreeBytes(total, have)
        val free = freeBytes(store.modelsDir)
        if (need > free) {
            throw Failure("Not enough storage: this model needs ${gb(need)} free but only ${gb(free)} is available.")
        }
    }

    /** Free space including cache the system may clear for us; falls back to plain usable space. */
    private fun freeBytes(dir: File): Long = runCatching {
        val sm = applicationContext.getSystemService(StorageManager::class.java)
        sm.getAllocatableBytes(sm.getUuidForPath(dir))
    }.getOrElse { dir.usableSpace }

    /** Called at most ~2x per second (see [DownloadIo.Throttle]). */
    private suspend fun publish(spec: Spec, done: Long, total: Long) {
        setProgress(workDataOf(P_PHASE to PHASE_DOWNLOAD, P_DONE to done, P_TOTAL to total))
        val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
        val text = if (total > 0) "${gb(done)} of ${gb(total)}" else gb(done)
        notifyForeground(spec, text, 100, pct, indeterminate = total <= 0)
    }

    private suspend fun notifyForeground(spec: Spec, text: String, max: Int, value: Int, indeterminate: Boolean) {
        if (!foregroundOk) return
        try {
            setForeground(foregroundInfo(spec, text, max, value, indeterminate))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Android can refuse to start a foreground service from the background. Keep going as a
            // normal worker; if the system stops us, the retry resumes from the .part file.
            foregroundOk = false
        }
    }

    private fun foregroundInfo(spec: Spec, text: String, max: Int, value: Int, indeterminate: Boolean): ForegroundInfo {
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
            .setColor(android.graphics.Color.BLACK)
            .setContentTitle("Downloading ${spec.name}")
            .setContentText(text)
            .setProgress(max, value, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .addAction(cancelAction)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return ForegroundInfo(notificationId, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun postResult(title: String, text: String) {
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
            .setColor(android.graphics.Color.BLACK)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(notificationId + 1, n) }
    }

    private fun ensureChannel() {
        val nm = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress of on-device AI model downloads"
                    setShowBadge(false)
                },
            )
        }
    }

    private fun readSpec(): Spec? {
        val id = inputData.getString(KEY_ID) ?: return null
        val url = inputData.getString(KEY_URL) ?: return null
        val format = inputData.getString(KEY_FORMAT)?.let { f -> ModelFormat.entries.firstOrNull { it.name == f } }
            ?: return null
        return Spec(
            id = id,
            name = inputData.getString(KEY_NAME) ?: id,
            format = format,
            url = url,
            size = inputData.getLong(KEY_SIZE, 0L),
            sha256 = inputData.getString(KEY_SHA)?.takeIf { it.isNotBlank() },
            vision = inputData.getBoolean(KEY_VISION, false),
            gated = inputData.getBoolean(KEY_GATED, false),
        )
    }

    private fun gb(bytes: Long): String =
        if (bytes >= 1_000_000_000L) "%.2f GB".format(bytes / 1e9) else "%.0f MB".format(bytes / 1e6)
}
