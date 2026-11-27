package atom.llm.cloud

import atom.core.llm.ReasoningEffort
import kotlinx.serialization.Serializable

/** How a provider authenticates: an API key, or a vendor account login reused from the vendor's own app. */
@Serializable
enum class AuthMethod { ApiKey, ChatGpt, Grok }

@Serializable
enum class ReasoningMode { Auto, On, Off }

@Serializable
data class HeaderEntry(val name: String, val value: String)

/**
 * One model as the user configured it. Null overrides mean "use what was detected" ([detected] holds the
 * built-in catalog or remote metadata captured when the model was added).
 */
@Serializable
data class ModelConfig(
    val id: String,
    val displayName: String = id,
    val contextWindow: Int? = null,
    val reasoningMode: ReasoningMode = ReasoningMode.Auto,
    /** Levels ticked by hand; only used when [reasoningMode] is [ReasoningMode.On]. */
    val reasoningLevels: List<ReasoningEffort>? = null,
    val detected: DetectedCapabilities? = null,
    val builtIn: Boolean = false,
    /** Synced from a signed-in account rather than added by hand or from the official list. */
    val fromAccount: Boolean = false,
) {
    /** Catalog or remote metadata, with family rules filling gaps. */
    val autoCapabilities: DetectedCapabilities get() = CapabilityDetector.detect(id, detected)

    val reasoning: ReasoningInfo
        get() = when (reasoningMode) {
            ReasoningMode.Off -> ReasoningInfo.None
            ReasoningMode.On -> ReasoningInfo(
                supported = true,
                levels = (reasoningLevels ?: autoCapabilities.reasoning?.levels.orEmpty()).distinct().sorted(),
                serverDefault = autoCapabilities.reasoning?.serverDefault,
            )
            ReasoningMode.Auto -> autoCapabilities.reasoning ?: ReasoningInfo.None
        }

    /**
     * What the engine is told about reasoning: [reasoning], except that in Auto mode a model nothing knows (no
     * catalog entry, no metadata, no family rule) is unknown (null), not "cannot think". The engine then snaps
     * nothing and, on a thinking vendor's wire, sends tool-call turns' reasoning back to be safe, as 4.0.5 did: a
     * new thinking model rejects a tool loop without it.
     */
    val engineReasoning: ReasoningInfo?
        get() = if (reasoningMode == ReasoningMode.Auto && autoCapabilities.reasoning == null) null else reasoning

    val effectiveContextWindow: Int? get() = contextWindow ?: autoCapabilities.contextWindow

    /** The model's own cap on one reply's tokens, when metadata gives one; null when unknown. */
    val maxOutputTokens: Int? get() = autoCapabilities.maxOutputTokens?.takeIf { it > 0 }

    val vision: Boolean? get() = autoCapabilities.vision
}

/** A configured provider, persisted as JSON. Secrets (API key, OAuth tokens) live in the encrypted vault. */
@Serializable
data class ProviderConfig(
    val id: String,
    val displayName: String,
    val wire: Wire,
    val baseUrl: String,
    val enabled: Boolean = true,
    val presetId: String? = null,
    val family: String = Family.fromBaseUrl(baseUrl),
    val systemPrompt: String = "",
    val headers: List<HeaderEntry> = emptyList(),
    /** A JSON object merged into every request body; blank when unused. */
    val extraBody: String = "",
    val models: List<ModelConfig> = emptyList(),
    val defaultModel: String? = null,
    val vertexProject: String = "",
    val vertexLocation: String = "global",
    val hostedWebSearch: Boolean = false,
    val googleSearch: Boolean = false,
    /** Anthropic only: the "anthropic-version" header; blank uses [AnthropicEngine.ANTHROPIC_VERSION]. */
    val anthropicVersion: String = "",
    val auth: AuthMethod = AuthMethod.ApiKey,
    val keyHelpUrl: String? = null,
    val supportsModelList: Boolean = true,
    /** models.dev provider id this was imported from. */
    val importedFrom: String? = null,
) {
    val builtIn: Boolean get() = presetId != null

    val preset: ProviderPreset? get() = presetId?.let(PresetCatalog::byId)

    fun model(id: String): ModelConfig? = models.firstOrNull { it.id == id }

    /** The model used when a selection names none, and for "Test connection". */
    val primaryModel: String get() = defaultModel?.takeIf { d -> models.any { it.id == d } } ?: models.firstOrNull()?.id.orEmpty()

    /** Whether requests go out without a key being configured (local servers, signed-in accounts). */
    val keyOptional: Boolean get() = presetId == null || auth != AuthMethod.ApiKey

    fun toProvider(): Provider = Provider(
        id = id,
        displayName = displayName,
        wire = wire,
        baseUrl = baseUrl.trim().trimEnd('/'),
        defaultModel = primaryModel,
        keyHelpUrl = keyHelpUrl,
        supportsModelList = supportsModelList && wire != Wire.Vertex,
        family = family,
    )

    /** Restores the official settings and model list, keeping id, enable switch and custom models. */
    fun resetToPreset(): ProviderConfig {
        val preset = preset ?: return this
        val fresh = preset.toConfig(id)
        return fresh.copy(enabled = enabled)
    }

    fun duplicate(newId: String, suffix: String): ProviderConfig = copy(
        id = newId,
        displayName = "$displayName $suffix",
        presetId = null,
        models = models.map { it.copy(builtIn = false) },
        auth = AuthMethod.ApiKey,
    )
}
