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
 * - OpenAI reasoning models, Gemini 2.5 and later, adaptive-thinking Claude: 32K, well under their maximums.
 * - Anything else: 16K.
 * A model's own output limit, when its catalog entry or model list says it, caps the budget, and so does a quarter of
 * a known context window (some servers refuse a request whose prompt plus output limit exceeds the window).
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
        val wanted = if (thinks) thinkingBudget(target, effort) else ANSWER
        var budget = wanted
        target.contextWindow?.takeIf { it > 0 }?.let { window -> budget = minOf(budget, maxOf(ANSWER, window / 4)) }
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
        Wire.Anthropic -> if (AnthropicEngine.isLegacyModel(target.modelId)) ANSWER else THINKING_LARGE
        Wire.Gemini, Wire.Vertex -> THINKING_LARGE
        Wire.OpenAiChat, Wire.OpenAiResponses -> when (target.family) {
            Family.DEEPSEEK -> if (effort == ReasoningEffort.Max) THINKING_MAX else THINKING_LARGE
            Family.MOONSHOT, Family.OPENAI -> THINKING_LARGE
            else -> THINKING
        }
    }
}
