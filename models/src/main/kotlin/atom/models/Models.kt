package atom.models

/** Which on-device engine can load a model file. */
enum class ModelFormat {
    /** `.litertlm` bundle for LiteRT-LM (GPU/NPU, best battery). */
    LiteRtLm,

    /** `.gguf` file for llama.cpp. */
    Gguf;

    /** File extension (without the dot) used on disk for this format. */
    val extension: String
        get() = when (this) {
            LiteRtLm -> "litertlm"
            Gguf -> "gguf"
        }

    companion object {
        /** Detects the format from a file name or URL path, or null if it is neither. */
        fun fromFileName(name: String): ModelFormat? {
            val lower = name.substringBefore('?').substringBefore('#').lowercase()
            return when {
                lower.endsWith(".litertlm") -> LiteRtLm
                lower.endsWith(".gguf") -> Gguf
                else -> null
            }
        }
    }
}

/**
 * A model the user can download.
 *
 * @param sizeBytes exact file size, or 0 when unknown (e.g. a pasted link).
 * @param sha256 lowercase hex SHA-256 of the file when known; verified after download.
 * @param gated true when Hugging Face requires a token and an accepted license.
 */
data class CatalogModel(
    val id: String,
    val name: String,
    val format: ModelFormat,
    val url: String,
    val sizeBytes: Long,
    val sha256: String?,
    val minRamGb: Int,
    val license: String,
    val gated: Boolean,
    val summary: String,
    val supportsVision: Boolean,
)

/** A model that is fully downloaded, verified and ready for an engine to load from [path]. */
data class InstalledModel(
    val id: String,
    val name: String,
    val format: ModelFormat,
    val path: String,
    val sizeBytes: Long,
    val supportsVision: Boolean,
)

/** What the UI should show for one model. */
sealed interface DownloadState {
    data object Idle : DownloadState
    data object Queued : DownloadState
    data class Running(val downloadedBytes: Long, val totalBytes: Long) : DownloadState
    data object Verifying : DownloadState
    data object Done : DownloadState
    data class Failed(val message: String) : DownloadState
}
