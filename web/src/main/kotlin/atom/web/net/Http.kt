package atom.web.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A request described as data, so builders can be unit-tested without a network. */
data class HttpSpec(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val contentType: String = "application/json",
    /** Multipart parts (name to value); used instead of [body] when non-empty. */
    val multipart: List<Part> = emptyList(),
) {
    data class Part(val name: String, val value: String? = null, val bytes: ByteArray? = null, val fileName: String? = null, val mime: String? = null)

    override fun toString(): String = "HttpSpec($method $url)"
}

class HttpResult(val code: Int, val body: ByteArray, val contentType: String?, val finalUrl: String) {
    val ok: Boolean get() = code in 200..299
    val text: String get() = body.toString(Charsets.UTF_8)
}

object Http {
    const val USER_AGENT = "Atom/1.0 (Android)"
    const val MAX_BODY = 8 * 1024 * 1024

    /** Connections idle out after 30 s so no sockets linger once the agent is done. */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .connectionPool(okhttp3.ConnectionPool(2, 30, TimeUnit.SECONDS))
            .followRedirects(true)
            .build()
    }

    /** Long-running image generation calls. */
    val slowClient: OkHttpClient by lazy {
        client.newBuilder().readTimeout(300, TimeUnit.SECONDS).callTimeout(330, TimeUnit.SECONDS)
            // These calls carry API keys in headers; a redirect is reported, never followed.
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    fun toRequest(spec: HttpSpec): Request {
        val body: RequestBody? = when {
            spec.multipart.isNotEmpty() -> okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM).apply {
                for (p in spec.multipart) {
                    if (p.bytes != null) {
                        addFormDataPart(p.name, p.fileName ?: "file", p.bytes.toRequestBody((p.mime ?: "application/octet-stream").toMediaType()))
                    } else {
                        addFormDataPart(p.name, p.value.orEmpty())
                    }
                }
            }.build()
            spec.body != null -> spec.body.toRequestBody(spec.contentType.toMediaType())
            spec.method.equals("POST", true) || spec.method.equals("PUT", true) -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        return Request.Builder().url(spec.url).method(spec.method.uppercase(), body).apply {
            header("User-Agent", USER_AGENT)
            spec.headers.forEach { (k, v) -> header(k, v) }
        }.build()
    }

    suspend fun execute(spec: HttpSpec, http: OkHttpClient = client, maxBytes: Int = MAX_BODY): HttpResult =
        call(http.newCall(toRequest(spec)), maxBytes)

    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), maxBytes: Int = MAX_BODY): HttpResult =
        execute(HttpSpec("GET", url, headers), maxBytes = maxBytes)

    private suspend fun call(call: Call, maxBytes: Int): HttpResult = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        val bytes = readCapped(it, maxBytes)
                        cont.resume(HttpResult(it.code, bytes, it.header("Content-Type"), it.request.url.toString()))
                    }
                } catch (e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
        })
    }

    private fun readCapped(response: Response, maxBytes: Int): ByteArray {
        val source = response.body.byteStream()
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < maxBytes) {
            val n = source.read(buf, 0, minOf(buf.size, maxBytes - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** A short, key-free description of a failed response for the model or the user. */
    fun describeError(result: HttpResult): String {
        val detail = runCatching {
            val el = atom.web.search.JsonPath.parse(result.text)
            listOf("error.message", "message", "error", "msg", "detail", "code")
                .firstNotNullOfOrNull { atom.web.search.JsonPath.string(el, it) }
        }.getOrNull() ?: result.text.take(200).replace(Regex("\\s+"), " ")
        return "HTTP ${result.code}: ${detail.take(300)}"
    }
}
