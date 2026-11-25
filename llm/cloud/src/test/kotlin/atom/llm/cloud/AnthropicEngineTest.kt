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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockWebServer

class AnthropicEngineTest {
    private lateinit var server: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() = server.close()

    private fun engine(model: String = "claude-opus-5-5") =
        CloudEngines.create(Providers.byId("anthropic")!!, "sk-ant-secret", model, server.url("/v1").toString())

    private val request = LlmRequest(model = "claude-opus-5-5", messages = listOf(ChatMessage.user("hi")))

    @Test
    fun streamsTextThinkingAndUsage() {
        server.enqueueSse(
            namedSseBody(
                "message_start" to """{"type":"message_start","message":{"id":"msg_1","usage":{"input_tokens":10,"cache_read_input_tokens":5,"output_tokens":1}}}""",
                "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
                "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"hmm"}}""",
                "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"abc"}}""",
                "content_block_stop" to """{"type":"content_block_stop","index":0}""",
                "content_block_start" to """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
                "ping" to """{"type":"ping"}""",
                "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Hello"}}""",
                "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":" there"}}""",
                "content_block_stop" to """{"type":"content_block_stop","index":1}""",
                "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":7}}""",
                "message_stop" to """{"type":"message_stop"}""",
            ),
        )

        val events = engine().collect(request)

        assertEquals("Hello there", events.text())
        assertEquals(listOf(LlmEvent.ReasoningDelta("hmm")), events.filterIsInstance<LlmEvent.ReasoningDelta>())
        assertEquals(LlmEvent.Usage(15, 7, cacheReadTokens = 5), events.filterIsInstance<LlmEvent.Usage>().single())
        assertEquals(LlmEvent.Finished(FinishReason.Stop), events.last())

        val recorded = server.takeRequest()
        assertEquals("/v1/messages", recorded.url.encodedPath)
        assertEquals("sk-ant-secret", recorded.headers["x-api-key"])
        assertEquals("2023-06-01", recorded.headers["anthropic-version"])
    }

    @Test
    fun accumulatesInputJsonDeltas() {
        server.enqueueSse(
            namedSseBody(
                "message_start" to """{"type":"message_start","message":{"usage":{"input_tokens":3,"output_tokens":1}}}""",
                "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
                "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Checking."}}""",
                "content_block_stop" to """{"type":"content_block_stop","index":0}""",
                "content_block_start" to """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"get_weather","input":{}}}""",
                "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":""}}""",
                "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"city\":"}}""",
                "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":" \"Par"}}""",
                "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"is\"}"}}""",
                "content_block_stop" to """{"type":"content_block_stop","index":1}""",
                "content_block_start" to """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_2","name":"go_home","input":{}}}""",
                "content_block_stop" to """{"type":"content_block_stop","index":2}""",
                "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":20}}""",
                "message_stop" to """{"type":"message_stop"}""",
            ),
        )

        val events = engine().collect(request)

        assertEquals(
            listOf(ToolCall("toolu_1", "get_weather", """{"city": "Paris"}"""), ToolCall("toolu_2", "go_home", "{}")),
            events.calls(),
        )
        assertEquals(listOf(LlmEvent.Finished(FinishReason.ToolCalls)), events.finished())
    }

    @Test
    fun errorStatusesMapToRetryability() {
        server.enqueueJson(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
        server.enqueueJson(429, """{"type":"error","error":{"type":"rate_limit_error","message":"slow down"}}""")

        val auth = engine().failure(request)
        val limited = engine().failure(request)

        assertFalse(auth.retryable)
        assertTrue("invalid x-api-key" in auth.message!!)
        assertTrue(limited.retryable)
        assertEquals(429, limited.statusCode)
    }

    @Test
    fun overloadedEventMidStreamIsRetryable() {
        server.enqueueSse(
            namedSseBody(
                "message_start" to """{"type":"message_start","message":{"usage":{"input_tokens":3,"output_tokens":1}}}""",
                "error" to """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""",
            ),
        )

        val error = engine().failure(request)

        assertTrue(error.retryable)
        assertTrue("Overloaded" in error.message!!)
    }

    @Test
    fun serializesSystemToolsAndMergedToolResults() {
        server.enqueueSse(namedSseBody("message_stop" to """{"type":"message_stop"}"""))
        val first = ToolCall("toolu_a", "get_weather", """{"city":"Paris"}""")
        val second = ToolCall("toolu_b", "screenshot", "")
        val history = listOf(
            ChatMessage.system("be brief"),
            ChatMessage(Role.User, listOf(ContentPart.Text("look"), ContentPart.Image("image/png", "QUJD"))),
            ChatMessage.assistant("On it.", listOf(first, second)),
            ChatMessage.toolResult(first, listOf(ContentPart.Text("sunny"))),
            ChatMessage.toolResult(second, listOf(ContentPart.Image("image/jpeg", "WFla"))),
            ChatMessage.user("Stop repeating."),
        )

        engine().collect(
            LlmRequest(
                model = "claude-opus-5-5",
                messages = history,
                tools = listOf(ToolSpec("get_weather", "Weather lookup", weatherSchema)),
                maxOutputTokens = 1000,
                reasoning = ReasoningEffort.Medium,
            ),
        )

        val body = server.takeRequest().json()
        assertEquals("be brief", body["system"]!!.jsonPrimitive.content)
        assertEquals("1000", body["max_tokens"]!!.jsonPrimitive.content)
        assertEquals("adaptive", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("medium", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertNull(body["temperature"])

        val tool = body["tools"]!!.jsonArray.single().jsonObject
        assertEquals("get_weather", tool["name"]!!.jsonPrimitive.content)
        assertEquals(weatherSchema, tool["input_schema"])

        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "assistant", "user"), messages.map { it["role"]!!.jsonPrimitive.content })
        val image = messages[0]["content"]!!.jsonArray[1].jsonObject
        assertEquals("base64", image["source"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val assistant = messages[1]["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("text", "tool_use", "tool_use"), assistant.map { it["type"]!!.jsonPrimitive.content })
        assertEquals("Paris", assistant[1]["input"]!!.jsonObject["city"]!!.jsonPrimitive.content)
        assertEquals(0, assistant[2]["input"]!!.jsonObject.size)
        val results = messages[2]["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("tool_result", "tool_result", "text"), results.map { it["type"]!!.jsonPrimitive.content })
        assertEquals("toolu_a", results[0]["tool_use_id"]!!.jsonPrimitive.content)
        assertEquals("image", results[1]["content"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun legacyModelsUseThinkingBudgetOutsideToolLoops() {
        server.enqueueSse(namedSseBody("message_stop" to """{"type":"message_stop"}"""))

        engine("claude-sonnet-4-5").collect(
            LlmRequest(model = "claude-sonnet-4-5", messages = listOf(ChatMessage.user("hi")), maxOutputTokens = 1000, reasoning = ReasoningEffort.Low),
        )

        val body = server.takeRequest().json()
        assertEquals("enabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("1024", body["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.content)
        assertEquals("2024", body["max_tokens"]!!.jsonPrimitive.content)
        assertNull(body["output_config"])
    }
}
