package atom.llm.cloud

import atom.core.llm.ChatMessage
import atom.core.llm.LlmRequest
import atom.core.llm.ReasoningEffort
import atom.core.llm.ToolCall
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockWebServer

class CustomizationTest {
    private lateinit var server: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() = server.close()

    private val done = sseBody("""{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""", "[DONE]")

    private fun chat(providerId: String, options: EngineOptions = EngineOptions(), model: String = "m") =
        CloudEngines.create(Providers.byId(providerId)!!.copy(wire = Wire.OpenAiChat), "key-abcdef", model, server.url("/v1").toString(), options)

    @Test
    fun userHeadersApplyButAuthHeadersAreProtected() {
        server.enqueueSse(done)
        val options = EngineOptions(
            headers = listOf(
                HeaderEntry("User-Agent", "AtomTest/1"),
                HeaderEntry("Authorization", "Bearer stolen"),
                HeaderEntry("x-api-key", "nope"),
                HeaderEntry("X-Custom", "a"),
                HeaderEntry("x-custom", "b"),
                HeaderEntry("Bad Header", "c"),
            ),
        )

        chat("deepseek", options).collect(LlmRequest("m", listOf(ChatMessage.user("hi"))))

        val recorded = server.takeRequest()
        assertEquals("AtomTest/1", recorded.headers["User-Agent"])
        assertEquals("Bearer key-abcdef", recorded.headers["Authorization"])
        assertNull(recorded.headers["x-api-key"])
        assertEquals("b", recorded.headers["X-Custom"])
        assertTrue(RequestCustomizer.isProtectedHeader(" AUTHORIZATION "))
    }

    @Test
    fun extraBodyMergesDeeplyButCannotReplaceTheConversation() {
        val base = Json.parseToJsonElement("""{"model":"m","messages":[1],"stream":true,"opts":{"a":1,"b":{"c":2}}}""").jsonObject
        val extra = Json.parseToJsonElement("""{"model":"evil","messages":[],"stream":false,"opts":{"b":{"d":3}},"top_k":5}""").jsonObject

        val merged = RequestCustomizer.mergeBody(base, extra)

        assertEquals("m", merged["model"]!!.jsonPrimitive.content)
        assertEquals(1, merged["messages"]!!.jsonArray.size)
        assertEquals("true", merged["stream"]!!.jsonPrimitive.content)
        assertEquals("""{"a":1,"b":{"c":2,"d":3}}""", merged["opts"].toString())
        assertEquals("5", merged["top_k"]!!.jsonPrimitive.content)
        assertTrue(RequestCustomizer.parseExtraBody("[1]").isFailure)
        assertNull(RequestCustomizer.parseExtraBody("  ").getOrThrow())
    }

    @Test
    fun providerPromptGoesFirstAndReasoningIsSnapped() {
        server.enqueueSse(done)
        val options = EngineOptions(
            systemPrompt = "You are Atom.",
            extraBody = Json.parseToJsonElement("""{"temperature":0.1}""").jsonObject,
            reasoning = ReasoningInfo.of(ReasoningEffort.Low, ReasoningEffort.High),
        )

        chat("deepseek", options).collect(
            LlmRequest("m", listOf(ChatMessage.system("agent rules"), ChatMessage.user("hi")), reasoning = ReasoningEffort.Medium),
        )

        val body = server.takeRequest().json()
        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("You are Atom.", "agent rules"), messages.take(2).map { it["content"]!!.jsonPrimitive.content })
        assertEquals("high", body["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("enabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("0.1", body["temperature"]!!.jsonPrimitive.content)
    }

    @Test
    fun reasoningParametersFollowTheVendor() {
        repeat(3) { server.enqueueSse(done) }
        chat("qwen").collect(LlmRequest("m", listOf(ChatMessage.user("hi")), reasoning = ReasoningEffort.Off))
        chat("openrouter").collect(LlmRequest("m", listOf(ChatMessage.user("hi")), reasoning = ReasoningEffort.Off))
        chat("deepseek").collect(LlmRequest("m", listOf(ChatMessage.user("hi")), reasoning = ReasoningEffort.Off))

        val qwen = server.takeRequest().json()
        assertEquals("false", qwen["enable_thinking"]!!.jsonPrimitive.content)
        assertNull(qwen["thinking_budget"])
        val openRouter = server.takeRequest().json()
        assertEquals("false", openRouter["reasoning"]!!.jsonObject["enabled"]!!.jsonPrimitive.content)
        val deepseek = server.takeRequest().json()
        assertEquals("disabled", deepseek["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertNull(deepseek["reasoning_effort"])
    }

    @Test
    fun interleavedReasoningIsReplayedWithToolCalls() {
        server.enqueueSse(
            sseBody(
                """{"choices":[{"index":0,"delta":{"reasoning_content":"need weather"}}]}""",
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_ds","type":"function","function":{"name":"w","arguments":"{}"}}]}}]}""",
                """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
                "[DONE]",
            ),
        )
        server.enqueueSse(done)
        val engine = chat("deepseek")
        engine.collect(LlmRequest("m", listOf(ChatMessage.user("hi"))))
        server.takeRequest()

        val call = ToolCall("call_ds", "w", "{}")
        engine.collect(LlmRequest("m", listOf(ChatMessage.user("hi"), ChatMessage.assistant("", listOf(call)), ChatMessage.toolResult(call, emptyList()))))

        val assistant = server.takeRequest().json()["messages"]!!.jsonArray[1].jsonObject
        assertEquals("need weather", assistant["reasoning_content"]!!.jsonPrimitive.content)
    }

    @Test
    fun anthropicAcceptsUnversionedBaseUrls() {
        assertEquals("https://api.anthropic.com/v1/messages", AnthropicEngine.messagesEndpoint("https://api.anthropic.com/"))
        assertEquals("https://proxy.example/v1/messages", AnthropicEngine.messagesEndpoint("https://proxy.example/v1"))
        assertEquals("https://api.minimaxi.com/anthropic/v1/messages", AnthropicEngine.messagesEndpoint("https://api.minimaxi.com/anthropic"))
    }

    @Test
    fun vertexUrlsFollowLocation() {
        assertEquals(
            "https://aiplatform.googleapis.com/v1/publishers/google/models/gemini-4-neon",
            GeminiEngine.vertexModelUrl("", null, null, "gemini-4-neon"),
        )
        assertEquals(
            "https://aiplatform.googleapis.com/v1/projects/p1/locations/global/publishers/google/models/g",
            GeminiEngine.vertexModelUrl("", "p1", "global", "g"),
        )
        assertEquals(
            "https://aiplatform.eu.rep.googleapis.com/v1/projects/p1/locations/eu/publishers/google/models/g",
            GeminiEngine.vertexModelUrl("", "p1", "eu", "g"),
        )
        assertEquals(
            "https://us-central1-aiplatform.googleapis.com/v1/projects/p1/locations/us-central1/publishers/google/models/g",
            GeminiEngine.vertexModelUrl("https://aiplatform.googleapis.com", "p1", "us-central1", "g"),
        )
        assertEquals(
            "https://proxy.example/v1/projects/p1/locations/us/publishers/google/models/g",
            GeminiEngine.vertexModelUrl("https://proxy.example/", "p1", "us", "g"),
        )
    }

    @Test
    fun geminiGroundingAddsSearchToolAndSources() {
        server.enqueueSse(
            sseBody(
                """{"candidates":[{"content":{"parts":[{"text":"Answer"}]},"finishReason":"STOP","groundingMetadata":{"groundingChunks":[{"web":{"uri":"https://g.co/x","title":"X"}}]}}]}""",
            ),
        )
        val engine = CloudEngines.create(
            Providers.byId("gemini")!!,
            "AIza-key-123",
            "gemini-4-neon",
            server.url("/v1beta").toString(),
            EngineOptions(googleSearch = true),
        )

        val text = engine.collect(LlmRequest("gemini-4-neon", listOf(ChatMessage.user("q")), reasoning = ReasoningEffort.XHigh)).text()

        assertTrue("[X](https://g.co/x)" in text)
        val body = server.takeRequest().json()
        assertTrue(body["tools"]!!.jsonArray.any { it.jsonObject.containsKey("googleSearch") })
        assertEquals("xhigh", body["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content)
    }

    @Test
    fun remoteListingCarriesCapabilityMetadata() {
        val page = Json.parseToJsonElement(
            """{"data":[{"id":"a/b","name":"B","context_length":131072,"supported_parameters":["tools","reasoning"],
               "architecture":{"input_modalities":["text","image"]}},{"id":"plain"}]}""",
        ).jsonObject
        val models = CloudEngines.openAiModels(page)
        assertEquals(131_072, models[0].capabilities!!.contextWindow)
        assertTrue(models[0].capabilities!!.reasoning!!.supported)
        assertEquals(true, models[0].capabilities!!.vision)
        assertNull(models[1].capabilities)
    }
}
