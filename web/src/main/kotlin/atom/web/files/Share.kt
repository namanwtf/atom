package atom.web.files

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import atom.web.R
import java.io.File

/** Serves generated images, browser previews and copies of shared files. */
class WebFileProvider : FileProvider(R.xml.web_file_paths)

object Share {
    fun authority(context: Context) = context.packageName + ".web.files"

    /** A content URI for [file]; files outside this module's folders are copied into the share cache first. */
    fun uriFor(context: Context, file: File): Uri {
        val app = context.applicationContext
        val roots = listOf(File(app.filesDir, "atom4_generated_images"), File(app.cacheDir, "web_share"), File(app.cacheDir, "browser_previews"))
        val canonical = file.canonicalFile
        val served = if (roots.any { canonical.path.startsWith(it.canonicalPath + File.separator) }) canonical else {
            val dir = File(app.cacheDir, "web_share").apply { mkdirs() }
            dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 3600_000L }?.forEach { it.delete() }
            File(dir, file.name).also { file.copyTo(it, overwrite = true) }
        }
        return FileProvider.getUriForFile(app, authority(app), served)
    }

    fun intent(context: Context, files: List<File>, mime: String? = null): Intent {
        val uris = files.map { uriFor(context, it) }
        val type = mime ?: files.map { FileTools.mime(it.name) ?: "*/*" }.distinct().singleOrNull() ?: "*/*"
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        send.type = type
        send.clipData = ClipData.newUri(context.contentResolver, files.first().name, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun share(context: Context, files: List<File>, mime: String? = null) {
        if (files.isEmpty()) return
        context.startActivity(intent(context, files, mime))
    }

    /** Opens a file with the app the user picks. */
    fun open(context: Context, file: File) {
        val uri = uriFor(context, file)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, FileTools.mime(file.name) ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(Intent.createChooser(view, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
