package atom.llm.cloud

import atom.core.llm.ChatMessage
import atom.core.llm.LlmRequest
import atom.core.llm.ReasoningEffort
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

/** A provider that says how long to wait (Retry-After on 429 or 503) gets that wait; thinking requests wait longer for bytes. */
class RetryAfterTest {
    private lateinit var server: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() = server.close()

    private fun failWith(code: Int, retryAfter: String?): atom.core.llm.LlmException {
        server.enqueue(
            MockResponse.Builder().code(code).addHeader("Content-Type", "application/json")
                .apply { if (retryAfter != null) addHeader("Retry-After", retryAfter) }
                .body("""{"error":{"message":"slow down"}}""").build(),
        )
        val engine = CloudEngines.create(Providers.byId("deepseek")!!, "key-abcdef", "deepseek-flash", server.url("/v1").toString())
        return engine.failure(LlmRequest("deepseek-flash", listOf(ChatMessage.user("hi"))))
    }

    @Test
    fun theProvidersWaitReachesTheAgent() {
        val e = failWith(429, "7")
        assertTrue(e.retryable)
        assertEquals(7_000, e.retryAfterMs)
        assertEquals(30_000, failWith(503, "30").retryAfterMs)
        assertNull(failWith(429, null).retryAfterMs)
        // Only rate limits and unavailability carry it.
        assertNull(failWith(500, "5").retryAfterMs)
    }

    @Test
    fun retryAfterReadsSecondsAndDates() {
        assertEquals(12_000, retryAfterMs("12"))
        assertEquals(1_500, retryAfterMs("1.5"))
        assertEquals(60_000, retryAfterMs("Wed, 21 Oct 2015 07:29:00 GMT", now = 1_445_412_480_000))
        assertNull(retryAfterMs("Wed, 21 Oct 2015 07:28:00 GMT", now = 1_445_412_480_000), "in the past")
        assertNull(retryAfterMs("soon"))
        assertNull(retryAfterMs("0"))
    }

    @Test
    fun thinkingRequestsWaitLongerBetweenBytes() {
        val request = LlmRequest("m", listOf(ChatMessage.user("hi")))
        assertSame(CloudHttp.reasoningClient, CloudHttp.client.forRequest(request))
        assertEquals(REASONING_READ_TIMEOUT_S * 1_000, CloudHttp.client.forRequest(request).readTimeoutMillis.toLong())
        assertSame(CloudHttp.client, CloudHttp.client.forRequest(request.copy(reasoning = ReasoningEffort.Off)))
    }
}
