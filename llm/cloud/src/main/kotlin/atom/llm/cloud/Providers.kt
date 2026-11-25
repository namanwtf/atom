package atom.llm.cloud

/**
 * The HTTP protocol a provider speaks. [OpenAiChat] and [OpenAiResponses] are the two endpoint modes of an
 * OpenAI-compatible provider; [Gemini] is Google AI Studio and [Vertex] is Gemini on Vertex AI.
 */
enum class Wire {
    OpenAiChat,
    OpenAiResponses,
    Anthropic,
    Gemini,
    Vertex,
    ;

    val protocol: Protocol
        get() = when (this) {
            OpenAiChat, OpenAiResponses -> Protocol.OpenAi
            Anthropic -> Protocol.Anthropic
            Gemini, Vertex -> Protocol.Gemini
        }
}

enum class Protocol { OpenAi, Anthropic, Gemini }

/**
 * Vendor families. Wire quirks (reasoning parameters, headers, interleaved thinking) key off the family, not
 * the provider id, so duplicated or user-added providers that point at a known vendor behave the same.
 */
object Family {
    const val OPENAI = "openai"
    const val ANTHROPIC = "anthropic"
    const val GEMINI = "gemini"
    const val DEEPSEEK = "deepseek"
    const val MOONSHOT = "moonshot"
    const val QWEN = "qwen"
    const val MIMO = "mimo"
    const val MINIMAX = "minimax"
    const val STEPFUN = "stepfun"
    const val SILICONFLOW = "siliconflow"
    const val OPENROUTER = "openrouter"
    const val XAI = "xai"
    const val GROQ = "groq"
    const val MISTRAL = "mistral"
    const val CHATGPT = "chatgpt"
    const val CUSTOM = "custom"

    private val HOSTS = listOf(
        "api.openai.com" to OPENAI,
        "chatgpt.com" to CHATGPT,
        "anthropic.com" to ANTHROPIC,
        "generativelanguage.googleapis.com" to GEMINI,
        "aiplatform" to GEMINI,
        "deepseek.com" to DEEPSEEK,
        "moonshot" to MOONSHOT,
        "kimi" to MOONSHOT,
        "dashscope" to QWEN,
        "aliyuncs.com" to QWEN,
        "xiaomimimo.com" to MIMO,
        "minimax" to MINIMAX,
        "stepfun.com" to STEPFUN,
        "siliconflow" to SILICONFLOW,
        "openrouter.ai" to OPENROUTER,
        "x.ai" to XAI,
        "grok.com" to XAI,
        "groq.com" to GROQ,
        "mistral.ai" to MISTRAL,
    )

    /** Guesses the family from a base URL; [CUSTOM] when the host is not a known vendor. */
    fun fromBaseUrl(baseUrl: String): String {
        val host = baseUrl.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        return HOSTS.firstOrNull { (needle, _) -> host.contains(needle) }?.second ?: CUSTOM
    }
}

/**
 * A provider as the engines see it. [baseUrl] includes the API version segment where the vendor has one;
 * each wire appends only its resource path.
 */
data class Provider(
    val id: String,
    val displayName: String,
    val wire: Wire,
    val baseUrl: String,
    val defaultModel: String,
    val keyHelpUrl: String?,
    val supportsModelList: Boolean,
    val family: String = id,
)

object Providers {
    const val CUSTOM_ID = "custom"

    /** Built-in providers, in catalog order, followed by the generic custom endpoint. */
    val all: List<Provider> = PresetCatalog.providers.map { it.toProvider() } + Provider(
        id = CUSTOM_ID,
        displayName = "Custom (OpenAI-compatible)",
        wire = Wire.OpenAiChat,
        baseUrl = "http://localhost:11434/v1",
        defaultModel = "",
        keyHelpUrl = null,
        supportsModelList = true,
        family = Family.CUSTOM,
    )

    private val byIdMap = all.associateBy { it.id }

    fun byId(id: String): Provider? = byIdMap[id]
}
