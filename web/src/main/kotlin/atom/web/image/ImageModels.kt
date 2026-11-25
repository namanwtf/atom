package atom.web.image

import kotlinx.serialization.Serializable

@Serializable
enum class ImageBackend(val label: String) {
    Gemini("Google Gemini"),
    OpenAI("OpenAI & compatible"),
    Xai("xAI Grok Imagine"),
    Seedream("Doubao Seedream"),
    Qwen("Qwen-Image"),
    SiliconFlow("SiliconFlow"),
}

@Serializable
enum class CredentialSource { Auto, Separate }

@Serializable
enum class Quality { Standard, High }

/** Region-dependent hosts. Gemini uses [ImageConfig.geminiVertex] instead. */
@Serializable
enum class Region { China, International }

data class ModelPreset(
    val id: String,
    val label: String,
    val backend: ImageBackend,
    val maxRefs: Int,
    /** Resolution choices the model accepts; empty means the model fixes it. */
    val resolutions: List<String> = listOf("1K", "2K", "4K"),
    val recommended: Boolean = false,
    val retired: Boolean = false,
    val note: String? = null,
    /** Model id on the international host when it differs. */
    val intlId: String? = null,
)

object ImagePresets {
    val ASPECTS = listOf("auto", "1:1", "16:9", "9:16", "4:3", "3:4", "3:2", "2:3")

    val all: List<ModelPreset> = listOf(
        ModelPreset("gemini-nano-banana-2.1", "Gemini Nano Banana 2.1", ImageBackend.Gemini, 14, recommended = true),
        ModelPreset("gemini-3.1-flash-lite-image", "Gemini 3.1 Flash-Lite", ImageBackend.Gemini, 4),
        ModelPreset("gemini-3.1-flash-image", "Gemini 3.1 Flash", ImageBackend.Gemini, 14),
        ModelPreset("gemini-3-pro-image", "Gemini 3 Pro", ImageBackend.Gemini, 14),
        ModelPreset("gemini-2.5-flash-image", "Gemini 2.5 Flash", ImageBackend.Gemini, 3, resolutions = emptyList()),

        ModelPreset("gpt-image-2.6-flare", "gpt-image-2.6-flare", ImageBackend.OpenAI, 16, recommended = true),
        ModelPreset("gpt-image-2.6-sunburst", "gpt-image-2.6-sunburst", ImageBackend.OpenAI, 16, note = "Best for precise edits"),
        ModelPreset("gpt-image-2.5-flare", "gpt-image-2.5-flare", ImageBackend.OpenAI, 16),
        ModelPreset("gpt-image-2.5-sunburst", "gpt-image-2.5-sunburst", ImageBackend.OpenAI, 16),
        ModelPreset("gpt-image-1.5", "gpt-image-1.5", ImageBackend.OpenAI, 16, resolutions = emptyList(), note = "Shuts down 1 Dec 2026"),
        ModelPreset("gpt-image-1", "gpt-image-1", ImageBackend.OpenAI, 16, resolutions = emptyList(), retired = true, note = "Retired by OpenAI"),

        ModelPreset("grok-imagine-image-2.0", "Grok Imagine 2.0", ImageBackend.Xai, 5, resolutions = listOf("1K", "2K"), recommended = true, note = "Up to 5 references"),
        ModelPreset("grok-imagine-image", "Grok Imagine", ImageBackend.Xai, 5, resolutions = listOf("1K", "2K")),

        ModelPreset("doubao-seedream-5-5-261028", "Seedream 5.5", ImageBackend.Seedream, 10, listOf("1K", "2K"), recommended = true, intlId = "dola-seedream-5-5-261028"),
        ModelPreset("doubao-seedream-5-0-pro-260628", "Seedream 5.0 Pro", ImageBackend.Seedream, 10, listOf("1K", "2K"), intlId = "dola-seedream-5-0-pro-260628"),
        ModelPreset("doubao-seedream-5-0-flash-260915", "Seedream 5.0 Flash", ImageBackend.Seedream, 10, listOf("1K", "2K"), intlId = "dola-seedream-5-0-flash-260915"),
        ModelPreset("doubao-seedream-5-0-lite-260128", "Seedream 5.0 Lite", ImageBackend.Seedream, 14, listOf("2K", "4K"), intlId = "seedream-5-0-lite-260128"),
        ModelPreset("doubao-seedream-4-5-251128", "Seedream 4.5", ImageBackend.Seedream, 14, listOf("2K", "4K"), intlId = "seedream-4-5-251128"),

        ModelPreset("qwen-image-2.0-pro", "Qwen-Image 2.0 Pro", ImageBackend.Qwen, 3, listOf("1K", "2K"), recommended = true),
        ModelPreset("qwen-image-2.0", "Qwen-Image 2.0", ImageBackend.Qwen, 3, listOf("1K", "2K")),

        ModelPreset("Qwen/Qwen-Image", "Qwen-Image", ImageBackend.SiliconFlow, 0, emptyList(), recommended = true),
        ModelPreset("Qwen/Qwen-Image-Edit", "Qwen-Image-Edit", ImageBackend.SiliconFlow, 1, emptyList(), note = "Editing only"),
        ModelPreset("Kwai-Kolors/Kolors", "Kolors", ImageBackend.SiliconFlow, 0, emptyList(), note = "China host"),
        ModelPreset("black-forest-labs/FLUX.1-schnell", "FLUX schnell", ImageBackend.SiliconFlow, 0, emptyList()),
    )

    fun forBackend(b: ImageBackend) = all.filter { it.backend == b }

    fun default(b: ImageBackend) = forBackend(b).first { it.recommended }

    fun find(b: ImageBackend, id: String) = forBackend(b).firstOrNull { it.id == id || it.intlId == id }
}

@Serializable
data class ImageConfig(
    val enabled: Boolean = false,
    val backend: ImageBackend = ImageBackend.Gemini,
    /** Selected model per backend; a value not in the presets is a custom model id. */
    val models: Map<ImageBackend, String> = emptyMap(),
    val credential: CredentialSource = CredentialSource.Auto,
    /** Separate base URLs per backend (blank = official endpoint). Keys live in the secret store. */
    val baseUrls: Map<ImageBackend, String> = emptyMap(),
    val geminiVertex: Boolean = false,
    val vertexProject: String = "",
    val vertexLocation: String = "global",
    val qwenRegion: Region = Region.China,
    val seedreamRegion: Region = Region.China,
    val siliconRegion: Region = Region.China,
    val aspect: String = "auto",
    val quality: Quality = Quality.Standard,
    val resolution: String = "1K",
    val seedreamWatermark: Boolean = false,
    val qwenPromptExtend: Boolean = true,
    val qwenWatermark: Boolean = false,
) {
    fun model(b: ImageBackend = backend): String = models[b]?.takeIf { it.isNotBlank() } ?: ImagePresets.default(b).id

    fun preset(b: ImageBackend = backend): ModelPreset? = ImagePresets.find(b, model(b))

    companion object {
        fun secretName(b: ImageBackend) = "image.${b.name.lowercase()}.key"
    }
}
