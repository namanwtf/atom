package atom.llm.cloud

import atom.core.llm.ChatMessage
import atom.core.llm.LlmEngine
import atom.core.llm.LlmEvent
import atom.core.llm.LlmException
import atom.core.llm.LlmRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** A model reported by a provider's listing endpoint, with whatever capability metadata it carried. */
data class RemoteModel(
    val id: String,
    val displayName: String? = null,
    val capabilities: DetectedCapabilities? = null,
)

object CloudEngines {

    fun create(
        provider: Provider,
        apiKey: String,
        model: String,
        baseUrl: String = provider.baseUrl,
        options: EngineOptions = EngineOptions(),
    ): LlmEngine = create(provider, apiKey, model, baseUrl, CloudHttp.client, options)

    internal fun create(
        provider: Provider,
        apiKey: String,
        model: String,
        baseUrl: String,
        http: OkHttpClient,
        options: EngineOptions = EngineOptions(),
    ): LlmEngine {
        val raw = when (provider.wire) {
            Wire.OpenAiChat -> OpenAiChatEngine(provider, apiKey, model, baseUrl, http, options)
            Wire.OpenAiResponses -> OpenAiResponsesEngine(provider, apiKey, model, baseUrl, http, options)
            Wire.Anthropic -> AnthropicEngine(provider, apiKey, model, baseUrl, http, options)
            Wire.Gemini, Wire.Vertex -> GeminiEngine(provider, apiKey, model, baseUrl, http, options)
        }
        return if (options.systemPrompt.isNullOrBlank() && options.reasoning == null) raw else CustomizedEngine(raw, options)
    }

    /** Model ids the key can use, sorted; empty when the provider has no listing endpoint. */
    suspend fun listModels(provider: Provider, apiKey: String, baseUrl: String = provider.baseUrl): List<String> =
        listRemoteModels(provider, apiKey, baseUrl).map { it.id }

    suspend fun listRemoteModels(
        provider: Provider,
        apiKey: String,
        baseUrl: String = provider.baseUrl,
        options: EngineOptions = EngineOptions(),
    ): List<RemoteModel> = listRemoteModels(provider, apiKey, baseUrl, CloudHttp.client, options)

    internal suspend fun listModels(provider: Provider, apiKey: String, baseUrl: String, http: OkHttpClient): List<String> =
        listRemoteModels(provider, apiKey, baseUrl, http, EngineOptions()).map { it.id }

    internal suspend fun listRemoteModels(
        provider: Provider,
        apiKey: String,
        baseUrl: String,
        http: OkHttpClient,
        options: EngineOptions,
    ): List<RemoteModel> {
        if (!provider.supportsModelList) return emptyList()
        val errors = ErrorMapper(provider.displayName, apiKey)
        val root = baseUrl.trimEnd('/')
        val models = when (provider.wire.protocol) {
            Protocol.OpenAi -> openAiModels(fetch(http, errors, openAiRequest("$root/models", apiKey, options)))
            Protocol.Anthropic -> anthropicModels(http, errors, AnthropicEngine.versionedRoot(root), apiKey, options)
            Protocol.Gemini -> geminiModels(http, errors, root, apiKey, options)
        }
        return models.filter { it.id.isNotBlank() }.distinctBy { it.id }.sortedBy { it.id }
    }

    private fun openAiRequest(url: String, apiKey: String, options: EngineOptions): Request =
        Request.Builder().url(url).apply {
            if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
            RequestCustomizer.applyHeaders(this, options)
        }.build()

    /** Reads the optional metadata OpenRouter, vLLM, LM Studio and similar servers attach to `/models` rows. */
    internal fun openAiModels(page: JsonObject): List<RemoteModel> = page.arr("data").mapNotNull { element ->
        val row = element.asObject() ?: return@mapNotNull null
        val id = row.str("id") ?: return@mapNotNull null
        val context = row.int("context_length") ?: row.int("context_window") ?: row.int("max_model_len")
            ?: row.obj("top_provider")?.int("context_length")
        val params = row.arr("supported_parameters").mapNotNull { (it as? JsonPrimitive)?.content }
        val reasoning = when {
            "reasoning" in params || "include_reasoning" in params -> ReasoningInfo(
                supported = true,
                levels = Reasoning.parseLevels(listOf("none", "low", "medium", "high"), toggle = false),
            )
            params.isNotEmpty() -> ReasoningInfo.None
            else -> null
        }
        val inputs = row.obj("architecture")?.arr("input_modalities")?.mapNotNull { (it as? JsonPrimitive)?.content }
        val caps = if (context != null || reasoning != null || inputs != null) {
            DetectedCapabilities(reasoning, context, inputs?.contains("image"), CapabilitySource.Remote)
        } else {
            null
        }
        RemoteModel(id, row.str("name")?.takeIf { it != id }, caps)
    }

    private suspend fun anthropicModels(
        http: OkHttpClient,
        errors: ErrorMapper,
        root: String,
        apiKey: String,
        options: EngineOptions,
    ): List<RemoteModel> {
        val models = mutableListOf<RemoteModel>()
        var after: String? = null
        repeat(MAX_PAGES) {
            val url = "$root/models".toHttpUrl().newBuilder()
                .addQueryParameter("limit", "1000")
                .apply { after?.let { addQueryParameter("after_id", it) } }
                .build()
            val page = fetch(
                http,
                errors,
                Request.Builder().url(url)
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", AnthropicEngine.ANTHROPIC_VERSION)
                    .apply { RequestCustomizer.applyHeaders(this, options) }
                    .build(),
            )
            page.arr("data").mapNotNullTo(models) { element ->
                val row = element.asObject() ?: return@mapNotNullTo null
                val id = row.str("id") ?: return@mapNotNullTo null
                val context = row.int("max_input_tokens")
                RemoteModel(
                    id,
                    row.str("display_name"),
                    context?.let { DetectedCapabilities(contextWindow = it, source = CapabilitySource.Remote) },
                )
            }
            after = page.str("last_id")
            if (page.bool("has_more") != true || after == null) return models
        }
        return models
    }

    private suspend fun geminiModels(
        http: OkHttpClient,
        errors: ErrorMapper,
        root: String,
        apiKey: String,
        options: EngineOptions,
    ): List<RemoteModel> {
        val models = mutableListOf<RemoteModel>()
        var token: String? = null
        repeat(MAX_PAGES) {
            val url = "$root/models".toHttpUrl().newBuilder()
                .addQueryParameter("pageSize", "1000")
                .apply { token?.let { addQueryParameter("pageToken", it) } }
                .build()
            val page = fetch(
                http,
                errors,
                Request.Builder().url(url).header("x-goog-api-key", apiKey).apply { RequestCustomizer.applyHeaders(this, options) }.build(),
            )
            page.arr("models").mapNotNullTo(models) { element ->
                val model = element.asObject() ?: return@mapNotNullTo null
                val methods = model.arr("supportedGenerationMethods").map { it.toString().trim('"') }
                if (methods.isNotEmpty() && "generateContent" !in methods) return@mapNotNullTo null
                val id = model.str("name")?.removePrefix("models/") ?: return@mapNotNullTo null
                val thinking = model.bool("thinking")
                RemoteModel(
                    id,
                    model.str("displayName"),
                    DetectedCapabilities(
                        reasoning = thinking?.let { if (it) CapabilityDetector.byFamily(id).reasoning ?: ReasoningInfo.forced() else ReasoningInfo.None },
                        contextWindow = model.int("inputTokenLimit"),
                        source = CapabilitySource.Remote,
                    ),
                )
            }
            token = page.str("nextPageToken")?.takeIf { it.isNotEmpty() }
            if (token == null) return models
        }
        return models
    }

    private suspend fun fetch(http: OkHttpClient, errors: ErrorMapper, request: Request): JsonObject {
        val body = fetchText(http, request, errors)
        return parseJsonOrNull(body) as? JsonObject
            ?: throw LlmException(errors.scrub("Unexpected model list response"), retryable = false)
    }

    private const val MAX_PAGES = 10
}

/**
 * Applies provider-level settings every wire shares: the provider system prompt goes first, and the requested
 * reasoning effort is snapped to what the model accepts.
 */
internal class CustomizedEngine(private val inner: LlmEngine, private val options: EngineOptions) : LlmEngine {
    override val id: String get() = inner.id
    override val capabilities get() = inner.capabilities

    override fun stream(request: LlmRequest): Flow<LlmEvent> = inner.stream(customize(request))

    internal fun customize(request: LlmRequest): LlmRequest {
        val prompt = options.systemPrompt?.trim().orEmpty()
        val messages = if (prompt.isEmpty()) request.messages else listOf(ChatMessage.system(prompt)) + request.messages
        val reasoning = options.reasoning?.let { Reasoning.snap(request.reasoning, it) } ?: request.reasoning
        return request.copy(messages = messages, reasoning = reasoning)
    }
}
