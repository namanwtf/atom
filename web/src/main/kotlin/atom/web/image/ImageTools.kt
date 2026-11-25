package atom.web.image

import android.content.Context
import android.net.Uri
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolArgumentException
import atom.core.tool.ToolContext
import atom.core.tool.ToolResult
import atom.core.tool.optInt
import atom.core.tool.optString
import atom.core.tool.schema
import atom.core.tool.string
import atom.web.BaseTool
import atom.web.JsonPref
import atom.web.Web
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File

internal object ImageSettings {
    @Volatile
    private var pref: JsonPref<ImageConfig>? = null

    fun of(context: Context): JsonPref<ImageConfig> =
        pref ?: synchronized(this) {
            pref ?: JsonPref(context, "image_config", ImageConfig.serializer(), ImageConfig()).also { pref = it }
        }
}

internal object ImageTools {
    fun create(context: Context, generator: ImageGenerator, gate: () -> String?): List<Tool> =
        listOf(Generate(context, generator, gate), Edit(context, generator, gate))

    private fun withImages(base: JsonObject): JsonObject {
        val props = base["properties"]!!.jsonObject.toMutableMap()
        props["images"] = JsonObject(
            mapOf(
                "type" to JsonPrimitive("array"),
                "items" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "description" to JsonPrimitive("Input images: file paths (e.g. from an earlier generate_image result) or content:// URIs"),
            ),
        )
        val required = ((base["required"] as? JsonArray)?.toList() ?: emptyList()) + JsonPrimitive("images")
        return JsonObject(base + mapOf("properties" to JsonObject(props), "required" to JsonArray(required)))
    }

    private val common = schema {
        string("prompt", "What to draw or change, in detail", required = true)
        integer("count", "Number of images, 1-4 (default 1)")
        string("aspect_ratio", "Aspect ratio (default from settings)", enum = ImagePresets.ASPECTS)
        string("resolution", "1K, 2K or 4K (default from settings; some models fix it)", enum = listOf("1K", "2K", "4K"))
    }

    private fun report(job: ImageJob): ToolResult {
        val head = "${ImageJobs.MARKER}${job.id}\n"
        return when (job.state) {
            JobState.Failed -> ToolResult(head + (job.error ?: "Image generation failed."), isError = true)
            else -> ToolResult.ok(
                head + "${job.title} with ${job.model}. They are shown to the user in the chat; refer to them by path for edit_image:\n" +
                    job.images.joinToString("\n") { "- ${it.file} (${it.width}×${it.height})" },
            )
        }
    }

    private class Generate(private val context: Context, private val gen: ImageGenerator, gate: () -> String?) : BaseTool(
        "generate_image",
        "Create images from a text prompt with the user's image model. The images appear in the chat automatically; do not describe them back in full.",
        common,
        Risk.Act,
        gate,
    ) {
        override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
            val req = gen.request(
                args.string("prompt"), args.optInt("count") ?: 1, args.optString("aspect_ratio"), args.optString("resolution"), null, emptyList(),
            )
            return report(gen.run(req, Web.host.conversationOf(ctx.taskId)))
        }
    }

    private class Edit(private val context: Context, private val gen: ImageGenerator, gate: () -> String?) : BaseTool(
        "edit_image",
        "Edit or combine existing images following a prompt (change style, add or remove things, merge references). Results appear in the chat.",
        withImages(common),
        Risk.Act,
        gate,
    ) {
        override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
            val list = when (val el = args["images"]) {
                is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.content }
                is JsonPrimitive -> el.content.split('\n', ',').map { it.trim() }
                else -> emptyList()
            }.filter { it.isNotBlank() }
            if (list.isEmpty()) throw ToolArgumentException("Give at least one input image in 'images'.")
            val refs = withContext(Dispatchers.IO) { list.take(14).map { load(it) } }
            val req = gen.request(
                args.string("prompt"), args.optInt("count") ?: 1, args.optString("aspect_ratio"), args.optString("resolution"), null, refs,
            )
            return report(gen.run(req, Web.host.conversationOf(ctx.taskId)))
        }

        private fun load(ref: String): RefImage {
            val bytes = if (ref.startsWith("content://") || ref.startsWith("file://")) {
                context.contentResolver.openInputStream(Uri.parse(ref))?.use { it.readBytes() }
            } else {
                File(ref).takeIf { it.isFile }?.readBytes()
            } ?: throw ToolArgumentException("Cannot open image $ref")
            val enc = ImageCodec.forModel(bytes, 2048) ?: throw ToolArgumentException("$ref is not a readable image")
            return RefImage(enc.mime, enc.base64)
        }
    }
}
