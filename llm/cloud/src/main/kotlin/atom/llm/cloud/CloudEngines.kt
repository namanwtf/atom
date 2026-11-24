package atom.llm.cloud

import atom.core.llm.LlmEngine
import atom.core.llm.LlmException
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

object CloudEngines {

    fun create(provider: Provider, apiKey: String, model: String, baseUrl: String = provider.baseUrl): LlmEngine =
        create(provider, apiKey, model, baseUrl, CloudHttp.client)

    internal fun create(provider: Provider, apiKey: String, model: String, baseUrl: String, http: OkHttpClient): LlmEngine =
        when (provider.wire) {
            Wire.OpenAiChat -> OpenAiChatEngine(provider, apiKey, model, baseUrl, http)
            Wire.Anthropic -> AnthropicEngine(provider, apiKey, model, baseUrl, http)
            Wire.Gemini -> GeminiEngine(provider, apiKey, model, baseUrl, http)
        }

    /** Model ids the key can use, sorted; empty when the provider has no listing endpoint. */
    suspend fun listModels(provider: Provider, apiKey: String, baseUrl: String = provider.baseUrl): List<String> =
        listModels(provider, apiKey, baseUrl, CloudHttp.client)

    internal suspend fun listModels(provider: Provider, apiKey: String, baseUrl: String, http: OkHttpClient): List<String> {
        if (!provider.supportsModelList) return emptyList()
        val errors = ErrorMapper(provider.displayName, apiKey)
        val root = baseUrl.trimEnd('/')
        val ids = when (provider.wire) {
            Wire.OpenAiChat -> dataIds(fetch(http, errors, openAiRequest("$root/models", apiKey)))
            Wire.Anthropic -> anthropicModels(http, errors, root, apiKey)
            Wire.Gemini -> geminiModels(http, errors, root, apiKey)
        }
        return ids.filter { it.isNotBlank() }.distinct().sorted()
    }

    private fun openAiRequest(url: String, apiKey: String): Request =
        Request.Builder().url(url).apply {
            if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
        }.build()

    private suspend fun anthropicModels(http: OkHttpClient, errors: ErrorMapper, root: String, apiKey: String): List<String> {
        val ids = mutableListOf<String>()
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
                    .build(),
            )
            ids += dataIds(page)
            after = page.str("last_id")
            if (page.bool("has_more") != true || after == null) return ids
        }
        return ids
    }

    private suspend fun geminiModels(http: OkHttpClient, errors: ErrorMapper, root: String, apiKey: String): List<String> {
        val ids = mutableListOf<String>()
        var token: String? = null
        repeat(MAX_PAGES) {
            val url = "$root/models".toHttpUrl().newBuilder()
                .addQueryParameter("pageSize", "1000")
                .apply { token?.let { addQueryParameter("pageToken", it) } }
                .build()
            val page = fetch(http, errors, Request.Builder().url(url).header("x-goog-api-key", apiKey).build())
            page.arr("models").mapNotNullTo(ids) { element ->
                val model = element.asObject() ?: return@mapNotNullTo null
                val methods = model.arr("supportedGenerationMethods").map { it.toString().trim('"') }
                if (methods.isNotEmpty() && "generateContent" !in methods) null
                else model.str("name")?.removePrefix("models/")
            }
            token = page.str("nextPageToken")?.takeIf { it.isNotEmpty() }
            if (token == null) return ids
        }
        return ids
    }

    private fun dataIds(page: JsonObject): List<String> =
        page.arr("data").mapNotNull { it.asObject()?.str("id") }

    private suspend fun fetch(http: OkHttpClient, errors: ErrorMapper, request: Request): JsonObject {
        val body = fetchText(http, request, errors)
        return parseJsonOrNull(body) as? JsonObject
            ?: throw LlmException(errors.scrub("Unexpected model list response"), retryable = false)
    }

    private const val MAX_PAGES = 10
}
