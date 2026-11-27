package atom.app.engine

import atom.llm.cloud.CloudEngines
import atom.llm.cloud.ModelConfig
import atom.llm.cloud.ProviderConfig
import atom.llm.cloud.ReasoningInfo
import atom.llm.cloud.ReasoningMode
import atom.llm.cloud.Wire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The options the app really builds for a model (EngineRouter.engineOptions from the saved ProviderConfig), and what
 * the engine then does with a thinking vendor's reasoning. A model id nothing knows must not count as "cannot think":
 * DeepSeek and Kimi reject the second request of a tool loop without the first call's reasoning.
 */
class EngineOptionsReasoningTest {
    private fun replays(baseUrl: String, model: ModelConfig): Pair<ReasoningInfo?, Boolean> {
        val config = ProviderConfig(id = "p", displayName = "P", wire = Wire.OpenAiChat, baseUrl = baseUrl, models = listOf(model))
        val provider = config.toProvider()
        val options = EngineRouter.engineOptions(config, config.model(model.id)!!, provider, emptyList(), null, hostedSearch = false)
        val engine = CloudEngines.create(provider, "key-abcdef", model.id, provider.baseUrl, options)
        return options.reasoning to engine.capabilities.replaysReasoning
    }

    @Test
    fun anUnknownThinkingModelStillGetsItsReasoningBack() {
        for (base in listOf("https://api.deepseek.com/v1", "https://api.moonshot.ai/v1")) {
            val (info, replay) = replays(base, ModelConfig("brand-new-model-2027"))
            assertNull(info, "$base: unknown in Auto mode, not \"cannot think\"")
            assertTrue(replay, "$base: reasoning goes back, as in 4.0.5")
        }
    }

    @Test
    fun knownModelsFollowWhatIsKnown() {
        assertFalse(replays("https://api.deepseek.com/v1", ModelConfig("deepseek-chat")).second, "deepseek-chat does not think")
        assertTrue(replays("https://api.deepseek.com/v1", ModelConfig("deepseek-reasoner")).second)
        assertTrue(replays("https://api.moonshot.ai/v1", ModelConfig("kimi-k2-thinking")).second)
        // Switched off by hand: the user said it does not think.
        val off = replays("https://api.deepseek.com/v1", ModelConfig("brand-new-model-2027", reasoningMode = ReasoningMode.Off))
        assertEquals(ReasoningInfo.None, off.first)
        assertFalse(off.second)
        // Other vendors never send reasoning back.
        assertFalse(replays("https://api.openai.com/v1", ModelConfig("brand-new-model-2027")).second)
    }
}
