package atom.app.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import atom.core.llm.ContentPart
import java.io.File
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns what the user picked or shared into attachments. Images are downscaled copies kept by the app (they are
 * sent as pixels); files and folders become filesystem paths the agent opens with its own file and terminal tools.
 */
object Attachments {
    const val MAX_IMAGES = 4
    const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
    private const val MAX_IMAGE_SIDE = 1568
    private const val JPEG_QUALITY = 85

    sealed interface Outcome {
        data class Added(val attachment: Attachment) : Outcome
        data class Rejected(val reason: Reason) : Outcome
    }

    enum class Reason { TooLarge, Unreadable }

    /** Where copies of shared files land; the agent can read them without extra permissions. */
    fun sharedDir(context: Context): File = File(context.filesDir, "shared").apply { mkdirs() }

    private fun imageDir(context: Context): File = File(context.filesDir, "attachments").apply { mkdirs() }

    suspend fun importImage(context: Context, uri: Uri): Outcome = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val size = sizeOf(resolver, uri)
        if (size != null && size > MAX_IMAGE_BYTES) return@withContext Outcome.Rejected(Reason.TooLarge)
        val name = displayName(resolver, uri)
        saveDownscaled(context, ImageDecoder.createSource(resolver, uri), name)
    }

    /** Decodes [source] at most [MAX_IMAGE_SIDE] px on the long side and keeps a JPEG copy for the model. */
    private fun saveDownscaled(context: Context, source: ImageDecoder.Source, name: String?): Outcome {
        val bitmap = runCatching {
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = minOf(1f, MAX_IMAGE_SIDE.toFloat() / maxOf(w, h))
                if (scale < 1f) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull() ?: return Outcome.Rejected(Reason.Unreadable)
        val file = File(imageDir(context), UUID.randomUUID().toString() + ".jpg")
        val written = runCatching { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) } }.isSuccess
        bitmap.recycle()
        if (!written) {
            file.delete()
            return Outcome.Rejected(Reason.Unreadable)
        }
        return Outcome.Added(Attachment(AttachmentKind.Image, file.absolutePath, name ?: file.name, file.length()))
    }

    /** True for a JPEG or PNG file path (by extension). */
    fun isImagePath(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

    /**
     * An absolute path handed to the runtime (`AgentHost.start(attachments = ...)`): JPEG and PNG files become image
     * attachments (a downscaled copy, sent as pixels); everything else is a path reference. Null for a relative path.
     */
    suspend fun fromPathForModel(context: Context, path: String): Attachment? = withContext(Dispatchers.IO) {
        val clean = path.trim()
        val file = File(clean)
        if (clean.startsWith("/") && file.isFile && isImagePath(clean) && file.length() <= MAX_IMAGE_BYTES) {
            val outcome = saveDownscaled(context, ImageDecoder.createSource(file), file.name)
            if (outcome is Outcome.Added) return@withContext outcome.attachment
        }
        fromPath(clean)
    }

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png")

    /** A picked or shared document: its real path when the provider exposes one, otherwise a private copy. */
    suspend fun importFile(context: Context, uri: Uri): Outcome = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = displayName(resolver, uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val path = uri.path ?: return@withContext Outcome.Rejected(Reason.Unreadable)
            return@withContext Outcome.Added(Attachment(AttachmentKind.File, path, name, File(path).length()))
        }
        documentPath(uri)?.let { path ->
            if (File(path).canRead()) return@withContext Outcome.Added(Attachment(AttachmentKind.File, path, name, File(path).length()))
        }
        val target = uniqueFile(sharedDir(context), name)
        val copied = runCatching {
            resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
        }.getOrNull()
        if (copied == null) {
            target.delete()
            return@withContext Outcome.Rejected(Reason.Unreadable)
        }
        Outcome.Added(Attachment(AttachmentKind.File, target.absolutePath, name, target.length()))
    }

    /** A folder from the system picker; only local storage folders map to a path. */
    fun importFolder(treeUri: Uri): Attachment? {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        val path = volumePath(treeUri.authority, docId) ?: return null
        return Attachment(AttachmentKind.Folder, path, path.substringAfterLast('/').ifEmpty { path })
    }

    fun fromPath(path: String): Attachment? {
        val clean = path.trim()
        if (!clean.startsWith("/")) return null
        val file = File(clean)
        val kind = if (file.isDirectory) AttachmentKind.Folder else AttachmentKind.Path
        return Attachment(kind, clean, file.name.ifEmpty { clean }, file.takeIf { it.isFile }?.length())
    }

    /** The user-message text the model sees for [attachments] besides images. */
    fun describeForModel(attachments: List<Attachment>): String? {
        val refs = attachments.filter { it.kind != AttachmentKind.Image }
        val images = attachments.filter { it.kind == AttachmentKind.Image }
        if (refs.isEmpty() && images.isEmpty()) return null
        return buildString {
            if (refs.isNotEmpty()) {
                append("[Attached paths. They are not uploaded: read them with your file or terminal tools.]")
                refs.forEach { a ->
                    append("\n- ").append(a.path).append(" (").append(
                        when (a.kind) {
                            AttachmentKind.Folder -> "folder"
                            AttachmentKind.File -> "file"
                            else -> "path"
                        },
                    )
                    a.sizeBytes?.let { append(", ").append(it).append(" bytes") }
                    append(')')
                }
            }
            if (images.isNotEmpty()) {
                if (refs.isNotEmpty()) append('\n')
                append("[").append(images.size).append(" image(s) attached")
                append(images.joinToString(prefix = "; local copies: ", separator = ", ") { it.path })
                append(']')
            }
        }
    }

    suspend fun loadImage(path: String): ContentPart.Image? = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.isFile || file.length() > MAX_IMAGE_BYTES) return@withContext null
        runCatching { ContentPart.Image("image/jpeg", Base64.getEncoder().encodeToString(file.readBytes())) }.getOrNull()
    }

    fun deleteImages(attachments: List<Attachment>) {
        attachments.filter { it.kind == AttachmentKind.Image }.forEach { runCatching { File(it.path).delete() } }
    }

    private fun documentPath(uri: Uri): String? {
        if (uri.authority != EXTERNAL_STORAGE && uri.authority != DOWNLOADS) return null
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        if (uri.authority == DOWNLOADS && docId.startsWith("raw:")) return docId.removePrefix("raw:")
        return volumePath(uri.authority, docId)
    }

    @Suppress("DEPRECATION")
    private fun volumePath(authority: String?, docId: String): String? {
        if (authority != EXTERNAL_STORAGE) return null
        val volume = docId.substringBefore(':')
        val rel = docId.substringAfter(':', "")
        val root = if (volume.equals("primary", ignoreCase = true)) Environment.getExternalStorageDirectory().absolutePath else "/storage/$volume"
        return if (rel.isEmpty()) root else "$root/$rel"
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun sizeOf(resolver: ContentResolver, uri: Uri): Long? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()

    private fun uniqueFile(dir: File, name: String): File {
        val safe = name.replace(Regex("[/\\\\:*?\"<>|]"), "_").take(120).ifBlank { "file" }
        var f = File(dir, safe)
        var n = 1
        while (f.exists()) {
            f = File(dir, safe.substringBeforeLast('.') + " ($n)" + safe.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" })
            n++
        }
        return f
    }

    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
    private const val DOWNLOADS = "com.android.providers.downloads.documents"
}
