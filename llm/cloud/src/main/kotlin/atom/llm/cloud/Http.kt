package atom.llm.cloud

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

internal object CloudHttp {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** [client] for requests that may think: the same pool, a longer wait between bytes. */
    val reasoningClient: OkHttpClient by lazy { client.newBuilder().readTimeout(REASONING_READ_TIMEOUT_S, TimeUnit.SECONDS).build() }
}

/** How long a stream may go quiet while the model thinks; some providers send nothing until the thinking is done. */
internal const val REASONING_READ_TIMEOUT_S = 300L

/**
 * The client for [request]: one that waits up to [REASONING_READ_TIMEOUT_S] between bytes unless thinking is off
 * (a request with a null effort may think, by the model's default). The connection pool is shared, so this costs
 * nothing; a dead connection is still noticed by the connect timeout and the TCP stack.
 */
internal fun OkHttpClient.forRequest(request: atom.core.llm.LlmRequest): OkHttpClient {
    if (request.reasoning == atom.core.llm.ReasoningEffort.Off) return this
    if (readTimeoutMillis.toLong() >= REASONING_READ_TIMEOUT_S * 1_000) return this
    // A client built with a shorter read timeout on purpose (tests, the Stop checks) keeps it.
    if (readTimeoutMillis.toLong() < 120_000) return this
    if (this === CloudHttp.client) return CloudHttp.reasoningClient
    return newBuilder().readTimeout(REASONING_READ_TIMEOUT_S, TimeUnit.SECONDS).build()
}

internal data class SseEvent(val type: String?, val data: String)

/**
 * Streams server-sent events for [request]. Cancelling the collector cancels the HTTP call, so an
 * abandoned agent turn never keeps a socket (and the radio) alive.
 */
internal fun sseEvents(client: OkHttpClient, request: Request, errors: ErrorMapper): Flow<SseEvent> = callbackFlow {
    val listener = object : EventSourceListener() {
        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            trySendBlocking(SseEvent(type, data))
        }

        override fun onClosed(eventSource: EventSource) {
            channel.close()
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            // Runs on OkHttp's thread, also after Stop cancelled the call: it must never throw (ErrorMapper.failure
            // makes sure), or the exception escapes there and takes the whole app down.
            channel.close(errors.failure(t, response))
        }
    }
    val source = EventSources.createFactory(client).newEventSource(request, listener)
    awaitClose { source.cancel() }
}

/** Executes a plain request and returns the body text, mapping failures to [atom.core.llm.LlmException]. */
internal suspend fun fetchText(client: OkHttpClient, request: Request, errors: ErrorMapper): String {
    val call = client.newCall(request)
    val (code, body) = try {
        call.await()
    } catch (e: IOException) {
        throw errors.network(e)
    }
    if (code !in 200..299) throw errors.http(code, body)
    return body
}

/** Executes [request] and returns status and body without interpreting either. */
internal suspend fun rawFetch(client: OkHttpClient, request: Request): Pair<Int, String> = client.newCall(request).await()

private suspend fun Call.await(): Pair<Int, String> = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            val result = try {
                response.use { it.code to it.body.string() }
            } catch (e: IOException) {
                cont.resumeWithException(e)
                return
            }
            cont.resume(result)
        }
    })
}
