package atom.llm.cloud

import atom.core.llm.ChatMessage
import atom.core.llm.ContentPart
import atom.core.llm.FinishReason
import atom.core.llm.LlmEvent
import atom.core.llm.LlmRequest
import atom.core.llm.ReasoningEffort
import atom.core.llm.Role
import atom.core.llm.ToolCall
import atom.core.llm.ToolSpec
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

class OpenAiChatEngineTest {
    private lateinit var server: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() = server.close()

    private fun engine(providerId: String = "openai", key: String = "sk-test-secret-123") =
        CloudEngines.create(Providers.byId(providerId)!!, key, "gpt-test", server.url("/v1/").toString())

    private val request = LlmRequest(model = "gpt-test", messages = listOf(ChatMessage.user("hi")))

    @Test
    fun streamsTextReasoningAndUsage() {
        server.enqueueSse(
            sseBody(
                """{"choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"thinking"}}]}""",
                """{"choices":[{"index":0,"delta":{"content":"Hel"}}]}""",
                """{"choices":[{"index":0,"delta":{"content":"lo"},"finish_reason":null}]}""",
                """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                """{"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":3}}""",
                "[DONE]",
            ),
        )

        val events = engine().collect(request)

        assertEquals("Hello", events.text())
        assertEquals(listOf(LlmEvent.ReasoningDelta("thinking")), events.filterIsInstance<LlmEvent.ReasoningDelta>())
        assertEquals(LlmEvent.Usage(12, 3), events.filterIsInstance<LlmEvent.Usage>().single())
        assertEquals(listOf(LlmEvent.Finished(FinishReason.Stop)), events.finished())
        assertTrue(events.last() is LlmEvent.Finished)

        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.url.encodedPath)
        assertEquals("Bearer sk-test-secret-123", recorded.headers["Authorization"])
    }

    @Test
    fun accumulatesToolCallFragmentsByIndex() {
        server.enqueueSse(
            sseBody(
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"get_weather","arguments":""}}]}}]}""",
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"ci"}}]}}]}""",
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"id":"call_b","type":"function","function":{"name":"open_app","arguments":""}}]}}]}""",
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ty\": \"Pa"}}]}}]}""",
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ris\"}"}}]}}]}""",
                """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
                "[DONE]",
            ),
        )

        val events = engine().collect(request)

        assertEquals(
            listOf(
                ToolCall("call_a", "get_weather", """{"city": "Paris"}"""),
                ToolCall("call_b", "open_app", "{}"),
            ),
            events.calls(),
        )
        assertEquals(listOf(LlmEvent.Finished(FinishReason.ToolCalls)), events.finished())
    }

    @Test
    fun unauthorizedIsNotRetryableAndHidesKey() {
        server.enqueueJson(401, """{"error":{"message":"Incorrect API key provided: sk-test-secret-123","type":"invalid_request_error"}}""")

        val error = engine().failure(request)

        assertFalse(error.retryable)
        assertEquals(401, error.statusCode)
        assertTrue("Incorrect API key provided" in error.message!!)
        assertFalse("sk-test-secret-123" in error.message!!)
    }

    @Test
    fun rateLimitAndServerErrorsAreRetryable() {
        server.enqueueJson(429, """{"error":{"message":"Rate limit reached"}}""")
        server.enqueueJson(503, "upstream unavailable")

        val rateLimited = engine().failure(request)
        val unavailable = engine().failure(request)

        assertTrue(rateLimited.retryable)
        assertEquals(429, rateLimited.statusCode)
        assertTrue("Rate limit reached" in rateLimited.message!!)
        assertTrue(unavailable.retryable)
        assertTrue("upstream unavailable" in unavailable.message!!)
    }

    @Test
    fun truncatedStreamIsRetryable() {
        server.enqueueSse(sseBody("""{"choices":[{"index":0,"delta":{"content":"Hel"}}]}"""))

        assertTrue(engine().failure(request).retryable)
    }

    @Test
    fun serializesMessagesToolsAndReasoning() {
        server.enqueueSse(sseBody("""{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""", "[DONE]"))
        val call = ToolCall("call_1", "get_weather", """{"city":"Paris"}""")
        val history = listOf(
            ChatMessage.system("be brief"),
            ChatMessage(Role.User, listOf(ContentPart.Text("what is this?"), ContentPart.Image("image/png", "QUJD"))),
            ChatMessage.assistant("", listOf(call)),
            ChatMessage.toolResult(call, listOf(ContentPart.Text("sunny"), ContentPart.Image("image/jpeg", "WFla"))),
        )

        engine().collect(
            LlmRequest(
                model = "gpt-5.5",
                messages = history,
                tools = listOf(ToolSpec("get_weather", "Weather lookup", weatherSchema)),
                maxOutputTokens = 512,
                reasoning = ReasoningEffort.High,
            ),
        )

        val body = server.takeRequest().json()
        assertEquals("gpt-5.5", body["model"]!!.jsonPrimitive.content)
        assertEquals(true, body["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("512", body["max_completion_tokens"]!!.jsonPrimitive.content)
        assertNull(body["max_tokens"])
        assertEquals("high", body["reasoning_effort"]!!.jsonPrimitive.content)

        val tool = body["tools"]!!.jsonArray.single().jsonObject
        assertEquals("function", tool["type"]!!.jsonPrimitive.content)
        assertEquals("get_weather", tool["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(weatherSchema, tool["function"]!!.jsonObject["parameters"])

        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user", "assistant", "tool", "user"), messages.map { it["role"]!!.jsonPrimitive.content })
        val userParts = messages[1]["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals("data:image/png;base64,QUJD", userParts[1]["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        val assistantCall = messages[2]["tool_calls"]!!.jsonArray.single().jsonObject
        assertEquals("call_1", assistantCall["id"]!!.jsonPrimitive.content)
        assertEquals("""{"city":"Paris"}""", assistantCall["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)
        assertEquals("call_1", messages[3]["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("sunny", messages[3]["content"]!!.jsonPrimitive.content)
        val toolImage = messages[4]["content"]!!.jsonArray[1].jsonObject
        assertEquals("data:image/jpeg;base64,WFla", toolImage["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun compatibleProvidersUseMaxTokensAndOpenRouterHeaders() {
        server.enqueueSse(sseBody("""{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""", "[DONE]"))

        engine("openrouter").collect(request.copy(reasoning = ReasoningEffort.Low))

        val recorded = server.takeRequest()
        val body: JsonObject = recorded.json()
        assertEquals("2048", body["max_tokens"]!!.jsonPrimitive.content)
        assertEquals("low", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("Atom", recorded.headers["X-Title"])
        assertTrue(recorded.headers["HTTP-Referer"] != null)
    }

    @Test
    fun cancellingTheCollectorStopsTheStream() {
        val head = sseBody("""{"choices":[{"index":0,"delta":{"content":"first"}}]}""")
        val tail = (1..200).joinToString("") { sseBody("""{"choices":[{"index":0,"delta":{"content":"$it"}}]}""") }
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "text/event-stream")
                .body(head + tail)
                .throttleBody(head.length.toLong(), 1, TimeUnit.SECONDS)
                .build(),
        )

        val first = runBlocking { withTimeout(5_000) { engine().stream(request).first() } }

        assertEquals(LlmEvent.TextDelta("first"), first)
    }
}
