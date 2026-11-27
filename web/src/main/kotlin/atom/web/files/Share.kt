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

    /** A content URI for [file]; see [urisFor]. */
    fun uriFor(context: Context, file: File): Uri = urisFor(context, listOf(file)).single()

    /**
     * Content URIs for [files], shared together. Files in a folder this provider serves (generated images, browser
     * previews, the problem report) are served where they are; any other file is copied into the share cache first.
     * Whenever the share cache is used, everything else in it is deleted: only what is being shared now stays.
     */
    fun urisFor(context: Context, files: List<File>): List<Uri> {
        val app = context.applicationContext
        val share = File(app.cacheDir, SHARE_DIR)
        val roots = listOf(File(app.filesDir, "atom4_generated_images"), share, File(app.cacheDir, "browser_previews"), File(app.cacheDir, "reports"))
        fun inside(f: File, dir: File) = f.path.startsWith(dir.canonicalPath + File.separator)
        val served = files.map { file ->
            val canonical = file.canonicalFile
            if (roots.any { inside(canonical, it) }) canonical else {
                share.mkdirs()
                File(share, file.name).also { file.copyTo(it, overwrite = true) }.canonicalFile
            }
        }
        if (served.any { inside(it, share) }) {
            share.listFiles()?.filter { f -> served.none { it.path == f.canonicalPath } }?.forEach { it.delete() }
        }
        return served.map { FileProvider.getUriForFile(app, authority(app), it) }
    }

    private const val SHARE_DIR = "web_share"

    fun intent(context: Context, files: List<File>, mime: String? = null): Intent {
        val uris = urisFor(context, files)
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
