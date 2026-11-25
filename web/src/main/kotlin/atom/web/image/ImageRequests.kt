package atom.web.image

import atom.web.net.HttpSpec
import atom.web.search.JsonPath
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class RefImage(val mime: String, val base64: String) {
    val dataUri: String get() = "data:$mime;base64,$base64"
    fun bytes(): ByteArray = java.util.Base64.getDecoder().decode(base64)
}

data class ImageRequest(
    val prompt: String,
    val count: Int = 1,
    val aspect: String = "auto",
    val quality: Quality = Quality.Standard,
    val resolution: String = "1K",
    val refs: List<RefImage> = emptyList(),
    val seedreamWatermark: Boolean = false,
    val qwenPromptExtend: Boolean = true,
    val qwenWatermark: Boolean = false,
) {
    val edit: Boolean get() = refs.isNotEmpty()
}

/** Where and how to call: resolved model, base URL and key. */
data class ImageTarget(
    val backend: ImageBackend,
    val model: String,
    val baseUrl: String,
    val apiKey: String,
    val vertex: Boolean = false,
    val vertexProject: String = "",
    val vertexLocation: String = "global",
    val region: Region = Region.China,
)

sealed interface ImageOut {
    data class Inline(val mime: String, val base64: String) : ImageOut
    data class Remote(val url: String) : ImageOut
}

class ImageApiException(message: String) : Exception(message)

object ImageEndpoints {
    fun defaultBase(backend: ImageBackend, region: Region, vertex: Boolean = false): String = when (backend) {
        ImageBackend.Gemini -> if (vertex) "https://aiplatform.googleapis.com" else "https://generativelanguage.googleapis.com/v1beta"
        ImageBackend.OpenAI -> "https://api.openai.com/v1"
        ImageBackend.Xai -> "https://api.x.ai/v1"
        ImageBackend.Seedream -> if (region == Region.China) "https://ark.cn-beijing.volces.com/api/v3" else "https://ark.ap-southeast.bytepluses.com/api/v3"
        ImageBackend.Qwen -> if (region == Region.China) "https://dashscope.aliyuncs.com" else "https://dashscope-intl.aliyuncs.com"
        ImageBackend.SiliconFlow -> if (region == Region.China) "https://api.siliconflow.cn/v1" else "https://api.siliconflow.com/v1"
    }

    /** Hosts whose provider key can be reused for [backend]. */
    fun matches(backend: ImageBackend, host: String, region: Region, vertex: Boolean): Boolean = when (backend) {
        ImageBackend.Gemini -> if (vertex) host.endsWith("aiplatform.googleapis.com") || host.contains("aiplatform") else host == "generativelanguage.googleapis.com"
        ImageBackend.OpenAI -> host == "api.openai.com"
        ImageBackend.Xai -> host == "api.x.ai"
        ImageBackend.Seedream -> if (region == Region.China) host.endsWith("volces.com") else host.endsWith("bytepluses.com")
        ImageBackend.Qwen -> if (region == Region.China) host == "dashscope.aliyuncs.com" || host.endsWith("cn-beijing.maas.aliyuncs.com")
        else host == "dashscope-intl.aliyuncs.com" || host.endsWith("ap-southeast-1.maas.aliyuncs.com")
        ImageBackend.SiliconFlow -> if (region == Region.China) host == "api.siliconflow.cn" else host == "api.siliconflow.com"
    }
}

/** Sizes per backend. Pure, so the mapping is unit-tested. */
object ImageSizes {
    fun ratio(aspect: String): Pair<Int, Int>? {
        val parts = aspect.split(':')
        if (parts.size != 2) return null
        val w = parts[0].trim().toIntOrNull() ?: return null
        val h = parts[1].trim().toIntOrNull() ?: return null
        return if (w > 0 && h > 0) w to h else null
    }

    fun side(resolution: String): Int = when (resolution.uppercase()) {
        "4K" -> 4096
        "3K" -> 3072
        "2K" -> 2048
        "1.5K" -> 1536
        else -> 1024
    }

    /** Width and height with about side² pixels in the given ratio, floored to [multiple] so the budget holds. */
    fun dims(aspect: String, side: Int, multiple: Int): Pair<Int, Int> {
        val (rw, rh) = ratio(aspect) ?: (1 to 1)
        val pixels = side.toDouble() * side
        val h = sqrt(pixels * rh / rw)
        val w = h * rw / rh
        fun fl(v: Double) = ((v / multiple).toInt() * multiple).coerceAtLeast(multiple)
        return fl(w) to fl(h)
    }

    fun openAi(model: String, aspect: String, resolution: String): String {
        val r = ratio(aspect) ?: return "auto"
        val flexible = model.startsWith("gpt-image-2")
        val res = resolution.uppercase()
        if (!flexible || res == "1K") {
            return when {
                r.first == r.second -> "1024x1024"
                r.first > r.second -> "1536x1024"
                else -> "1024x1536"
            }
        }
        val long = if (res == "4K") 3840 else 2048
        var w: Int
        var h: Int
        if (r.first >= r.second) {
            w = long; h = (long.toDouble() * r.second / r.first).roundToInt()
        } else {
            h = long; w = (long.toDouble() * r.first / r.second).roundToInt()
        }
        val cap = 3840 * 2160
        if (w.toLong() * h > cap) {
            val k = sqrt(cap.toDouble() / (w.toLong() * h))
            w = (w * k).toInt(); h = (h * k).toInt()
        }
        w = w / 16 * 16
        h = h / 16 * 16
        return "${w}x$h"
    }

    fun seedream(preset: ModelPreset?, aspect: String, resolution: String): String {
        val allowed = preset?.resolutions?.takeIf { it.isNotEmpty() } ?: listOf("1K", "2K", "4K")
        val res = resolution.uppercase().takeIf { it in allowed }
            ?: allowed.firstOrNull { side(it) >= side(resolution) } ?: allowed.last()
        if (ratio(aspect) == null) return res
        val (w, h) = dims(aspect, side(res), 8)
        return "${w}x$h"
    }

    fun qwen(aspect: String, resolution: String): String? {
        if (ratio(aspect) == null) return null
        val side = minOf(side(resolution), 2048).coerceAtLeast(512)
        val (w, h) = dims(aspect, side, 16)
        return "$w*$h"
    }

    private val qwenImageSizes = listOf("1328x1328", "1664x928", "928x1664", "1472x1140", "1140x1472", "1584x1056", "1056x1584")
    private val fluxSizes = listOf("1024x1024", "512x1024", "768x512", "768x1024", "1024x576", "576x1024")
    private val kolorsSizes = listOf("1024x1024", "960x1280", "768x1024", "720x1440", "720x1280")

    fun siliconFlow(model: String, aspect: String): String {
        val list = when {
            model.contains("FLUX", true) -> fluxSizes
            model.contains("Kolors", true) -> kolorsSizes
            else -> qwenImageSizes
        }
        val r = ratio(aspect) ?: return list.first()
        val want = r.first.toDouble() / r.second
        return list.minBy { s ->
            val (w, h) = s.split('x').map(String::toDouble)
            kotlin.math.abs(kotlin.math.ln((w / h) / want))
        }
    }
}

/** Request builders per backend. Each returns every HTTP call needed for [ImageRequest.count] images. */
object ImageRequests {
    fun build(req: ImageRequest, t: ImageTarget): List<HttpSpec> = when (t.backend) {
        ImageBackend.Gemini -> List(req.count.coerceIn(1, 4)) { gemini(req, t) }
        ImageBackend.OpenAI -> listOf(if (req.edit) openAiEdit(req, t) else openAiGenerate(req, t))
        ImageBackend.Xai -> listOf(xai(req, t))
        ImageBackend.Seedream -> seedream(req, t)
        ImageBackend.Qwen -> listOf(qwen(req, t))
        ImageBackend.SiliconFlow -> siliconFlow(req, t)
    }

    private fun json(el: JsonElement) = el.toString()

    private fun bearer(key: String) = mapOf("Authorization" to "Bearer $key")

    fun geminiUrl(t: ImageTarget): String {
        val base = t.baseUrl.trimEnd('/')
        if (!t.vertex) return "$base/models/${t.model}:generateContent"
        val proxy = base.takeIf { !it.contains("googleapis.com") }
        val loc = t.vertexLocation.ifBlank { "global" }
        if (t.vertexProject.isBlank()) return "${proxy ?: "https://aiplatform.googleapis.com"}/v1/publishers/google/models/${t.model}:generateContent"
        val host = proxy ?: when (loc) {
            "global" -> "https://aiplatform.googleapis.com"
            "us", "eu" -> "https://aiplatform.$loc.rep.googleapis.com"
            else -> "https://$loc-aiplatform.googleapis.com"
        }
        return "$host/v1/projects/${t.vertexProject.trim()}/locations/$loc/publishers/google/models/${t.model}:generateContent"
    }

    fun gemini(req: ImageRequest, t: ImageTarget): HttpSpec {
        val preset = ImagePresets.find(ImageBackend.Gemini, t.model)
        val body = buildJsonObject {
            putJsonArray("contents") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        req.refs.forEach { r ->
                            add(buildJsonObject { putJsonObject("inlineData") { put("mimeType", r.mime); put("data", r.base64) } })
                        }
                        add(buildJsonObject { put("text", req.prompt) })
                    }
                })
            }
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add(JsonPrimitive("TEXT")); add(JsonPrimitive("IMAGE")) }
                val aspect = ImageSizes.ratio(req.aspect)?.let { req.aspect }
                val size = req.resolution.uppercase().takeIf { preset == null || it in preset.resolutions }
                if (aspect != null || size != null) {
                    putJsonObject("imageConfig") {
                        if (aspect != null) put("aspectRatio", aspect)
                        if (size != null) put("imageSize", size)
                    }
                }
            }
        }
        val auth = if (t.apiKey.startsWith("ya29.")) bearer(t.apiKey) else mapOf("x-goog-api-key" to t.apiKey)
        return HttpSpec("POST", geminiUrl(t), auth, json(body))
    }

    fun openAiGenerate(req: ImageRequest, t: ImageTarget): HttpSpec {
        val body = buildJsonObject {
            put("model", t.model)
            put("prompt", req.prompt)
            put("n", req.count.coerceIn(1, 10))
            put("size", ImageSizes.openAi(t.model, req.aspect, req.resolution))
            put("quality", if (req.quality == Quality.High) "high" else "medium")
            if (t.baseUrl.contains("api.openai.com")) put("output_format", "png")
        }
        return HttpSpec("POST", t.baseUrl.trimEnd('/') + "/images/generations", bearer(t.apiKey), json(body))
    }

    fun openAiEdit(req: ImageRequest, t: ImageTarget): HttpSpec {
        val parts = ArrayList<HttpSpec.Part>()
        parts += HttpSpec.Part("model", t.model)
        parts += HttpSpec.Part("prompt", req.prompt)
        parts += HttpSpec.Part("n", req.count.coerceIn(1, 10).toString())
        parts += HttpSpec.Part("size", ImageSizes.openAi(t.model, req.aspect, req.resolution))
        parts += HttpSpec.Part("quality", if (req.quality == Quality.High) "high" else "medium")
        req.refs.forEachIndexed { i, r ->
            val ext = r.mime.substringAfter('/').replace("jpeg", "jpg")
            parts += HttpSpec.Part("image[]", bytes = r.bytes(), fileName = "image$i.$ext", mime = r.mime)
        }
        return HttpSpec("POST", t.baseUrl.trimEnd('/') + "/images/edits", bearer(t.apiKey), multipart = parts)
    }

    fun xai(req: ImageRequest, t: ImageTarget): HttpSpec {
        val body = buildJsonObject {
            put("model", t.model)
            put("prompt", req.prompt)
            put("n", req.count.coerceIn(1, 10))
            put("response_format", "b64_json")
            if (ImageSizes.ratio(req.aspect) != null) put("aspect_ratio", req.aspect)
            put("resolution", if (req.resolution.uppercase() == "1K") "1k" else "2k")
            when (req.refs.size) {
                0 -> {}
                1 -> putJsonObject("image") { put("url", req.refs[0].dataUri) }
                else -> putJsonArray("images") { req.refs.take(5).forEach { r -> add(buildJsonObject { put("url", r.dataUri) }) } }
            }
        }
        val path = if (req.edit) "/images/edits" else "/images/generations"
        return HttpSpec("POST", t.baseUrl.trimEnd('/') + path, bearer(t.apiKey), json(body))
    }

    private val seedreamSequential = Regex("seedream-(5-0-lite|4-5|4-0)")

    fun seedream(req: ImageRequest, t: ImageTarget): List<HttpSpec> {
        val preset = ImagePresets.find(ImageBackend.Seedream, t.model)
        val sequential = req.count > 1 && seedreamSequential.containsMatchIn(t.model)
        fun one(n: Int) = buildJsonObject {
            put("model", t.model)
            put("prompt", req.prompt)
            if (req.refs.size == 1) put("image", req.refs[0].dataUri)
            if (req.refs.size > 1) putJsonArray("image") { req.refs.forEach { add(JsonPrimitive(it.dataUri)) } }
            put("size", ImageSizes.seedream(preset, req.aspect, req.resolution))
            put("watermark", req.seedreamWatermark)
            put("response_format", "b64_json")
            if (seedreamSequential.containsMatchIn(t.model)) {
                put("sequential_image_generation", if (n > 1) "auto" else "disabled")
                if (n > 1) putJsonObject("sequential_image_generation_options") { put("max_images", n) }
            }
        }
        val url = t.baseUrl.trimEnd('/') + "/images/generations"
        return if (sequential) listOf(HttpSpec("POST", url, bearer(t.apiKey), json(one(req.count))))
        else List(req.count.coerceIn(1, 4)) { HttpSpec("POST", url, bearer(t.apiKey), json(one(1))) }
    }

    fun qwen(req: ImageRequest, t: ImageTarget): HttpSpec {
        val body = buildJsonObject {
            put("model", t.model)
            putJsonObject("input") {
                putJsonArray("messages") {
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            req.refs.take(3).forEach { r -> add(buildJsonObject { put("image", r.dataUri) }) }
                            add(buildJsonObject { put("text", req.prompt) })
                        }
                    })
                }
            }
            putJsonObject("parameters") {
                put("n", req.count.coerceIn(1, 6))
                (ImageSizes.qwen(req.aspect, req.resolution) ?: if (!req.edit) ImageSizes.qwen("1:1", req.resolution) else null)?.let { put("size", it) }
                put("prompt_extend", req.qwenPromptExtend)
                put("watermark", req.qwenWatermark)
            }
        }
        val base = t.baseUrl.trimEnd('/').removeSuffix("/compatible-mode/v1")
        return HttpSpec("POST", "$base/api/v1/services/aigc/multimodal-generation/generation", bearer(t.apiKey), json(body))
    }

    fun siliconFlow(req: ImageRequest, t: ImageTarget): List<HttpSpec> {
        val batch = t.model.contains("Qwen-Image", true) && !t.model.contains("Edit", true) || t.model.contains("Kolors", true)
        fun one(n: Int) = buildJsonObject {
            put("model", t.model)
            put("prompt", req.prompt)
            if (!t.model.contains("Edit", true)) put("image_size", ImageSizes.siliconFlow(t.model, req.aspect))
            if (batch) put("batch_size", n)
            req.refs.firstOrNull()?.let { put("image", it.dataUri) }
        }
        val url = t.baseUrl.trimEnd('/') + "/images/generations"
        return if (batch) listOf(HttpSpec("POST", url, bearer(t.apiKey), json(one(req.count.coerceIn(1, 4)))))
        else List(req.count.coerceIn(1, 4)) { HttpSpec("POST", url, bearer(t.apiKey), json(one(1))) }
    }
}

/** Response parsers per backend. */
object ImageResponses {
    fun parse(backend: ImageBackend, text: String): List<ImageOut> {
        val root = runCatching { JsonPath.parse(text) }.getOrElse { throw ImageApiException("The service returned something that is not JSON.") }
        val out = when (backend) {
            ImageBackend.Gemini -> gemini(root)
            ImageBackend.OpenAI, ImageBackend.Xai, ImageBackend.Seedream -> dataArray(root)
            ImageBackend.Qwen -> JsonPath.select(root, "output.choices[*].message.content[*].image").mapNotNull { (it as? JsonPrimitive)?.content }.map { ImageOut.Remote(it) }
            ImageBackend.SiliconFlow -> (JsonPath.select(root, "images[*].url") + JsonPath.select(root, "data[*].url"))
                .mapNotNull { (it as? JsonPrimitive)?.content }.map { ImageOut.Remote(it) }
        }
        if (out.isEmpty()) throw ImageApiException(emptyReason(backend, root))
        return out
    }

    private fun gemini(root: JsonElement): List<ImageOut> =
        JsonPath.select(root, "candidates[*].content.parts[*]").mapNotNull { part ->
            val inline = JsonPath.first(part, "inlineData") ?: JsonPath.first(part, "inline_data") ?: return@mapNotNull null
            val data = JsonPath.string(inline, "data") ?: return@mapNotNull null
            ImageOut.Inline(JsonPath.string(inline, "mimeType") ?: JsonPath.string(inline, "mime_type") ?: "image/png", data)
        }

    private fun dataArray(root: JsonElement): List<ImageOut> {
        val fallbackMime = JsonPath.string(root, "output_format")?.let { "image/" + it.replace("jpg", "jpeg") } ?: "image/png"
        return JsonPath.items(root, "data").mapNotNull { item ->
            if (item !is JsonObject) return@mapNotNull null
            JsonPath.string(item, "b64_json")?.let { b64 ->
                return@mapNotNull ImageOut.Inline(JsonPath.string(item, "mime_type") ?: sniff(b64) ?: fallbackMime, b64.substringAfter("base64,"))
            }
            JsonPath.string(item, "url")?.let { ImageOut.Remote(it) }
        }
    }

    private fun sniff(b64: String): String? = when {
        b64.startsWith("/9j/") -> "image/jpeg"
        b64.startsWith("iVBOR") -> "image/png"
        b64.startsWith("UklGR") -> "image/webp"
        else -> null
    }

    fun emptyReason(backend: ImageBackend, root: JsonElement): String {
        val err = listOf("error.message", "message", "error", "data[0].error.message", "promptFeedback.blockReason", "candidates[0].finishReason")
            .firstNotNullOfOrNull { JsonPath.string(root, it) }
        val text = if (backend == ImageBackend.Gemini) {
            JsonPath.select(root, "candidates[*].content.parts[*].text").joinToString(" ") { (it as? JsonPrimitive)?.content.orEmpty() }.trim()
        } else ""
        return when {
            text.isNotEmpty() -> "No image was returned. The model said: ${text.take(400)}"
            err != null -> "No image was returned: ${err.take(300)}"
            else -> "No image was returned."
        }
    }

    fun error(text: String): String? = runCatching {
        val root = JsonPath.parse(text)
        listOf("error.message", "message", "error", "code").firstNotNullOfOrNull { JsonPath.string(root, it) }
    }.getOrNull()
}

