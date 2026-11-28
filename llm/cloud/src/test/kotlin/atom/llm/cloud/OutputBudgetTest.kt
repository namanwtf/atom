package atom.llm.cloud

import atom.core.llm.ChatMessage
import atom.core.llm.ContentPart
import atom.core.llm.LlmRequest
import atom.core.llm.ReasoningEffort
import atom.core.llm.ToolCall
import atom.core.llm.ToolCallIds
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockWebServer

/**
 * A thinking request needs room for its reasoning as well as the answer: with the plain 2048-token answer budget,
 * DeepSeek Flash with Deep Think on reasoned until the limit and never started the answer.
 */
class OutputBudgetTest {
    private lateinit var server: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() = server.close()

    /** The budget for a built-in model, from the catalog entry the app routes with. */
    private fun preset(providerId: String, modelId: String, effort: ReasoningEffort?): Int {
        val preset = PresetCatalog.byId(providerId)!!
        val config = preset.toConfig().model(modelId)!!
        val provider = preset.toProvider()
        return OutputBudget.Target(provider.wire, provider.family, modelId, config.engineReasoning, config.maxOutput, config.effectiveContextWindow)
            .tokens(effort)
    }

    @Test
    fun deepSeekThinkingGetsRoomForReasoningAndAnswer() {
        // Deep Think on with the model's default effort (null), as the composer sends it.
        assertEquals(32_768, preset("deepseek", "deepseek-flash", null))
        assertEquals(32_768, preset("deepseek", "deepseek-flash", ReasoningEffort.High))
        assertEquals(65_536, preset("deepseek", "deepseek-flash", ReasoningEffort.Max))
        // Deep Think off: the plain answer budget, as before.
        assertEquals(OutputBudget.ANSWER, preset("deepseek", "deepseek-flash", ReasoningEffort.Off))
        assertEquals(OutputBudget.ANSWER, preset("deepseek", "deepseek-v4-pro", ReasoningEffort.Off))
    }

    @Test
    fun otherThinkingVendorsStayWithinWhatTheirApisAccept() {
        assertEquals(32_768, preset("moonshot", "kimi-k2.7-code", null), "Kimi asks for at least 16,000")
        assertEquals(32_768, preset("moonshot", "kimi-k2.6", null))
        assertEquals(OutputBudget.ANSWER, preset("moonshot", "kimi-k2.6", ReasoningEffort.Off))
        assertEquals(16_384, preset("qwen", "qwen3.8-max", ReasoningEffort.High))
        assertEquals(OutputBudget.ANSWER, preset("qwen", "qwen3.8-max", ReasoningEffort.Off))
        assertEquals(16_384, preset("mimo", "mimo-v2.5", null))
        assertEquals(65_536, preset("openai", "gpt-6.1-sol", ReasoningEffort.Medium))
        assertEquals(65_536, preset("gemini", "gemini-4-argon", null))
        assertEquals(65_536, preset("anthropic", "claude-opus-5-5", ReasoningEffort.High))
    }

    @Test
    fun legacyClaudeKeepsItsAnswerRoomBecauseItsThinkingBudgetGoesOnTop() {
        val target = OutputBudget.Target(Wire.Anthropic, Family.ANTHROPIC, "claude-sonnet-4-5", ReasoningInfo.of(ReasoningEffort.Off, ReasoningEffort.High))
        assertEquals(OutputBudget.ANSWER, target.tokens(ReasoningEffort.High))
    }

    @Test
    fun modelsThatDoNotThinkKeepThePlainBudget() {
        val chat = OutputBudget.Target(Wire.OpenAiChat, Family.DEEPSEEK, "deepseek-chat", CapabilityDetector.byFamily("deepseek-chat").reasoning)
        assertEquals(OutputBudget.ANSWER, chat.tokens(null))
        assertEquals(OutputBudget.ANSWER, OutputBudget.Target(Wire.OpenAiChat, Family.CUSTOM, "llama3", null).tokens(null))
        assertEquals(OutputBudget.ANSWER, OutputBudget.Target(Wire.OpenAiChat, Family.OPENAI, "gpt-4o", ReasoningInfo.None).tokens(ReasoningEffort.High))
    }

    @Test
    fun anUnknownModelOnAThinkingVendorIsTreatedAsThinking() {
        assertEquals(32_768, OutputBudget.Target(Wire.OpenAiChat, Family.DEEPSEEK, "deepseek-2027", null).tokens(null))
        assertEquals(16_384, OutputBudget.Target(Wire.OpenAiChat, Family.CUSTOM, "my-model", null).tokens(ReasoningEffort.High))
    }

    @Test
    fun theModelsOwnLimitAndItsWindowCapTheBudget() {
        val info = ReasoningInfo.forced()
        assertEquals(8_000, OutputBudget.Target(Wire.OpenAiChat, Family.OPENROUTER, "x/r1", info, maxOutput = 8_000).tokens(null))
        // A small local window: a third of it, never below the answer budget.
        assertEquals(10_922, OutputBudget.Target(Wire.OpenAiChat, Family.CUSTOM, "qwq", info, contextWindow = 32_768).tokens(null))
        assertEquals(OutputBudget.ANSWER, OutputBudget.Target(Wire.OpenAiChat, Family.CUSTOM, "qwq", info, contextWindow = 4_096).tokens(null))
        // A model whose own limit is below the answer budget gets its own limit.
        assertEquals(1_024, OutputBudget.Target(Wire.OpenAiChat, Family.CUSTOM, "tiny", ReasoningInfo.None, maxOutput = 1_024).tokens(null))
    }

    @Test
    fun aModelThatReportsALargerLimitGetsIt() {
        val info = ReasoningInfo.forced()
        // The provider's own list says 128K: a thinking request uses all of it, above the vendor figure.
        assertEquals(128_000, OutputBudget.Target(Wire.Anthropic, Family.ANTHROPIC, "claude-opus-5-5", info, maxOutput = 128_000).tokens(null))
        assertEquals(100_000, OutputBudget.Target(Wire.OpenAiResponses, Family.OPENAI, "o-model", info, maxOutput = 100_000).tokens(null))
        // Never more than the ceiling, and never more than a third of the window.
        assertEquals(OutputBudget.CEILING, OutputBudget.Target(Wire.Gemini, Family.GEMINI, "g", info, maxOutput = 500_000).tokens(null))
        assertEquals(66_666, OutputBudget.Target(Wire.Anthropic, Family.ANTHROPIC, "claude-opus-5-5", info, maxOutput = 128_000, contextWindow = 200_000).tokens(null))
        // Without thinking the reported limit does not raise the plain answer budget.
        assertEquals(OutputBudget.ANSWER, OutputBudget.Target(Wire.OpenAiChat, Family.OPENAI, "gpt-4o", ReasoningInfo.None, maxOutput = 16_384).tokens(null))
        // Legacy Claude keeps its answer room even when its limit is reported (the thinking budget goes on top).
        val legacy = OutputBudget.Target(Wire.Anthropic, Family.ANTHROPIC, "claude-sonnet-4-5", ReasoningInfo.of(ReasoningEffort.Off, ReasoningEffort.High), maxOutput = 64_000)
        assertEquals(OutputBudget.ANSWER, legacy.tokens(ReasoningEffort.High))
    }

    @Test
    fun theCatalogsOutputLimitReachesTheModel() {
        val json = """{"vendor":{"id":"vendor","name":"Vendor","api":"https://api.vendor.example/v1","models":{
            "thinker":{"id":"thinker","reasoning":true,"limit":{"context":131072,"output":12000}}}}}"""
        val model = ModelsDev.parse(json).single().models.single()
        assertEquals(12_000, model.capabilities.maxOutput)
        val config = ModelConfig(model.id, detected = model.capabilities)
        assertEquals(12_000, config.maxOutput)
        assertNull(ModelConfig("deepseek-flash").maxOutput)
    }

    @Test
    fun deepSeekThinkingRequestCarriesTheBudgetAndKeepsReplayingReasoning() {
        // The 4.0.6 rule still holds with the larger budget: a tool-call turn's stored reasoning goes back with it.
        val call = ToolCall(ToolCallIds.fresh(), "set_volume", """{"level":20}""")
        val history = listOf(
            ChatMessage.user("turn the volume down"),
            ChatMessage.assistant("", listOf(call)).copy(reasoning = "Quieter; 20% is fine."),
            ChatMessage.toolResult(call, listOf(ContentPart.Text("Volume 20%"))),
        )
        val budget = preset("deepseek", "deepseek-flash", null)
        server.enqueueSse(sseBody("""{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""", "[DONE]"))
        CloudEngines.create(Providers.byId("deepseek")!!, "key-abcdef", "deepseek-flash", server.url("/v1").toString())
            .collect(LlmRequest("deepseek-flash", history, maxOutputTokens = budget))
        val body = server.takeRequest().json()
        assertEquals("32768", body["max_tokens"]!!.jsonPrimitive.content)
        val turn = body["messages"]!!.jsonArray.map { it.jsonObject }.single { it["tool_calls"] != null }
        assertEquals("Quieter; 20% is fine.", turn["reasoning_content"]!!.jsonPrimitive.content)
    }

    @Test
    fun aToolCallCutOffMidArgumentsNeverGoesBackAsInvalidJson() {
        // Stored by a build that kept the cut-off call: DeepSeek refused every later request with HTTP 400.
        val cut = ToolCall("call_cut", "write_file", """{"path":"notes.txt","text":"The first par""")
        val good = ToolCall("call_good", "set_volume", """{"level":20}""")
        val history = listOf(
            ChatMessage.user("save my notes"),
            ChatMessage.assistant("", listOf(good, cut)).copy(reasoning = "Two things."),
            ChatMessage.toolResult(good, listOf(ContentPart.Text("Volume 20%"))),
            ChatMessage.toolResult(cut, listOf(ContentPart.Text("Arguments were not a valid JSON object"))),
        )
        for (wire in listOf(Wire.OpenAiChat, Wire.OpenAiResponses)) {
            server.enqueueSse(sseBody("""{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""", "[DONE]"))
            CloudEngines.create(Providers.byId("deepseek")!!.copy(wire = wire), "key-abcdef", "deepseek-flash", server.url("/v1").toString())
                .runCatching { collect(LlmRequest("deepseek-flash", history)) }
            val body = server.takeRequest().json()
            val arguments = if (wire == Wire.OpenAiChat) {
                body["messages"]!!.jsonArray.flatMap { m -> m.jsonObject["tool_calls"]?.jsonArray.orEmpty() }
                    .map { it.jsonObject["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content }
            } else {
                body["input"]!!.jsonArray.map { it.jsonObject }.filter { it["type"]?.jsonPrimitive?.content == "function_call" }
                    .map { it["arguments"]!!.jsonPrimitive.content }
            }
            assertEquals(listOf("""{"level":20}""", "{}"), arguments, "$wire")
            assertTrue(arguments.all { Json.parseToJsonElement(it) is JsonObject }, "$wire")
        }
    }
}
