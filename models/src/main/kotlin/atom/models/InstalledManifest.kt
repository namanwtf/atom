package atom.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

/** On-disk record of one installed model (paths are relative to the models directory). */
@Serializable
internal data class ManifestEntry(
    val id: String,
    val name: String,
    val format: ModelFormat,
    val fileName: String,
    val sizeBytes: Long,
    val supportsVision: Boolean,
    val sha256: String? = null,
    val sourceUrl: String,
    val installedAtMs: Long,
)

@Serializable
internal data class ManifestFile(val version: Int = 1, val models: List<ManifestEntry> = emptyList())

/**
 * `models/manifest.json`: the list of fully installed models, so [ModelStore.installed] survives
 * restarts. Writes go to a temp file followed by an atomic rename, so a crash never leaves a torn file.
 */
internal class InstalledManifest(private val dir: File) {

    private val file = File(dir, "manifest.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    /** Entries whose model file still exists with the recorded size. */
    @Synchronized
    fun read(): List<ManifestEntry> {
        if (!file.exists()) return emptyList()
        val parsed = runCatching { json.decodeFromString(ManifestFile.serializer(), file.readText()) }.getOrNull()
            ?: return emptyList()
        return parsed.models.filter { e ->
            val f = File(dir, e.fileName)
            f.isFile && (e.sizeBytes <= 0 || f.length() == e.sizeBytes)
        }
    }

    @Synchronized
    fun upsert(entry: ManifestEntry): List<ManifestEntry> {
        val next = read().filterNot { it.id == entry.id } + entry
        write(next)
        return next
    }

    @Synchronized
    fun remove(id: String): Pair<ManifestEntry?, List<ManifestEntry>> {
        val current = read()
        val removed = current.firstOrNull { it.id == id }
        val next = current.filterNot { it.id == id }
        write(next)
        return removed to next
    }

    private fun write(entries: List<ManifestEntry>) {
        dir.mkdirs()
        val tmp = File(dir, "manifest.json.tmp")
        val bytes = json.encodeToString(ManifestFile.serializer(), ManifestFile(models = entries)).toByteArray()
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        DownloadIo.promote(tmp, file)
    }

    fun toInstalled(e: ManifestEntry) = InstalledModel(
        id = e.id,
        name = e.name,
        format = e.format,
        path = File(dir, e.fileName).absolutePath,
        sizeBytes = e.sizeBytes,
        supportsVision = e.supportsVision,
    )
}
