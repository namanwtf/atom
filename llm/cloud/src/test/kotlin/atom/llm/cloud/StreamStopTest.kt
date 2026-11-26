package atom.llm.cloud

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSource

/** Stopping a run cancels the stream mid-response; nothing may then throw on OkHttp's own threads. */
class StreamStopTest {
    @Test
    fun stoppingMidStreamThrowsNothingOnOkHttpThreads() {
        val uncaught = CopyOnWriteArrayList<String>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e -> uncaught += "${thread.name}: $e" }
        val client = OkHttpClient()
        try {
            MockWebServer().use { server ->
                server.start()
                val body = sseBody(*Array(400) { """{"choices":[{"index":0,"delta":{"content":"word$it "}}]}""" })
                server.enqueue(
                    MockResponse.Builder()
                        .addHeader("Content-Type", "text/event-stream")
                        .body(body)
                        .throttleBody(256, 100, TimeUnit.MILLISECONDS)
                        .build(),
                )
                val request = Request.Builder().url(server.url("/v1/chat/completions")).build()
                runBlocking {
                    // Taking the first event and leaving is what Stop does to the collector.
                    val first = withTimeout(20_000) { sseEvents(client, request, ErrorMapper("Test", "sk-test-secret")).first() }
                    assertTrue(first.data.contains("word0"))
                }
                // The cancelled call reports its failure on an OkHttp thread shortly after.
                Thread.sleep(2_000)
            }
        } finally {
            client.dispatcher.executorService.shutdown()
            client.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS)
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
        assertEquals(emptyList(), uncaught.toList())
    }

    @Test
    fun aFailureAfterTheStreamOpenedDoesNotReadTheStrippedBody() {
        // After onOpen, OkHttp reports failures with the response whose body it replaced by one that throws on read.
        val stripped = Response.Builder()
            .request(Request.Builder().url("http://localhost/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", "text/event-stream")
            .body(UnreadableBody)
            .build()
        val e = ErrorMapper("Test", "sk-test-secret").failure(IOException("canceled"), stripped)
        assertTrue(e.retryable)
        assertTrue(e.message.orEmpty().contains("network error"), e.message)
    }

    @Test
    fun aWrongContentTypeStillReportsTheBody() {
        val response = Response.Builder()
            .request(Request.Builder().url("http://localhost/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", "application/json")
            .body("""{"error":{"message":"model is warming up"}}""".toResponseBody(null))
            .build()
        val e = ErrorMapper("Test", "sk-test-secret").failure(IllegalStateException("Invalid content-type: application/json"), response)
        assertTrue(e.message.orEmpty().contains("model is warming up"), e.message)
    }

    private object UnreadableBody : ResponseBody() {
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = -1
        override fun source(): BufferedSource = throw IllegalStateException("Unreadable ResponseBody")
    }
}
