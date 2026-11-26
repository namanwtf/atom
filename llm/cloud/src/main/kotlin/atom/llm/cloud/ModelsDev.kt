package atom.llm.cloud

import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

@Serializable
data class CatalogModelEntry(
    val id: String,
    val name: String,
    val capabilities: DetectedCapabilities,
    val toolCall: Boolean = true,
)

@Serializable
data class CatalogProvider(
    val id: String,
    val name: String,
    /** Null when the catalog gives no endpoint; the user fills it in after import. */
    val baseUrl: String?,
    val wire: Wire,
    val doc: String? = null,
    val models: List<CatalogModelEntry>,
)

/** Where the catalog on screen came from. */
enum class CatalogSource { Bundled, Cached, Online }

/** Why an online refresh of the catalog failed, so the page can say it in plain words. */
sealed interface CatalogError {
    data class Http(val code: Int) : CatalogError
    data object Timeout : CatalogError
    data object Dns : CatalogError
    data object Ssl : CatalogError
    data object TooLarge : CatalogError
    data object Format : CatalogError
    data class Network(val detail: String?) : CatalogError
}

class CatalogException(val error: CatalogError) : IOException(error.toString())

/** The catalog snapshot that ships with the app, and when it was taken. */
data class CatalogSnapshot(val providers: List<CatalogProvider>, val fetchedAtMillis: Long?)

/**
 * The models.dev community catalog. Fetched only when the user opens the import screen; the request carries
 * no credentials or identifying headers, and the reduced result is cached by the caller.
 */
object ModelsDev {
    const val URL = "https://models.dev/api.json"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Serializable
    private class MdOption(val type: String = "", val values: List<String>? = null)

    @Serializable
    private class MdLimit(val context: Long? = null)

    @Serializable
    private class MdModalities(val input: List<String>? = null)

    @Serializable
    private class MdModel(
        val id: String = "",
        val name: String? = null,
        val reasoning: Boolean? = null,
        @SerialName("reasoning_options") val reasoningOptions: List<MdOption>? = null,
        val attachment: Boolean? = null,
        @SerialName("tool_call") val toolCall: Boolean? = null,
        val limit: MdLimit? = null,
        val modalities: MdModalities? = null,
    )

    @Serializable
    private class MdProvider(
        val id: String = "",
        val name: String = "",
        val api: String? = null,
        val npm: String? = null,
        val doc: String? = null,
        val models: Map<String, MdModel> = emptyMap(),
    )

    private val rootSerializer = MapSerializer(String.serializer(), MdProvider.serializer())
    private val cacheSerializer = ListSerializer(CatalogProvider.serializer())

    @OptIn(ExperimentalSerializationApi::class)
    fun parse(stream: InputStream): List<CatalogProvider> = reduce(json.decodeFromStream(rootSerializer, stream))

    fun parse(text: String): List<CatalogProvider> = reduce(json.decodeFromString(rootSerializer, text))

    fun encodeCache(providers: List<CatalogProvider>): String = json.encodeToString(cacheSerializer, providers)

    fun decodeCache(text: String): List<CatalogProvider>? =
        runCatching { json.decodeFromString(cacheSerializer, text) }.getOrNull()

    suspend fun fetch(): List<CatalogProvider> = fetch(CloudHttp.client, URL)

    /** Largest response accepted from the catalog host. */
    const val MAX_BYTES = 48L * 1024 * 1024

    private const val SNAPSHOT = "/atom/llm/cloud/models_dev_snapshot.json"
    private const val SNAPSHOT_META = "/atom/llm/cloud/models_dev_snapshot.meta"

    /** The bundled snapshot (built by tools/models_dev/snapshot.py); null only if the resource is missing. */
    fun bundled(): CatalogSnapshot? {
        val stream = ModelsDev::class.java.getResourceAsStream(SNAPSHOT) ?: return null
        val providers = runCatching { stream.use(::parse) }.getOrNull() ?: return null
        val meta = ModelsDev::class.java.getResourceAsStream(SNAPSHOT_META)?.use { it.readBytes().toString(Charsets.UTF_8) }
        return CatalogSnapshot(providers, meta?.let(::snapshotTime))
    }

    /** The `fetched=` time of a snapshot's meta file, in epoch milliseconds. */
    internal fun snapshotTime(meta: String): Long? {
        val value = meta.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("fetched=") }?.removePrefix("fetched=") ?: return null
        return runCatching { java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
    }

    /** Maps a refresh failure to a [CatalogError]. */
    fun classify(t: Throwable): CatalogError = when (t) {
        is CatalogException -> t.error
        is java.net.UnknownHostException -> CatalogError.Dns
        is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> CatalogError.Timeout
        is javax.net.ssl.SSLException -> CatalogError.Ssl
        is kotlinx.serialization.SerializationException, is IllegalArgumentException, is IllegalStateException -> CatalogError.Format
        else -> t.cause?.takeIf { it !== t }?.let { cause -> classify(cause).takeIf { it !is CatalogError.Network } }
            ?: CatalogError.Network(t.message)
    }

    /** Wraps [input] so reading more than [limit] bytes fails with [CatalogError.TooLarge]. */
    internal fun limited(input: InputStream, limit: Long): InputStream = object : java.io.FilterInputStream(input) {
        private var count = 0L

        private fun add(n: Long) {
            if (n > 0) count += n
            if (count > limit) throw CatalogException(CatalogError.TooLarge)
        }

        override fun read(): Int = super.read().also { if (it >= 0) add(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { add(it.toLong()) }

        override fun skip(n: Long): Long = super.skip(n).also { add(it) }
    }

    internal suspend fun fetch(http: OkHttpClient, url: String): List<CatalogProvider> {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        val call = http.newCall(request)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            if (!it.isSuccessful) throw CatalogException(CatalogError.Http(it.code))
                            if (it.body.contentLength() > MAX_BYTES) throw CatalogException(CatalogError.TooLarge)
                            try {
                                limited(it.body.byteStream(), MAX_BYTES).use(::parse)
                            } catch (e: CatalogException) {
                                throw e
                            } catch (e: IOException) {
                                throw e
                            } catch (e: Exception) {
                                throw CatalogException(CatalogError.Format)
                            }
                        }
                    }
                    result.fold(cont::resume, cont::resumeWithException)
                }
            })
        }
    }

    /** Wire for a catalog provider's SDK package; unknown packages are treated as OpenAI-compatible. */
    fun wireFor(npm: String?): Wire = when (npm) {
        "@ai-sdk/anthropic" -> Wire.Anthropic
        "@ai-sdk/google" -> Wire.Gemini
        "@ai-sdk/google-vertex" -> Wire.Vertex
        "@ai-sdk/openai" -> Wire.OpenAiResponses
        else -> Wire.OpenAiChat
    }

    private val DEFAULT_BASE_URLS = mapOf(
        "@ai-sdk/openai" to "https://api.openai.com/v1",
        "@ai-sdk/anthropic" to "https://api.anthropic.com",
        "@ai-sdk/google" to PresetCatalog.GEMINI_BASE_URL,
        "@ai-sdk/xai" to "https://api.x.ai/v1",
        "@ai-sdk/mistral" to "https://api.mistral.ai/v1",
        "@ai-sdk/groq" to "https://api.groq.com/openai/v1",
        "@ai-sdk/deepseek" to "https://api.deepseek.com",
        "@ai-sdk/togetherai" to "https://api.together.xyz/v1",
        "@ai-sdk/deepinfra" to "https://api.deepinfra.com/v1/openai",
        "@ai-sdk/cerebras" to "https://api.cerebras.ai/v1",
        "@ai-sdk/fireworks" to "https://api.fireworks.ai/inference/v1",
        "@ai-sdk/perplexity" to "https://api.perplexity.ai",
        "@openrouter/ai-sdk-provider" to "https://openrouter.ai/api/v1",
    )

    private fun reduce(raw: Map<String, MdProvider>): List<CatalogProvider> = raw.map { (key, p) ->
        val id = p.id.ifBlank { key }
        CatalogProvider(
            id = id,
            name = p.name.ifBlank { id },
            baseUrl = (p.api ?: DEFAULT_BASE_URLS[p.npm])?.trim()?.trimEnd('/')?.takeIf { it.startsWith("http") && '$' !in it },
            wire = wireFor(p.npm),
            doc = p.doc,
            models = p.models.map { (modelKey, m) -> entry(m.id.ifBlank { modelKey }, m) }.sortedBy { it.name.lowercase() },
        )
    }.filter { it.models.isNotEmpty() }.sortedBy { it.name.lowercase() }

    private fun entry(id: String, m: MdModel): CatalogModelEntry {
        val options = m.reasoningOptions.orEmpty()
        val reasoning = when (m.reasoning) {
            null -> null
            false -> ReasoningInfo.None
            true -> ReasoningInfo(
                supported = true,
                levels = Reasoning.parseLevels(
                    options.filter { it.type == "effort" }.flatMap { it.values.orEmpty() },
                    toggle = options.any { it.type == "toggle" },
                ),
            )
        }
        val vision = m.modalities?.input?.contains("image") ?: m.attachment
        val context = m.limit?.context?.takeIf { it > 0 }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
        return CatalogModelEntry(
            id = id,
            name = m.name?.takeIf { it.isNotBlank() } ?: id,
            capabilities = DetectedCapabilities(reasoning, context, vision, CapabilitySource.Catalog),
            toolCall = m.toolCall ?: true,
        )
    }

    /** An imported provider starts disabled and without a key until the user adds one. */
    fun toConfig(provider: CatalogProvider, modelIds: Set<String>, configId: String): ProviderConfig {
        val base = provider.baseUrl.orEmpty()
        return ProviderConfig(
            id = configId,
            displayName = provider.name,
            wire = provider.wire,
            baseUrl = base,
            enabled = false,
            family = Family.fromBaseUrl(base),
            models = provider.models.filter { it.id in modelIds }.map {
                ModelConfig(id = it.id, displayName = it.name, detected = it.capabilities)
            },
            defaultModel = provider.models.firstOrNull { it.id in modelIds }?.id,
            keyHelpUrl = provider.doc,
            importedFrom = provider.id,
        )
    }

    suspend fun parseOffMain(text: String): List<CatalogProvider> = withContext(Dispatchers.Default) { parse(text) }
}
