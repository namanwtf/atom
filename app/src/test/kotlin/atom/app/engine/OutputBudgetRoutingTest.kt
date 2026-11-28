package atom.app.engine

import atom.core.llm.ReasoningEffort
import atom.llm.cloud.CloudEngines
import atom.llm.cloud.OutputBudget
import atom.llm.cloud.PresetCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What a run on a built-in cloud model gets: output room that fits its reasoning, and how it answers after a cut. */
class OutputBudgetRoutingTest {
    private fun routed(providerId: String, modelId: String): RoutedEngine {
        val preset = PresetCatalog.byId(providerId)!!
        val config = preset.toConfig()
        val model = config.model(modelId)!!
        val provider = config.toProvider()
        return RoutedEngine(
            engine = CloudEngines.create(provider, "key-abcdef", modelId),
            model = modelId,
            label = modelId,
            onDevice = false,
            notice = null,
            reasoning = model.reasoning,
            contextWindow = model.effectiveContextWindow,
            output = OutputBudget.Target(provider.wire, provider.family, modelId, model.engineReasoning, model.maxOutput, model.effectiveContextWindow),
        )
    }

    @Test
    fun deepThinkOnGivesTheRunRoomToAnswer() {
        val flash = routed("deepseek", "deepseek-flash")
        val thinking = flash.agentConfig("sys", emptyList(), slowApprovals = false, effort = null)
        assertEquals(32_768, thinking.maxOutputTokens)
        assertNull(thinking.reasoning)
        val off = flash.agentConfig("sys", emptyList(), slowApprovals = false, effort = ReasoningEffort.Off)
        assertEquals(RoutedEngine.CLOUD_MAX_OUTPUT, off.maxOutputTokens)
        assertEquals(ReasoningEffort.Off, off.reasoning)
    }

    @Test
    fun theRetryAfterACutThinksAsLittleAsTheModelAllows() {
        // Thinking can be switched off: off.
        assertEquals(ReasoningEffort.Off, routed("deepseek", "deepseek-flash").briefReasoning())
        // Always thinks, with levels: the lightest.
        assertEquals(ReasoningEffort.Low, routed("moonshot", "kimi-k3").briefReasoning())
        // Always thinks, no levels: the model default.
        assertNull(routed("moonshot", "kimi-k2.7-code").briefReasoning())
        assertEquals(ReasoningEffort.Off, routed("deepseek", "deepseek-flash").agentConfig("sys", emptyList(), false).briefReasoning)
    }
}
