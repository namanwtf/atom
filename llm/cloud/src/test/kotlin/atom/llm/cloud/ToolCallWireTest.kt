package atom.llm.cloud

import atom.core.llm.ChatMessage
import atom.core.llm.ContentPart
import atom.core.llm.LlmRequest
import atom.core.llm.ToolCall
import atom.core.llm.ToolCallIds
import atom.core.tool.ToolLoadout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockWebServer

/** How tool-call turns (the run's own preload among them) go out per OpenAI-compatible provider. */
class ToolCallWireTest {
    private lateinit var server: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() = server.close()

    private val done = sseBody("""{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""", "[DONE]")

    private fun sent(providerId: String, history: List<ChatMessage>): List<JsonObject> {
        server.enqueueSse(done)
        CloudEngines.create(Providers.byId(providerId)!!.copy(wire = Wire.OpenAiChat), "key-abcdef", "m", server.url("/v1").toString())
            .collect(LlmRequest("m", history))
        return server.takeRequest().json()["messages"]!!.jsonArray.map { it.jsonObject }
    }

    /** A conversation with the preload exchange (here the id an old test build wrote) and a real call after it. */
    private fun history(preloadId: String = "preload_settings_browser"): List<ChatMessage> {
        val preload = ToolCall(preloadId, ToolLoadout.LOADER, """{"groups":"settings,browser"}""")
        val real = ToolCall("call_0123456789abcdefghijklmn", "set_volume", """{"level":20}""")
        return listOf(
            ChatMessage.user("turn the volume down"),
            ChatMessage.assistant("", listOf(preload)),
            ChatMessage.toolResult(preload, listOf(ContentPart.Text("Loaded for this task:\n- settings: set_volume"))),
            ChatMessage.assistant("", listOf(real)),
            ChatMessage.toolResult(real, listOf(ContentPart.Text("Volume 20%"))),
        )
    }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content

    private fun callIds(messages: List<JsonObject>) =
        messages.flatMap { m -> m["tool_calls"]?.jsonArray?.map { it.jsonObject.str("id")!! }.orEmpty() }

    private fun resultIds(messages: List<JsonObject>) = messages.filter { it.str("role") == "tool" }.map { it.str("tool_call_id")!! }

    @Test
    fun mistralGetsNineCharacterIdsThatStillPair() {
        val messages = sent("mistral", history())
        val calls = callIds(messages)
        assertEquals(2, calls.size)
        calls.forEach { assertTrue(Regex("[A-Za-z0-9]{9}").matches(it), it) }
        // Each result still points at its call, also for the old "preload_..." id of a stored conversation.
        assertEquals(calls, resultIds(messages))
    }

    @Test
    fun aFreshPreloadIdGoesOutUnchangedEverywhere() {
        val id = ToolCallIds.fresh()
        for (provider in listOf("mistral", "openai", "groq", "openrouter")) {
            assertEquals(id, callIds(sent(provider, history(id))).first(), provider)
        }
    }

    @Test
    fun otherProvidersKeepTheirOwnIds() {
        val messages = sent("openai", history())
        assertEquals(listOf("preload_settings_browser", "call_0123456789abcdefghijklmn"), callIds(messages))
        assertEquals(callIds(messages), resultIds(messages))
    }

    @Test
    fun thinkingVendorsGetThePreloadAsANote() {
        // DeepSeek and Kimi reject a tool-call turn without its reasoning_content; the preload never had any.
        for (provider in listOf("deepseek", "moonshot")) {
            val messages = sent(provider, history(ToolCallIds.fresh()))
            assertTrue(messages.none { m -> m["tool_calls"]?.jsonArray?.any { it.jsonObject["function"]!!.jsonObject.str("name") == ToolLoadout.LOADER } == true }, provider)
            assertTrue(messages.any { it.str("role") == "user" && it.str("content")!!.contains("settings: set_volume") }, provider)
            // The real call keeps its tool-call turn and its result.
            assertEquals(listOf("call_0123456789abcdefghijklmn"), callIds(messages), provider)
            assertEquals(listOf("call_0123456789abcdefghijklmn"), resultIds(messages), provider)
        }
    }
}
