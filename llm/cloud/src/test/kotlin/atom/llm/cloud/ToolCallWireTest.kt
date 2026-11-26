package atom.llm.cloud

import atom.core.llm.ChatMessage
import atom.core.llm.ContentPart
import atom.core.llm.LlmRequest
import atom.core.llm.ReasoningEffort
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

    private fun sent(providerId: String, history: List<ChatMessage>, effort: ReasoningEffort? = null): List<JsonObject> {
        server.enqueueSse(done)
        CloudEngines.create(Providers.byId(providerId)!!.copy(wire = Wire.OpenAiChat), "key-abcdef", "m", server.url("/v1").toString())
            .collect(LlmRequest("m", history, reasoning = effort))
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
            // The note joins the user's turn, so roles alternate: never two user turns in a row.
            val roles = messages.map { it.str("role") }
            assertTrue(roles.zipWithNext().none { (a, b) -> a == "user" && b == "user" }, "$provider: $roles")
            val first = messages.first { it.str("role") == "user" }.str("content")!!
            assertTrue(first.startsWith("turn the volume down") && first.contains("[Tools loaded for this task]"), first)
            assertTrue(messages.none { "_atom_note" in it }, "the internal mark never goes out")
        }
    }

    @Test
    fun aNoteAfterAnImageTurnJoinsItsParts() {
        val preload = ToolCall(ToolCallIds.fresh(), ToolLoadout.LOADER, "{}")
        val history = listOf(
            ChatMessage(atom.core.llm.Role.User, listOf(ContentPart.Text("what is this?"), ContentPart.Image("image/png", "AAAA"))),
            ChatMessage.assistant("", listOf(preload)),
            ChatMessage.toolResult(preload, listOf(ContentPart.Text("Loaded for this task:\n- vision"))),
        )
        val messages = sent("deepseek", history)
        assertEquals(listOf("user"), messages.map { it.str("role") })
        val parts = messages.single()["content"]!!.jsonArray.map { it.jsonObject }
        assertTrue(parts.last().str("text")!!.contains("[Tools loaded for this task]"))
    }

    /**
     * An earlier task (before the latest user message) as the app reads it back after a restart: the reasoning cache
     * is empty, so only what was stored with the turn ([ChatMessage.reasoning]) is there. A fresh id per test keeps
     * the in-memory cache out of it.
     */
    private fun restarted(reasoning: String?): Pair<List<ChatMessage>, String> {
        val call = ToolCall(ToolCallIds.fresh(), "set_volume", """{"level":20}""")
        return listOf(
            ChatMessage.user("turn the volume down"),
            ChatMessage.assistant("", listOf(call)).copy(reasoning = reasoning),
            ChatMessage.toolResult(call, listOf(ContentPart.Text("Volume 20%"))),
            ChatMessage.assistant("Done, the volume is at 20%."),
            ChatMessage.user("and now a bit louder"),
        ) to call.id
    }

    private fun roles(messages: List<JsonObject>) = messages.map { it.str("role") }

    @Test
    fun storedReasoningGoesBackWithItsToolCallTurnAfterARestart() {
        for (provider in listOf("deepseek", "moonshot")) {
            val (history, id) = restarted("The user wants it quieter; 20% is fine.")
            val messages = sent(provider, history)
            val turn = messages.single { it["tool_calls"] != null }
            assertEquals("The user wants it quieter; 20% is fine.", turn.str("reasoning_content"), provider)
            assertEquals(listOf(id), callIds(messages), provider)
            assertEquals(listOf(id), resultIds(messages), provider)
            assertEquals(listOf("user", "assistant", "tool", "assistant", "user"), roles(messages), provider)
        }
    }

    @Test
    fun otherProvidersNeverGetTheReasoning() {
        for (provider in listOf("openai", "groq", "mistral", "openrouter")) {
            val messages = sent(provider, restarted("private thoughts").first)
            assertTrue(messages.none { "reasoning_content" in it }, provider)
            assertEquals(1, callIds(messages).size, provider)
        }
    }

    @Test
    fun anEarlierToolCallStoredWithoutReasoningGoesAsANote() {
        for (provider in listOf("deepseek", "moonshot")) {
            val messages = sent(provider, restarted(null).first)
            // No tool-call turn without its reasoning_content: the vendor would reject the whole request.
            assertTrue(callIds(messages).isEmpty() && resultIds(messages).isEmpty(), "$provider: $messages")
            val first = messages.first { it.str("role") == "user" }.str("content")!!
            assertTrue(first.startsWith("turn the volume down"), first)
            assertTrue(first.contains("[Earlier tool call: set_volume({\"level\":20})]\nVolume 20%"), first)
            assertEquals(listOf("user", "assistant", "user"), roles(messages), provider)
            assertTrue(messages.none { "_atom_note" in it }, "the internal mark never goes out")
        }
    }

    @Test
    fun withThinkingOffAnEarlierToolCallKeepsItsForm() {
        val (history, id) = restarted(null)
        val messages = sent("deepseek", history, ReasoningEffort.Off)
        assertEquals(listOf(id), callIds(messages))
        assertTrue(messages.none { "reasoning_content" in it })
    }

    @Test
    fun theCurrentTurnKeepsItsToolCallsWithoutReasoning() {
        // After the latest user message: a model that is not thinking made these calls; they stay tool-call turns.
        val call = ToolCall(ToolCallIds.fresh(), "set_volume", """{"level":20}""")
        val history = listOf(
            ChatMessage.user("turn the volume down"),
            ChatMessage.assistant("", listOf(call)),
            ChatMessage.toolResult(call, listOf(ContentPart.Text("Volume 20%"))),
        )
        assertEquals(listOf(call.id), callIds(sent("deepseek", history)))
    }
}
