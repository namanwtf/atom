package atom.llm.cloud

import atom.core.llm.ChatMessage
import atom.core.llm.ContentPart
import atom.core.llm.FinishReason
import atom.core.llm.LlmEvent
import atom.core.llm.LlmRequest
import atom.core.llm.ReasoningEffort
import atom.core.llm.ToolCall
import atom.core.llm.ToolSpec
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockWebServer

class GeminiEngineTest {
    private lateinit var server: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() = server.close()

    private fun engine() =
        CloudEngines.create(Providers.byId("gemini")!!, "AIza-secret-key", "gemini-3.8-flash", server.url("/v1beta").toString())

    private val request = LlmRequest(model = "gemini-3.8-flash", messages = listOf(ChatMessage.user("hi")))

    @Test
    fun streamsTextThoughtsAndUsage() {
        server.enqueueSse(
            sseBody(
                """{"candidates":[{"content":{"role":"model","parts":[{"text":"pondering","thought":true}]}}]}""",
                """{"candidates":[{"content":{"role":"model","parts":[{"text":"Hel"}]}}],"usageMetadata":{"promptTokenCount":9}}""",
                """{"candidates":[{"content":{"role":"model","parts":[{"text":"lo"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":9,"candidatesTokenCount":2,"thoughtsTokenCount":4}}""",
            ),
        )

        val events = engine().collect(request)

        assertEquals("Hello", events.text())
        assertEquals(listOf(LlmEvent.ReasoningDelta("pondering")), events.filterIsInstance<LlmEvent.ReasoningDelta>())
        assertEquals(LlmEvent.Usage(9, 6), events.filterIsInstance<LlmEvent.Usage>().single())
        assertEquals(LlmEvent.Finished(FinishReason.Stop), events.last())

        val recorded = server.takeRequest()
        assertEquals("/v1beta/models/gemini-3.8-flash:streamGenerateContent", recorded.url.encodedPath)
        assertEquals("sse", recorded.url.queryParameter("alt"))
        assertEquals("AIza-secret-key", recorded.headers["x-goog-api-key"])
        assertNull(recorded.url.queryParameter("key"))
    }

    @Test
    fun functionCallsBecomeToolCallsAndKeepTheirSignature() {
        server.enqueueSse(
            sseBody(
                """{"candidates":[{"content":{"role":"model","parts":[{"text":"Let me check."}]}}]}""",
                """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"get_weather","args":{"city":"Paris","days":2}},"thoughtSignature":"sig-1"}]}}]}""",
                """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"go_home"}}]},"finishReason":"STOP"}]}""",
            ),
        )

        val events = engine().collect(request)
        val calls = events.calls()

        assertEquals(listOf("get_weather", "go_home"), calls.map { it.name })
        assertEquals(Json.parseToJsonElement("""{"city":"Paris","days":2}"""), Json.parseToJsonElement(calls[0].argumentsJson))
        assertEquals("{}", calls[1].argumentsJson)
        assertTrue(calls[0].id.isNotBlank() && calls[0].id != calls[1].id)
        assertEquals(listOf(LlmEvent.Finished(FinishReason.ToolCalls)), events.finished())

        server.enqueueSse(sseBody("""{"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}]}"""))
        engine().collect(
            request.copy(
                messages = listOf(
                    ChatMessage.user("weather?"),
                    ChatMessage.assistant("Let me check.", calls),
                    ChatMessage.toolResult(calls[0], listOf(ContentPart.Text("sunny"))),
                    ChatMessage.toolResult(calls[1], listOf(ContentPart.Text("done"))),
                ),
            ),
        )
        server.takeRequest()
        val contents = server.takeRequest().json()["contents"]!!.jsonArray.map { it.jsonObject }
        val modelParts = contents[1]["parts"]!!.jsonArray.map { it.jsonObject }
        assertEquals("sig-1", modelParts[1]["thoughtSignature"]!!.jsonPrimitive.content)
        assertNull(modelParts[2]["thoughtSignature"])
        val responses = contents[2]["parts"]!!.jsonArray.map { it.jsonObject["functionResponse"]!!.jsonObject }
        assertEquals(listOf("get_weather", "go_home"), responses.map { it["name"]!!.jsonPrimitive.content })
        assertEquals("sunny", responses[0]["response"]!!.jsonObject["output"]!!.jsonPrimitive.content)
    }

    @Test
    fun errorStatusesMapToRetryability() {
        server.enqueueJson(400, """[{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}]""")
        server.enqueueJson(429, """{"error":{"code":429,"message":"Resource has been exhausted","status":"RESOURCE_EXHAUSTED"}}""")

        val invalid = engine().failure(request)
        val exhausted = engine().failure(request)

        assertFalse(invalid.retryable)
        assertTrue("API key not valid" in invalid.message!!)
        assertTrue(exhausted.retryable)
        assertEquals(429, exhausted.statusCode)
    }

    @Test
    fun serializesSystemToolsImagesAndThinking() {
        server.enqueueSse(sseBody("""{"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}]}"""))
        val call = ToolCall("call_unknown", "get_weather", """{"city":"Paris"}""")

        engine().collect(
            LlmRequest(
                model = "gemini-3.8-flash",
                messages = listOf(
                    ChatMessage.system("be brief"),
                    ChatMessage(atom.core.llm.Role.User, listOf(ContentPart.Text("look"), ContentPart.Image("image/png", "QUJD"))),
                    ChatMessage.assistant("", listOf(call)),
                    ChatMessage.toolResult(call, listOf(ContentPart.Text("sunny"))),
                ),
                tools = listOf(
                    ToolSpec("get_weather", "Weather lookup", weatherSchema),
                    ToolSpec("go_home", "Go to the home screen", JsonObject(mapOf("type" to Json.parseToJsonElement("\"object\""), "properties" to JsonObject(emptyMap())))),
                ),
                maxOutputTokens = 256,
                reasoning = ReasoningEffort.Low,
            ),
        )

        val body = server.takeRequest().json()
        assertEquals("be brief", body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        val config = body["generationConfig"]!!.jsonObject
        assertEquals("256", config["maxOutputTokens"]!!.jsonPrimitive.content)
        assertEquals("low", config["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content)

        val declarations = body["tools"]!!.jsonArray.single().jsonObject["functionDeclarations"]!!.jsonArray.map { it.jsonObject }
        val parameters = declarations[0]["parameters"]!!.jsonObject
        assertNull(parameters["additionalProperties"])
        assertNull(parameters["\$schema"])
        assertEquals(listOf("city"), parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        val unit = parameters["properties"]!!.jsonObject["unit"]!!.jsonObject
        assertEquals("string", unit["type"]!!.jsonPrimitive.content)
        assertEquals("true", unit["nullable"]!!.jsonPrimitive.content)
        assertNull(declarations[1]["parameters"])

        val contents = body["contents"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "model", "user"), contents.map { it["role"]!!.jsonPrimitive.content })
        assertEquals("image/png", contents[0]["parts"]!!.jsonArray[1].jsonObject["inlineData"]!!.jsonObject["mimeType"]!!.jsonPrimitive.content)
        val functionCall = contents[1]["parts"]!!.jsonArray.single().jsonObject
        assertEquals("Paris", functionCall["functionCall"]!!.jsonObject["args"]!!.jsonObject["city"]!!.jsonPrimitive.content)
        assertEquals("skip_thought_signature_validator", functionCall["thoughtSignature"]!!.jsonPrimitive.content)
    }

    @Test
    fun streamWithoutFinishReasonIsRetryable() {
        server.enqueueSse(sseBody("""{"candidates":[{"content":{"parts":[{"text":"partial"}]}}]}"""))

        assertTrue(engine().failure(request).retryable)
    }
}
