package atom.llm.cloud

import atom.core.llm.ReasoningEffort

data class ModelPreset(
    val id: String,
    val displayName: String,
    val contextWindow: Int?,
    val reasoning: ReasoningInfo,
    val vision: Boolean = true,
) {
    fun toConfig(): ModelConfig = ModelConfig(
        id = id,
        displayName = displayName,
        detected = DetectedCapabilities(reasoning, contextWindow, vision, CapabilitySource.Catalog),
        builtIn = true,
    )
}

data class ProviderPreset(
    val id: String,
    val displayName: String,
    val wire: Wire,
    val baseUrl: String,
    val models: List<ModelPreset>,
    val keyHelpUrl: String?,
    val supportsModelList: Boolean = true,
    val family: String = id,
    val authMethods: List<AuthMethod> = listOf(AuthMethod.ApiKey),
    /** Endpoint modes the user may switch between (e.g. OpenAI Chat Completions, Gemini Vertex). */
    val wires: List<Wire> = listOf(wire),
) {
    fun toProvider(): Provider = Provider(
        id = id,
        displayName = displayName,
        wire = wire,
        baseUrl = baseUrl,
        defaultModel = models.firstOrNull()?.id.orEmpty(),
        keyHelpUrl = keyHelpUrl,
        supportsModelList = supportsModelList,
        family = family,
    )

    fun toConfig(configId: String = id): ProviderConfig = ProviderConfig(
        id = configId,
        displayName = displayName,
        wire = wire,
        baseUrl = baseUrl,
        enabled = true,
        presetId = id,
        family = family,
        systemPrompt = PresetCatalog.DEFAULT_SYSTEM_PROMPT,
        models = models.map { it.toConfig() },
        defaultModel = models.firstOrNull()?.id,
        keyHelpUrl = keyHelpUrl,
        supportsModelList = supportsModelList,
    )
}

/** The providers and models Atom ships with. Model metadata matches the vendors' published limits. */
object PresetCatalog {
    const val DEFAULT_PROVIDER = "deepseek"
    const val DEFAULT_MODEL = "deepseek-flash"

    const val DEFAULT_SYSTEM_PROMPT =
        "You are Atom, an AI agent running on the user's Android phone. You can see the screen and operate " +
            "apps, settings and files on this device through your tools. Act carefully, confirm before anything " +
            "irreversible, and answer in the user's language."

    private val OFF = ReasoningEffort.Off
    private val L = ReasoningEffort.Low
    private val M = ReasoningEffort.Medium
    private val H = ReasoningEffort.High
    private val X = ReasoningEffort.XHigh
    private val MAX = ReasoningEffort.Max

    private fun m(id: String, name: String, context: Int?, reasoning: ReasoningInfo, vision: Boolean = true) =
        ModelPreset(id, name, context, reasoning, vision)

    private val KIMI_K26 = m("kimi-k2.6", "Kimi K2.6", 262_144, ReasoningInfo.of(OFF))
    private val KIMI_K27 = m("kimi-k2.7-code", "Kimi K2.7 Code", 262_144, ReasoningInfo.forced())
    private val KIMI_K3 = m("kimi-k3", "Kimi K3", 1_048_576, ReasoningInfo.of(L, H, MAX))

    val providers: List<ProviderPreset> = listOf(
        ProviderPreset(
            id = "openai",
            displayName = "OpenAI",
            wire = Wire.OpenAiResponses,
            wires = listOf(Wire.OpenAiResponses, Wire.OpenAiChat),
            baseUrl = "https://api.openai.com/v1",
            keyHelpUrl = "https://platform.openai.com/api-keys",
            authMethods = listOf(AuthMethod.ApiKey, AuthMethod.ChatGpt),
            models = listOf(
                m("gpt-6.1-sol", "GPT-6.1 Sol", 1_050_000, ReasoningInfo.of(L, M, H, X, MAX)),
                m("gpt-6-luna", "GPT-6 Luna", 1_050_000, ReasoningInfo.of(OFF, L, M, H, X, MAX)),
                m("gpt-6-astra", "GPT-6 Astra", 1_050_000, ReasoningInfo.of(L, M, H, X, MAX)),
            ),
        ),
        ProviderPreset(
            id = "anthropic",
            displayName = "Anthropic",
            wire = Wire.Anthropic,
            baseUrl = "https://api.anthropic.com",
            keyHelpUrl = "https://console.anthropic.com/settings/keys",
            models = listOf(
                m("claude-fable-5", "Claude Fable 5", 1_000_000, ReasoningInfo.of(L, M, H, X, MAX)),
                m("claude-fable-5-1", "Claude Fable 5.1", 1_000_000, ReasoningInfo.of(L, M, H, X, MAX)),
                m("claude-opus-4-8", "Claude Opus 4.8", 1_000_000, ReasoningInfo.of(OFF, L, M, H, X, MAX)),
                m("claude-opus-5-5", "Claude Opus 5.5", 1_000_000, ReasoningInfo.of(L, M, H, X, MAX)),
                m("claude-sonnet-5", "Claude Sonnet 5", 1_000_000, ReasoningInfo.of(OFF, L, M, H, X, MAX)),
            ),
        ),
        ProviderPreset(
            id = "qwen",
            displayName = "Alibaba Bailian",
            wire = Wire.OpenAiChat,
            family = Family.QWEN,
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            keyHelpUrl = "https://bailian.console.aliyun.com/?tab=model#/api-key",
            models = listOf(
                m("qwen3.7-plus", "Qwen3.7 Plus", 1_000_000, ReasoningInfo.of(OFF, L, M, H)),
                m("qwen3.8-flash", "Qwen3.8 Flash", 1_000_000, ReasoningInfo.of(OFF, L, M, X)),
                m("qwen3.8-max", "Qwen3.8 Max", 1_000_000, ReasoningInfo.of(OFF, L, M, X)),
                m("deepseek-v4.1-flash", "DeepSeek V4.1 Flash", 1_000_000, ReasoningInfo.of(OFF, L, H, MAX)),
                KIMI_K26.copy(reasoning = ReasoningInfo.of(OFF, L, M, H)),
                KIMI_K27.copy(reasoning = ReasoningInfo.of(OFF, L, M, H)),
                KIMI_K3.copy(reasoning = ReasoningInfo.of(OFF, L, H, MAX)),
            ),
        ),
        ProviderPreset(
            id = "deepseek",
            displayName = "DeepSeek",
            wire = Wire.OpenAiChat,
            baseUrl = "https://api.deepseek.com",
            keyHelpUrl = "https://platform.deepseek.com/api_keys",
            models = listOf(
                m("deepseek-flash", "DeepSeek Flash", 1_048_576, ReasoningInfo.of(OFF, L, H, MAX)),
                m("deepseek-v4-pro", "DeepSeek V4 Pro", 1_048_576, ReasoningInfo.of(OFF, L, H, MAX), vision = false),
            ),
        ),
        ProviderPreset(
            id = "moonshot",
            displayName = "Kimi (Moonshot)",
            wire = Wire.OpenAiChat,
            family = Family.MOONSHOT,
            baseUrl = "https://api.moonshot.cn/v1",
            keyHelpUrl = "https://platform.moonshot.cn/console/api-keys",
            models = listOf(
                KIMI_K26,
                KIMI_K27,
                m("kimi-k2.7-code-highspeed", "Kimi K2.7 Code HighSpeed", 262_144, ReasoningInfo.forced()),
                KIMI_K3,
            ),
        ),
        ProviderPreset(
            id = "mimo",
            displayName = "MiMo (Xiaomi)",
            wire = Wire.OpenAiChat,
            baseUrl = "https://api.xiaomimimo.com/v1",
            keyHelpUrl = "https://platform.xiaomimimo.com/#/console/api-keys",
            models = listOf(
                m("mimo-v2.5", "MiMo V2.5", 1_000_000, ReasoningInfo.forced()),
                m("mimo-v2.5-pro", "MiMo V2.5 Pro", 1_048_576, ReasoningInfo.forced(), vision = false),
                m("mimo-v2.6-flash", "MiMo V2.6 Flash", 1_048_576, ReasoningInfo.of(OFF)),
                m("mimo-v2.6-pro", "MiMo V2.6 Pro", 1_048_576, ReasoningInfo.of(OFF)),
            ),
        ),
        ProviderPreset(
            id = "minimax",
            displayName = "MiniMax",
            wire = Wire.OpenAiChat,
            baseUrl = "https://api.minimaxi.com/v1",
            keyHelpUrl = "https://platform.minimaxi.com/user-center/basic-information/interface-key",
            supportsModelList = false,
            models = listOf(m("MiniMax-M3", "MiniMax M3", 1_000_000, ReasoningInfo.forced())),
        ),
        ProviderPreset(
            id = "stepfun",
            displayName = "StepFun",
            wire = Wire.OpenAiChat,
            baseUrl = "https://api.stepfun.com/v1",
            keyHelpUrl = "https://platform.stepfun.com/interface-key",
            models = listOf(
                m("step-3.7-flash", "Step 3.7 Flash", 256_000, ReasoningInfo.of(L, M, H)),
                m("step-5-preview", "Step 5 Preview", 1_000_000, ReasoningInfo.of(L, M, H)),
            ),
        ),
        ProviderPreset(
            id = "siliconflow",
            displayName = "SiliconFlow",
            wire = Wire.OpenAiChat,
            baseUrl = "https://api.siliconflow.cn/v1",
            keyHelpUrl = "https://cloud.siliconflow.cn/account/ak",
            models = emptyList(),
        ),
        ProviderPreset(
            id = "openrouter",
            displayName = "OpenRouter",
            wire = Wire.OpenAiChat,
            baseUrl = "https://openrouter.ai/api/v1",
            keyHelpUrl = "https://openrouter.ai/settings/keys",
            models = emptyList(),
        ),
        ProviderPreset(
            id = "xai",
            displayName = "xAI",
            wire = Wire.OpenAiChat,
            wires = listOf(Wire.OpenAiChat, Wire.OpenAiResponses),
            baseUrl = "https://api.x.ai/v1",
            keyHelpUrl = "https://console.x.ai",
            authMethods = listOf(AuthMethod.ApiKey, AuthMethod.Grok),
            models = emptyList(),
        ),
        ProviderPreset(
            id = "gemini",
            displayName = "Gemini",
            wire = Wire.Gemini,
            wires = listOf(Wire.Gemini, Wire.Vertex),
            baseUrl = GEMINI_BASE_URL,
            keyHelpUrl = "https://aistudio.google.com/apikey",
            models = listOf(
                m("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite", 1_048_576, ReasoningInfo.of(OFF, L, M, H)),
                m("gemini-3.8-flash", "Gemini 3.8 Flash", 1_048_576, ReasoningInfo.of(L, M, H)),
                m("gemini-4-argon", "Gemini 4 Argon", 1_048_576, ReasoningInfo.of(L, M, H)),
                m("gemini-4-carbon", "Gemini 4 Carbon", 1_048_576, ReasoningInfo.of(L, M, H, default = M)),
                m("gemini-4-hydrogen", "Gemini 4 Hydrogen", 1_048_576, ReasoningInfo.of(L, M, H, X, default = H)),
                m("gemini-4-neon", "Gemini 4 Neon", 1_048_576, ReasoningInfo.of(L, M, H, X, default = H)),
            ),
        ),
        ProviderPreset(
            id = "groq",
            displayName = "Groq",
            wire = Wire.OpenAiChat,
            baseUrl = "https://api.groq.com/openai/v1",
            keyHelpUrl = "https://console.groq.com/keys",
            models = emptyList(),
        ),
        ProviderPreset(
            id = "mistral",
            displayName = "Mistral",
            wire = Wire.OpenAiChat,
            baseUrl = "https://api.mistral.ai/v1",
            keyHelpUrl = "https://console.mistral.ai/api-keys",
            models = emptyList(),
        ),
    )

    /** Starting points offered under "Add new provider" for self-hosted OpenAI-compatible servers. */
    val localTemplates: List<Pair<String, String>> = listOf(
        "Ollama" to "http://localhost:11434/v1",
        "LM Studio" to "http://localhost:1234/v1",
    )

    const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"

    /** Vertex locations offered in the picker; any single region id is also accepted. */
    val vertexLocations: List<String> = listOf("global", "us", "eu", "us-central1", "europe-west4", "asia-northeast1")

    private val byIdMap = providers.associateBy { it.id }

    fun byId(id: String): ProviderPreset? = byIdMap[id]

    fun model(providerId: String, modelId: String): ModelPreset? = byId(providerId)?.models?.firstOrNull { it.id == modelId }
}
