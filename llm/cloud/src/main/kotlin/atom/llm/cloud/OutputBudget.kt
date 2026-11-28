package atom.llm.cloud

import atom.core.llm.ReasoningEffort

/**
 * How many output tokens (max_tokens and its wire equivalents) a cloud request may use.
 *
 * On every wire ATOM speaks, the model's reasoning counts against that limit (the only exception is the legacy
 * Anthropic thinking budget, which [AnthropicEngine] adds on top). A request that thinks therefore needs room for the
 * reasoning and the answer: with the plain answer budget a thinking model can spend all of it reasoning and never
 * start the answer. Budgets are a ceiling, not a reservation: billing follows the tokens actually generated.
 *
 * Thinking budgets per vendor stay inside what each API accepts, since a value above a model's maximum is refused
 * with HTTP 400:
 * - DeepSeek: thinking mode defaults to 64K output tokens (128K at max effort), so 32K (64K at max effort) is accepted.
 * - Kimi (Moonshot): the thinking guides ask for max_tokens of at least 16,000 and use 32K in their examples.
 * - Qwen (Alibaba Bailian) and SiliconFlow: 16K, within the smallest thinking-mode output limit of their models.
 * - OpenAI reasoning models, Gemini 2.5 and later, adaptive-thinking Claude (4.6 and later): 64K, within the output
 *   limit of every model of those generations (Gemini 2.5 and later take 65,536; adaptive Claude takes 64K or more;
 *   OpenAI's reasoning models take 100K or more).
 * - Anything else: 16K.
 * When the provider's own model list reports a model's output limit, a thinking request uses that limit (up to
 * [CEILING]) instead of the vendor figure, so a model that takes more gets more. The budget is also capped at a third
 * of a known context window: the prompt may use the window minus the output room, and some servers refuse a request
 * whose prompt plus output limit exceeds the window, so the prompt keeps at least two thirds of it.
 */
object OutputBudget {
    /** Answer room when the model does not think. */
    const val ANSWER = 2_048

    /** Room for reasoning plus the answer on a vendor without a known larger limit. */
    const val THINKING = 16_384

    /** Room for reasoning plus the answer where every model of the vendor takes it. */
    const val THINKING_LARGE = 32_768

    /** DeepSeek at its deepest effort, whose default output limit doubles there. */
    const val THINKING_MAX = 65_536

    /** OpenAI reasoning models, Gemini 2.5 and later and adaptive-thinking Claude, which all take at least this. */
    const val THINKING_XL = 65_536

    /** The most a thinking request asks for, even when a model reports a larger output limit. */
    const val CEILING = 131_072

    /** What [tokens] needs to know about the model a request goes to. */
    data class Target(
        val wire: Wire,
        val family: String,
        val modelId: String,
        /** What the model accepts for reasoning; null when nothing knows the model. */
        val reasoning: ReasoningInfo?,
        /** The model's own output limit, when known. */
        val maxOutput: Int? = null,
        val contextWindow: Int? = null,
    ) {
        fun tokens(effort: ReasoningEffort?): Int = OutputBudget.tokens(this, effort)
    }

    fun tokens(target: Target, effort: ReasoningEffort?): Int {
        val thinks = thinks(target.reasoning, effort) ?: ChatReasoning.interleaved(target.family)
        val reported = target.maxOutput?.takeIf { it > 0 }
        val wanted = when {
            !thinks -> ANSWER
            // The legacy Anthropic thinking budget goes on top of max_tokens; the answer keeps its own room there.
            target.wire == Wire.Anthropic && AnthropicEngine.isLegacyModel(target.modelId) -> ANSWER
            reported != null -> minOf(reported, CEILING)
            else -> thinkingBudget(target, effort)
        }
        var budget = wanted
        target.contextWindow?.takeIf { it > 0 }?.let { window -> budget = minOf(budget, maxOf(ANSWER, window / 3)) }
        target.maxOutput?.takeIf { it > 0 }?.let { max -> budget = minOf(budget, max) }
        return budget
    }

    /**
     * Whether a request with [effort] makes the model think: true for a forced-reasoning model and for one that thinks
     * unless switched off; false for one that cannot think or with thinking off; null when the model is unknown and
     * no effort says either way.
     */
    fun thinks(info: ReasoningInfo?, effort: ReasoningEffort?): Boolean? {
        if (info == null) return when (effort) {
            null -> null
            ReasoningEffort.Off -> false
            else -> true
        }
        return when {
            !info.supported -> false
            info.levels.isEmpty() -> true
            effort == ReasoningEffort.Off -> ReasoningEffort.Off !in info.levels
            effort == null -> info.serverDefault != ReasoningEffort.Off
            else -> true
        }
    }

    private fun thinkingBudget(target: Target, effort: ReasoningEffort?): Int = when (target.wire) {
        // The legacy thinking budget goes on top of max_tokens there; the answer keeps its own room.
        Wire.Anthropic -> if (AnthropicEngine.isLegacyModel(target.modelId)) ANSWER else THINKING_XL
        Wire.Gemini, Wire.Vertex -> THINKING_XL
        Wire.OpenAiChat, Wire.OpenAiResponses -> when (target.family) {
            Family.DEEPSEEK -> if (effort == ReasoningEffort.Max) THINKING_MAX else THINKING_LARGE
            Family.OPENAI -> THINKING_XL
            Family.MOONSHOT -> THINKING_LARGE
            else -> THINKING
        }
    }
}
