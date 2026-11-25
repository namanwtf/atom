package atom.voice.cloud

import atom.voice.speech.HttpErrors
import atom.voice.speech.SpeechError
import atom.voice.speech.SpeechException
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

internal val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

internal fun String.jsonBody(): RequestBody = toRequestBody(JSON_TYPE)

/** Suspends until the response headers arrive; cancelling the coroutine cancels the call. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(networkError(e))
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, _, _ -> response.close() }
        }
    })
}

internal fun networkError(e: IOException): SpeechException =
    if (e is InterruptedIOException) SpeechException(SpeechError.Timeout, "timed out", e)
    else SpeechException(SpeechError.Network, e.javaClass.simpleName, e)

/** Executes [request]; throws a [SpeechException] for non-2xx statuses (the body is read for a short reason). */
internal suspend fun OkHttpClient.send(request: Request): Response {
    val response = newCall(request).await()
    if (!response.isSuccessful) {
        val body = withContext(Dispatchers.IO) { runCatching { response.body.string() }.getOrNull() }
        response.close()
        throw SpeechException(HttpErrors.kind(response.code), "HTTP ${response.code}${HttpErrors.snippet(body)?.let { ": $it" } ?: ""}")
    }
    return response
}

/** Executes and returns the whole body as text. */
internal suspend fun OkHttpClient.text(request: Request): String {
    var out = ""
    closingOnCancel(send(request)) { r -> out = withContext(Dispatchers.IO) { readOrThrow { r.body.string() } } }
    return out
}

/** Executes and returns the whole body as bytes. */
internal suspend fun OkHttpClient.bytes(request: Request): ByteArray {
    var out = ByteArray(0)
    closingOnCancel(send(request)) { r -> out = withContext(Dispatchers.IO) { readOrThrow { r.body.bytes() } } }
    return out
}

/**
 * Streams the body in chunks of up to [chunkSize] bytes on the IO dispatcher, calling [onChunk] for each.
 * Cancellation closes the body, which unblocks the read.
 */
internal suspend fun Response.streamBytes(chunkSize: Int = 8192, onChunk: suspend (ByteArray) -> Unit) {
    closingOnCancel(this) { response ->
        val source = response.body.byteStream()
        val buffer = ByteArray(chunkSize)
        withContext(Dispatchers.IO) {
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = readOrThrow { source.read(buffer) }
                if (n < 0) break
                if (n > 0) onChunk(buffer.copyOf(n))
            }
        }
    }
}

/** Streams Server-Sent Events `data:` payloads (one per event). */
internal suspend fun Response.streamSse(onData: suspend (String) -> Unit) {
    closingOnCancel(this) { response ->
        val reader = response.body.charStream().buffered()
        withContext(Dispatchers.IO) {
            val data = StringBuilder()
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = readOrThrow { reader.readLine() } ?: break
                when {
                    line.isEmpty() -> if (data.isNotEmpty()) {
                        onData(data.toString())
                        data.setLength(0)
                    }
                    line.startsWith("data:") -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.removePrefix("data:").trimStart())
                    }
                }
            }
            if (data.isNotEmpty()) onData(data.toString())
        }
    }
}

/**
 * Runs [block] and closes [response] when it ends or when the coroutine is cancelled: closing the body from the
 * watcher unblocks a read that is stuck in the socket, so cancellation never waits for the network.
 */
internal suspend fun closingOnCancel(response: Response, block: suspend (Response) -> Unit) {
    coroutineScope {
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                runCatching { response.close() }
            }
        }
        try {
            block(response)
        } finally {
            watcher.cancel()
        }
    }
}

private inline fun <T> readOrThrow(block: () -> T): T = try {
    block()
} catch (e: IOException) {
    throw networkError(e)
}

internal fun hexToBytes(hex: String): ByteArray {
    val clean = hex.trim()
    val out = ByteArray(clean.length / 2)
    for (i in out.indices) {
        val hi = Character.digit(clean[2 * i], 16)
        val lo = Character.digit(clean[2 * i + 1], 16)
        require(hi >= 0 && lo >= 0) { "not hex" }
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}
