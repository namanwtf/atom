package atom.models

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * One-tap model manager: downloads catalog models in the background (WorkManager + a dataSync
 * foreground notification), resumes interrupted transfers, verifies checksums and keeps a manifest
 * of what is installed under `filesDir/models`.
 */
class ModelStore private constructor(context: Context) {

    companion object {
        @Volatile
        private var instance: ModelStore? = null

        fun get(context: Context): ModelStore =
            instance ?: synchronized(this) {
                instance ?: ModelStore(context.applicationContext).also { instance = it }
            }

        internal const val WORK_PREFIX = "atom-model-download:"
        internal const val TAG = "atom-model-download"
        internal fun workName(modelId: String) = WORK_PREFIX + modelId
    }

    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal val modelsDir: File = File(appContext.filesDir, "models").apply { mkdirs() }
    private val manifest = InstalledManifest(modelsDir)

    /** Tokens live only in memory for the lifetime of the process; never written to disk or WorkManager. */
    private val tokens = ConcurrentHashMap<String, String>()

    // The manifest is a few hundred bytes; reading it once here keeps `installed` correct from the first frame.
    private val _installed = MutableStateFlow(manifest.read().map(manifest::toInstalled))

    /** Models that are fully downloaded and verified. Survives restarts via `models/manifest.json`. */
    val installed: StateFlow<List<InstalledModel>> = _installed.asStateFlow()

    /**
     * Queues [model] for download. Safe to call repeatedly: an already running or queued download
     * for the same id is kept, and an installed model is left alone.
     *
     * @param hfToken Hugging Face read token, sent only to huggingface.co, kept only in memory.
     * @param wifiOnly wait for an unmetered network (default) instead of any connection.
     */
    fun download(model: CatalogModel, hfToken: String? = null, wifiOnly: Boolean = true) {
        if (_installed.value.any { it.id == model.id }) return
        if (!hfToken.isNullOrBlank()) tokens[model.id] = hfToken.trim() else tokens.remove(model.id)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()

        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(ModelDownloadWorker.inputFor(model))
            .addTag(TAG)
            .addTag("$TAG:${model.id}")
            .build()

        workManager.enqueueUniqueWork(workName(model.id), ExistingWorkPolicy.KEEP, request)
    }

    /** Parses a pasted link (direct URL, Hugging Face file URL, or `hf:org/repo/file`). */
    fun modelFromUrl(url: String): Result<CatalogModel> = ModelUrls.parse(url, ModelCatalog.curated)

    /** Live state for one model, correct across app restarts. */
    fun progress(modelId: String): Flow<DownloadState> =
        combine(
            workManager.getWorkInfosForUniqueWorkFlow(workName(modelId)),
            installed.map { list -> list.any { it.id == modelId } }.distinctUntilChanged(),
        ) { infos, isInstalled -> stateFor(infos.lastOrNull(), isInstalled) }
            .distinctUntilChanged()

    /** Stops a queued or running download and discards the partial file. */
    fun cancel(modelId: String) {
        tokens.remove(modelId)
        scope.launch {
            runCatching { workManager.cancelUniqueWork(workName(modelId)).await() }
            if (_installed.value.none { it.id == modelId }) deletePartials(modelId)
        }
    }

    /** Cancels any download and removes the model's files and manifest entry. */
    suspend fun delete(modelId: String) = withContext(Dispatchers.IO + NonCancellable) {
        tokens.remove(modelId)
        runCatching { workManager.cancelUniqueWork(workName(modelId)).await() }
        val (removed, remaining) = manifest.remove(modelId)
        removed?.let { File(modelsDir, it.fileName).delete() }
        deletePartials(modelId)
        _installed.value = remaining.map(manifest::toInstalled)
    }

    // ---- used by ModelDownloadWorker ----

    internal fun tokenFor(modelId: String): String? = tokens[modelId]

    internal fun fileFor(modelId: String, format: ModelFormat) =
        File(modelsDir, ModelUrls.slug(modelId, 120) + "." + format.extension)

    internal fun partFileFor(modelId: String, format: ModelFormat) =
        File(modelsDir, ModelUrls.slug(modelId, 120) + "." + format.extension + ".part")

    internal fun markInstalled(entry: ManifestEntry) {
        val all = manifest.upsert(entry)
        _installed.value = all.map(manifest::toInstalled)
        tokens.remove(entry.id)
    }

    internal fun deletePartials(modelId: String) {
        ModelFormat.entries.forEach { partFileFor(modelId, it).delete() }
    }

    private fun stateFor(info: WorkInfo?, isInstalled: Boolean): DownloadState {
        if (info == null) return if (isInstalled) DownloadState.Done else DownloadState.Idle
        return when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadState.Queued
            WorkInfo.State.RUNNING -> ModelDownloadWorker.progressState(info.progress)
            WorkInfo.State.SUCCEEDED -> if (isInstalled) DownloadState.Done else DownloadState.Idle
            WorkInfo.State.FAILED -> if (isInstalled) DownloadState.Done else DownloadState.Failed(
                info.outputData.getString(ModelDownloadWorker.KEY_ERROR) ?: "Download failed.",
            )
            WorkInfo.State.CANCELLED -> if (isInstalled) DownloadState.Done else DownloadState.Idle
        }
    }
}
