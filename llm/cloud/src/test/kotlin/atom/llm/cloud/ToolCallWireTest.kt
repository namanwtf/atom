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

    private fun sent(
        providerId: String,
        history: List<ChatMessage>,
        effort: ReasoningEffort? = null,
        model: String = "m",
        options: EngineOptions = EngineOptions(),
    ): List<JsonObject> {
        server.enqueueSse(done)
        CloudEngines.create(Providers.byId(providerId)!!.copy(wire = Wire.OpenAiChat), "key-abcdef", model, server.url("/v1").toString(), options)
            .collect(LlmRequest(model, history, reasoning = effort))
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

    /** Models that think on every request (forced), per thinking vendor. */
    private val thinkingModels = listOf("deepseek" to "deepseek-reasoner", "moonshot" to "kimi-k2-thinking")

    @Test
    fun anEarlierToolCallStoredWithoutReasoningGoesAsANote() {
        for ((provider, model) in thinkingModels + listOf("deepseek" to "m")) {
            // An unknown model counts as thinking only when an effort asks for it.
            val effort = if (model == "m") ReasoningEffort.High else null
            val messages = sent(provider, restarted(null).first, effort, model)
            // No tool-call turn without its reasoning_content: the vendor would reject the whole request.
            assertTrue(callIds(messages).isEmpty() && resultIds(messages).isEmpty(), "$provider/$model: $messages")
            val first = messages.first { it.str("role") == "user" }.str("content")!!
            assertTrue(first.startsWith("turn the volume down"), first)
            assertTrue(first.contains("[Earlier tool call: set_volume({\"level\":20})]\nVolume 20%"), first)
            assertEquals(listOf("user", "assistant", "user"), roles(messages), provider)
            assertTrue(messages.none { "_atom_note" in it }, "the internal mark never goes out")
        }
    }

    @Test
    fun nonThinkingModelsKeepEarlierToolCallsAsToolCalls() {
        // As in 4.0.5: deepseek-chat, Kimi K2 Instruct and Qwen Instruct do not think, so nothing is rewritten and no
        // reasoning goes out, whatever the history holds.
        for ((provider, model) in listOf(
            "deepseek" to "deepseek-chat", "moonshot" to "kimi-k2-instruct", "qwen" to "qwen3-235b-a22b-instruct-2507",
            "siliconflow" to "Qwen/Qwen3-235B-A22B-Instruct-2507", "deepseek" to "m",
        )) {
            for (reasoning in listOf(null, "stored thoughts")) {
                val (history, id) = restarted(reasoning)
                val messages = sent(provider, history, model = model)
                assertEquals(listOf(id), callIds(messages), "$provider/$model")
                assertEquals(listOf(id), resultIds(messages), "$provider/$model")
                assertEquals(listOf("user", "assistant", "tool", "assistant", "user"), roles(messages), "$provider/$model")
                if (model != "m") assertTrue(messages.none { "reasoning_content" in it }, "$provider/$model: $messages")
                assertTrue(messages.none { it.str("content")?.contains("[Earlier tool call") == true }, "$provider/$model")
            }
        }
    }

    @Test
    fun aModelNothingKnowsGetsItsReasoningBackAsTheAppConfiguresIt() {
        // The app's options come from the saved model (ModelConfig): in Auto mode with nothing detected that is
        // "unknown", so a brand-new thinking model still gets reasoning_content, as in 4.0.5.
        for (provider in listOf("deepseek", "moonshot")) {
            val model = ModelConfig("brand-new-model-2027")
            val (history, id) = restarted("Thinking about the volume.")
            val messages = sent(provider, history, model = model.id, options = EngineOptions(reasoning = model.engineReasoning))
            assertEquals("Thinking about the volume.", messages.single { it["tool_calls"] != null }.str("reasoning_content"), provider)
            assertEquals(listOf(id), callIds(messages), provider)
        }
        // "Off" chosen by hand: none.
        val off = ModelConfig("brand-new-model-2027", reasoningMode = ReasoningMode.Off)
        val messages = sent("deepseek", restarted("x").first, model = off.id, options = EngineOptions(reasoning = off.engineReasoning))
        assertTrue(messages.none { "reasoning_content" in it })
    }

    @Test
    fun deepseekReasonerStillGetsItsReasoningBack() {
        val (history, id) = restarted("The user wants it quieter; 20% is fine.")
        val messages = sent("deepseek", history, model = "deepseek-reasoner")
        val turn = messages.single { it["tool_calls"] != null }
        assertEquals("The user wants it quieter; 20% is fine.", turn.str("reasoning_content"))
        assertEquals(listOf(id), callIds(messages))
        assertEquals(listOf("user", "assistant", "tool", "assistant", "user"), roles(messages))
    }

    @Test
    fun nudgesAndSteeringDoNotStartATurn() {
        // One turn: the user's request, a call made without reasoning, then Atom's nudge and a steering message the
        // user sent mid-run. Both are user-role messages inside the turn: the call stays a tool call.
        val call = ToolCall(ToolCallIds.fresh(), "set_volume", """{"level":20}""")
        val history = listOf(
            ChatMessage.user("turn the volume down"),
            ChatMessage.assistant("", listOf(call)),
            ChatMessage.toolResult(call, listOf(ContentPart.Text("Volume 20%"))),
            ChatMessage.user("You called the same tool twice; check the result first.").copy(midTurn = true),
            ChatMessage.user("a bit more, please").copy(midTurn = true),
        )
        for ((provider, model) in thinkingModels) {
            val messages = sent(provider, history, model = model)
            assertEquals(listOf(call.id), callIds(messages), "$provider/$model: $messages")
            assertEquals(listOf(call.id), resultIds(messages), "$provider/$model")
        }
        // The same messages as real user turns make the call an earlier one.
        val real = history.map { it.copy(midTurn = false) }
        assertTrue(callIds(sent("deepseek", real, model = "deepseek-reasoner")).isEmpty())
    }

    @Test
    fun theEngineSaysWhenItSendsReasoningBack() {
        fun replays(provider: String, model: String) =
            CloudEngines.create(Providers.byId(provider)!!.copy(wire = Wire.OpenAiChat), "key-abcdef", model, "https://x.example/v1").capabilities.replaysReasoning
        assertTrue(replays("deepseek", "deepseek-reasoner"))
        assertTrue(replays("moonshot", "kimi-k2-thinking"))
        assertTrue(replays("deepseek", "some-new-model"), "unknown: counted, to be safe")
        assertTrue(!replays("deepseek", "deepseek-chat"))
        assertTrue(!replays("openai", "gpt-4o"))
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
