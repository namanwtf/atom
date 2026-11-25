package atom.extend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HttpReply(val code: Int, val body: ByteArray, val headers: Map<String, String> = emptyMap()) {
    val text: String get() = body.toString(Charsets.UTF_8)
}

/** A plain GET, abstracted so installers are tested without the network. */
fun interface HttpGet {
    suspend fun get(url: String, headers: Map<String, String>, maxBytes: Long): HttpReply
}

class HttpTooLarge(message: String) : IOException(message)

internal object ExtendHttp {
    /** Idle sockets are dropped after 20 s: nothing stays connected once a call or install is over. */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(2, 20, TimeUnit.SECONDS))
            .build()
    }

    val get = HttpGet { url, headers, maxBytes ->
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build()
        client.newCall(request).await().use { response ->
            withContext(Dispatchers.IO) {
                HttpReply(response.code, readCapped(response, maxBytes), response.headers.associate { it.first.lowercase() to it.second })
            }
        }
    }

    fun readCapped(response: Response, maxBytes: Long): ByteArray {
        val body = response.body
        val declared = body.contentLength()
        if (declared > maxBytes) throw HttpTooLarge("Response is larger than ${maxBytes / 1024} KiB.")
        val out = java.io.ByteArrayOutputStream()
        body.byteStream().use { input ->
            val buf = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) throw HttpTooLarge("Response is larger than ${maxBytes / 1024} KiB.")
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }
}

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}

/** Readable reasons for common network failures, including Android's cleartext block for http:// URLs. */
internal fun networkReason(e: Throwable): String {
    val msg = e.message.orEmpty()
    return when {
        msg.contains("CLEARTEXT", ignoreCase = true) ->
            "Plain http:// is blocked by the app's network security settings. Use https:// or allow cleartext traffic."
        e is java.net.UnknownHostException -> "Host not found. Check the address and your connection."
        e is java.net.ConnectException -> "Could not connect. Is the server running and reachable from this phone?"
        e is java.net.SocketTimeoutException -> "The server did not answer in time."
        e is javax.net.ssl.SSLException -> "Secure connection failed: ${msg.take(120)}"
        else -> msg.ifBlank { e::class.simpleName ?: "Network error" }.take(200)
    }
}
