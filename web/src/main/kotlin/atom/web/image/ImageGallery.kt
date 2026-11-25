package atom.web.image

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import atom.web.PrefsJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

@Serializable
data class GalleryItem(
    val file: String,
    val prompt: String,
    val model: String,
    val backend: String,
    val createdAt: Long,
    val width: Int = 0,
    val height: Int = 0,
    val edit: Boolean = false,
    val bytes: Long = 0,
) {
    val name: String get() = file.substringAfterLast('/')
}

/** Generated images, kept in app storage with a small JSON index (prompt, model, size). */
object ImageGallery {
    private val lock = Mutex()
    private val state = MutableStateFlow<List<GalleryItem>>(emptyList())
    @Volatile
    private var loaded = false

    val items: StateFlow<List<GalleryItem>> = state.asStateFlow()

    fun dir(context: Context) = File(context.applicationContext.filesDir, "generated_images").apply { mkdirs() }

    private fun index(context: Context) = File(dir(context), "index.json")

    suspend fun load(context: Context): List<GalleryItem> = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!loaded) {
                val f = index(context)
                val list = if (f.exists()) runCatching { PrefsJson.decodeFromString(ListSerializer(GalleryItem.serializer()), f.readText()) }.getOrDefault(emptyList()) else emptyList()
                state.value = list.filter { File(it.file).exists() }
                loaded = true
            }
            state.value
        }
    }

    private fun persist(context: Context, list: List<GalleryItem>) {
        val f = index(context)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(PrefsJson.encodeToString(ListSerializer(GalleryItem.serializer()), list))
        tmp.renameTo(f)
    }

    suspend fun add(context: Context, bytes: ByteArray, prompt: String, model: String, backend: ImageBackend, edit: Boolean): GalleryItem =
        withContext(Dispatchers.IO) {
            load(context)
            val ext = when (ImageCodec.mimeOf(bytes)) {
                "image/jpeg" -> "jpg"
                "image/webp" -> "webp"
                else -> "png"
            }
            lock.withLock {
                val now = System.currentTimeMillis()
                var file = File(dir(context), "atom_$now.$ext")
                var k = 1
                while (file.exists()) file = File(dir(context), "atom_${now}_${k++}.$ext")
                file.writeBytes(bytes)
                val (w, h) = ImageCodec.bounds(bytes) ?: (0 to 0)
                val item = GalleryItem(file.path, prompt, model, backend.label, now, w, h, edit, bytes.size.toLong())
                state.update { listOf(item) + it }
                persist(context, state.value)
                item
            }
        }

    suspend fun delete(context: Context, item: GalleryItem) = withContext(Dispatchers.IO) {
        lock.withLock {
            File(item.file).delete()
            state.update { list -> list.filterNot { it.file == item.file } }
            persist(context, state.value)
        }
    }

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        lock.withLock {
            dir(context).listFiles()?.forEach { it.delete() }
            state.value = emptyList()
            persist(context, emptyList())
        }
    }

    fun find(path: String): GalleryItem? = state.value.firstOrNull { it.file == path }
}

object ImageSaver {
    /** Copies [file] into Pictures/Atom so it shows in the gallery apps. */
    suspend fun toPictures(context: Context, file: File): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mime = when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> "image/png"
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Atom")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IllegalStateException("Could not create the picture")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: throw IllegalStateException("Could not write the picture")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
