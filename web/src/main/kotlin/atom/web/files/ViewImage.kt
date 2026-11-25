package atom.web.files

import android.content.Context
import android.net.Uri
import atom.core.llm.ContentPart
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolContext
import atom.core.tool.ToolResult
import atom.core.tool.optString
import atom.core.tool.schema
import atom.core.tool.string
import atom.web.BaseTool
import atom.web.PrivilegedFiles
import atom.web.image.ImageCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Lets the model look at one local image per call. */
internal class ViewImage(private val context: Context, priv: () -> PrivilegedFiles?) : BaseTool(
    "view_image",
    "Look at a local image: a file path or a content:// URI (e.g. a gallery item or attachment). One image per call; it is downscaled to at most 1568 px.",
    schema {
        string("path", "Image path or content:// URI", required = true)
        string("scope", "app (default) or root for paths only root can read", enum = listOf("app", "root", "linux"))
    },
    Risk.Read,
) {
    private val scopes = FileScopes(context, priv)

    override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
        val target = args.string("path").trim()
        val bytes = withContext(Dispatchers.IO) {
            if (target.startsWith("content://") || target.startsWith("file://")) {
                context.contentResolver.openInputStream(Uri.parse(target))?.use { it.readBytes() }
                    ?: return@withContext null
            } else {
                val fs = scopes.fs(args)
                val path = scopes.path(args, fs)
                val e = fs.stat(path) ?: return@withContext null
                if (e.size > 64L * 1024 * 1024) throw IllegalArgumentException("Image is too large (${Sizes.human(e.size)}).")
                fs.read(path, 64 * 1024 * 1024)
            }
        } ?: return ToolResult.error("Cannot open $target.")
        val original = ImageCodec.bounds(bytes) ?: return ToolResult.error("$target is not an image Android can decode.")
        val enc = withContext(Dispatchers.Default) { ImageCodec.forModel(bytes) } ?: return ToolResult.error("Could not decode $target.")
        return ToolResult(
            text = "Image $target (${original.first}×${original.second}, sent as ${enc.width}×${enc.height}).",
            images = listOf(ContentPart.Image(enc.mime, enc.base64)),
            sensitive = true,
        )
    }

    companion object {
        fun create(context: Context, priv: () -> PrivilegedFiles?): Tool = ViewImage(context.applicationContext, priv)
    }
}
