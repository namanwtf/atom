package atom.llm.cloud

enum class Wire { OpenAiChat, Anthropic, Gemini }

/**
 * A cloud model provider. [baseUrl] always includes the API version segment; each wire appends
 * only its resource path (`/chat/completions`, `/messages`, `/models/{model}:streamGenerateContent`).
 */
data class Provider(
    val id: String,
    val displayName: String,
    val wire: Wire,
    val baseUrl: String,
    val defaultModel: String,
    val keyHelpUrl: String?,
    val supportsModelList: Boolean,
)

object Providers {
    const val CUSTOM_ID = "custom"

    val all: List<Provider> = listOf(
        Provider(
            id = "openai",
            displayName = "OpenAI",
            wire = Wire.OpenAiChat,
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-6.2-sol",
            keyHelpUrl = "https://platform.openai.com/api-keys",
            supportsModelList = true,
        ),
        Provider(
            id = "anthropic",
            displayName = "Anthropic",
            wire = Wire.Anthropic,
            baseUrl = "https://api.anthropic.com/v1",
            defaultModel = "claude-opus-5-5",
            keyHelpUrl = "https://console.anthropic.com/settings/keys",
            supportsModelList = true,
        ),
        Provider(
            id = "gemini",
            displayName = "Google Gemini",
            wire = Wire.Gemini,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            defaultModel = "gemini-3.8-flash",
            keyHelpUrl = "https://aistudio.google.com/apikey",
            supportsModelList = true,
        ),
        Provider(
            id = "deepseek",
            displayName = "DeepSeek",
            wire = Wire.OpenAiChat,
            baseUrl = "https://api.deepseek.com",
            defaultModel = "deepseek-flash",
            keyHelpUrl = "https://platform.deepseek.com/api_keys",
            supportsModelList = true,
        ),
        Provider(
            id = "moonshot",
            displayName = "Moonshot Kimi",
            wire = Wire.OpenAiChat,
            baseUrl = "https://api.moonshot.ai/v1",
            defaultModel = "kimi-k3.1-pro",
            keyHelpUrl = "https://platform.kimi.ai/console/api-keys",
            supportsModelList = true,
        ),
        Provider(
            id = "qwen",
            displayName = "Qwen (DashScope)",
            wire = Wire.OpenAiChat,
            baseUrl = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
            defaultModel = "qwen-plus",
            keyHelpUrl = "https://modelstudio.console.alibabacloud.com/?tab=playground#/api-key",
            supportsModelList = true,
        ),
        Provider(
            id = "xai",
            displayName = "xAI",
            wire = Wire.OpenAiChat,
            baseUrl = "https://api.x.ai/v1",
            defaultModel = "grok-4.7",
            keyHelpUrl = "https://console.x.ai",
            supportsModelList = true,
        ),
        Provider(
            id = "minimax",
            displayName = "MiniMax",
            wire = Wire.OpenAiChat,
            baseUrl = "https://api.minimax.io/v1",
            defaultModel = "MiniMax-M3",
            keyHelpUrl = "https://platform.minimax.io/user-center/basic-information/interface-key",
            supportsModelList = false,
        ),
        Provider(
            id = "stepfun",
            displayName = "StepFun",
            wire = Wire.OpenAiChat,
            baseUrl = "https://api.stepfun.com/v1",
            defaultModel = "step-3.5-flash",
            keyHelpUrl = "https://platform.stepfun.com/interface-key",
            supportsModelList = true,
        ),
        Provider(
            id = "siliconflow",
            displayName = "SiliconFlow",
            wire = Wire.OpenAiChat,
            baseUrl = "https://api.siliconflow.com/v1",
            defaultModel = "deepseek-ai/DeepSeek-R1",
            keyHelpUrl = "https://cloud.siliconflow.com/account/ak",
            supportsModelList = true,
        ),
        Provider(
            id = "openrouter",
            displayName = "OpenRouter",
            wire = Wire.OpenAiChat,
            baseUrl = "https://openrouter.ai/api/v1",
            defaultModel = "openrouter/auto",
            keyHelpUrl = "https://openrouter.ai/settings/keys",
            supportsModelList = true,
        ),
        Provider(
            id = "groq",
            displayName = "Groq",
            wire = Wire.OpenAiChat,
            baseUrl = "https://api.groq.com/openai/v1",
            defaultModel = "openai/gpt-oss-120b",
            keyHelpUrl = "https://console.groq.com/keys",
            supportsModelList = true,
        ),
        Provider(
            id = "mistral",
            displayName = "Mistral",
            wire = Wire.OpenAiChat,
            baseUrl = "https://api.mistral.ai/v1",
            defaultModel = "mistral-medium-latest",
            keyHelpUrl = "https://console.mistral.ai/api-keys",
            supportsModelList = true,
        ),
        Provider(
            id = CUSTOM_ID,
            displayName = "Custom (OpenAI-compatible)",
            wire = Wire.OpenAiChat,
            baseUrl = "http://localhost:11434/v1",
            defaultModel = "",
            keyHelpUrl = null,
            supportsModelList = true,
        ),
    )

    private val byIdMap = all.associateBy { it.id }

    fun byId(id: String): Provider? = byIdMap[id]
}
