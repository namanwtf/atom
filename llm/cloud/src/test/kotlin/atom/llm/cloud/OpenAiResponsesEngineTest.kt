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

class OpenAiResponsesEngineTest {
    private lateinit var server: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() = server.close()

    private fun engine(options: EngineOptions = EngineOptions(), family: String = Family.OPENAI) =
        CloudEngines.create(
            Providers.byId("openai")!!.copy(family = family),
            "sk-resp-secret-1",
            "gpt-6.1-sol",
            server.url("/v1").toString(),
            options,
        )

    private fun events(vararg pairs: Pair<String, String>) = namedSseBody(*pairs)

    private val toolTurn = events(
        "response.created" to """{"type":"response.created","response":{"id":"resp_1"}}""",
        "response.reasoning_summary_part.added" to """{"type":"response.reasoning_summary_part.added"}""",
        "response.reasoning_summary_text.delta" to """{"type":"response.reasoning_summary_text.delta","delta":"Plan"}""",
        "response.output_item.done" to """{"type":"response.output_item.done","item":{"id":"rs_1","type":"reasoning","encrypted_content":"ENC","summary":[]}}""",
        "response.output_text.delta" to """{"type":"response.output_text.delta","delta":"Checking"}""",
        "response.output_item.added" to """{"type":"response.output_item.added","output_index":2,"item":{"id":"fc_1","type":"function_call","call_id":"call_x","name":"get_weather","arguments":""}}""",
        "response.function_call_arguments.delta" to """{"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"{\"city\":"}""",
        "response.function_call_arguments.delta" to """{"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"\"Paris\"}"}""",
        "response.output_item.done" to """{"type":"response.output_item.done","item":{"id":"fc_1","type":"function_call","call_id":"call_x","name":"get_weather","arguments":"{\"city\":\"Paris\"}"}}""",
        "response.completed" to """{"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":40,"output_tokens":9}}}""",
    )

    @Test
    fun streamsTextReasoningToolCallsAndUsage() {
        server.enqueueSse(toolTurn)

        val events = engine().collect(LlmRequest("gpt-6.1-sol", listOf(ChatMessage.user("weather?"))))

        assertEquals("Checking", events.text())
        assertEquals("Plan", events.filterIsInstance<LlmEvent.ReasoningDelta>().joinToString("") { it.text })
        assertEquals(listOf(ToolCall("call_x", "get_weather", """{"city":"Paris"}""")), events.calls())
        assertEquals(LlmEvent.Usage(40, 9), events.filterIsInstance<LlmEvent.Usage>().single())
        assertEquals(listOf(LlmEvent.Finished(FinishReason.ToolCalls)), events.finished())
        assertEquals("/v1/responses", server.takeRequest().url.encodedPath)
    }

    @Test
    fun buildsStatelessRequestAndReplaysReasoningItems() {
        server.enqueueSse(toolTurn)
        server.enqueueSse(
            events("response.completed" to """{"type":"response.completed","response":{"usage":{"input_tokens":1,"output_tokens":1}}}"""),
        )
        val engine = engine(EngineOptions(hostedWebSearch = true))
        engine.collect(LlmRequest("gpt-6.1-sol", listOf(ChatMessage.user("weather?")), reasoning = ReasoningEffort.Max))
        val first = server.takeRequest().json()
        assertEquals(false, first["store"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("max", first["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("reasoning.encrypted_content", first["include"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(listOf("web_search"), first["tools"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content })

        val call = ToolCall("call_x", "get_weather", """{"city":"Paris"}""")
        val history = listOf(
            ChatMessage.system("be brief"),
            ChatMessage(Role.User, listOf(ContentPart.Text("weather?"), ContentPart.Image("image/png", "QUJD"))),
            ChatMessage.assistant("Checking", listOf(call)),
            ChatMessage.toolResult(call, listOf(ContentPart.Text("sunny"), ContentPart.Image("image/jpeg", "WFla"))),
        )
        engine.collect(
            LlmRequest("gpt-6.1-sol", history, tools = listOf(ToolSpec("get_weather", "Weather", weatherSchema)), maxOutputTokens = 300),
        )

        val body = server.takeRequest().json()
        assertEquals("be brief", body["instructions"]!!.jsonPrimitive.content)
        assertEquals("300", body["max_output_tokens"]!!.jsonPrimitive.content)
        val input = body["input"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("message", "reasoning", "message", "function_call", "function_call_output", "message"),
            input.map { it["type"]!!.jsonPrimitive.content },
        )
        assertEquals("input_image", input[0]["content"]!!.jsonArray[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("ENC", input[1]["encrypted_content"]!!.jsonPrimitive.content)
        assertNull(input[1]["id"])
        assertEquals("output_text", input[2]["content"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("call_x", input[3]["call_id"]!!.jsonPrimitive.content)
        assertEquals("sunny", input[4]["output"]!!.jsonPrimitive.content)
        val function = body["tools"]!!.jsonArray[0].jsonObject
        assertEquals("get_weather", function["name"]!!.jsonPrimitive.content)
        assertEquals(weatherSchema, function["parameters"])
    }

    @Test
    fun citationsBecomeMarkdownSources() {
        server.enqueueSse(
            events(
                "response.output_text.delta" to """{"type":"response.output_text.delta","delta":"News"}""",
                "response.output_text.annotation.added" to """{"type":"response.output_text.annotation.added","annotation":{"type":"url_citation","url":"https://ex.com/a","title":"A"}}""",
                "response.completed" to """{"type":"response.completed","response":{}}""",
            ),
        )

        val text = engine().collect(LlmRequest("gpt-6.1-sol", listOf(ChatMessage.user("news")))).text()

        assertTrue(text.startsWith("News"))
        assertTrue("[A](https://ex.com/a)" in text)
    }

    @Test
    fun accountBackendOmitsOutputLimitAndSendsAccountHeaders() {
        server.enqueueSse(events("response.completed" to """{"type":"response.completed","response":{}}"""))
        val tokens = OAuthTokens(accessToken = "tok", accountId = "acct_1")

        engine(EngineOptions(authHeaders = ChatGptAuth.accountHeaders(tokens)), family = Family.CHATGPT)
            .collect(LlmRequest("gpt-6.1-sol", listOf(ChatMessage.user("hi"))))

        val recorded = server.takeRequest()
        val body = recorded.json()
        assertNull(body["max_output_tokens"])
        assertEquals("", body["instructions"]!!.jsonPrimitive.content)
        assertEquals("acct_1", recorded.headers["ChatGPT-Account-ID"])
    }

    @Test
    fun failedResponseAndIncompleteMapCorrectly() {
        server.enqueueSse(events("response.failed" to """{"type":"response.failed","response":{"error":{"code":"server_error","message":"boom"}}}"""))
        val failure = engine().failure(LlmRequest("gpt-6.1-sol", listOf(ChatMessage.user("hi"))))
        assertTrue(failure.retryable)
        assertTrue("boom" in failure.message!!)

        server.enqueueSse(
            events("response.incomplete" to """{"type":"response.incomplete","response":{"incomplete_details":{"reason":"max_output_tokens"}}}"""),
        )
        val events = engine().collect(LlmRequest("gpt-6.1-sol", listOf(ChatMessage.user("hi"))))
        assertEquals(listOf(LlmEvent.Finished(FinishReason.Length)), events.finished())

        server.enqueueSse(events("response.output_text.delta" to """{"type":"response.output_text.delta","delta":"x"}"""))
        assertTrue(engine().failure(LlmRequest("gpt-6.1-sol", listOf(ChatMessage.user("hi")))).retryable)
        assertFalse(server.requestCount == 0)
    }
}
