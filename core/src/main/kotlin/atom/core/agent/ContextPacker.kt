package atom.core.agent

import atom.core.llm.ChatMessage
import atom.core.llm.ContentPart
import atom.core.llm.Role
import atom.core.llm.ToolSpec

data class ContextPolicy(
    /**
     * A deliberate cap on the prompt in tokens (on-device, where every prompt token is prefill on the phone). Null
     * means "the model's own window": [AgentConfig] derives the budget from it, and from [UNKNOWN_WINDOW_BUDGET] when
     * the window is unknown. Never set a small cap for a cloud model with a known window: the history would be cut
     * and compacted long before the model is anywhere near full.
     */
    val budgetTokens: Int? = null,
    /** Most recent tool results kept at full length; older ones are shrunk to a stub. */
    val keepFullToolResults: Int = 3,
    val maxToolResultChars: Int = 8_000,
    val compactedToolResultChars: Int = 280,
    /** Screenshots are the most expensive input; only the newest few survive. */
    val keepImages: Int = 1,
    /**
     * On-device engines without native tool calling: the whole window that the messages, the tool descriptions
     * (written into the prompt) and the answer share. Each request's message budget is then what is left after the
     * tools that request offers ([AgentConfig.packingFor]), never more than [budgetTokens]. Null: no sharing.
     */
    val sharedWindow: Int? = null,
) {
    companion object {
        /** Room kept free in a [sharedWindow] for template tokens, the tool-format header and estimation error. */
        const val SHARED_MARGIN = 384

        /**
         * The messages should get at least this much of a [sharedWindow]; below it the agent first drops optional
         * tool groups, then shrinks the history down to [SHARED_FLOOR].
         */
        const val SHARED_MIN_BUDGET = 600

        /** The least the messages get in an over-full [sharedWindow]: the task and the newest step, shortened. */
        const val SHARED_FLOOR = 200

        /** Tool results are cut to this many characters in an over-full [sharedWindow]. */
        const val SHARED_FLOOR_RESULT_CHARS = 600

        /** Prompt budget when the model's window is unknown: generous enough for any current model, never tiny. */
        const val UNKNOWN_WINDOW_BUDGET = 64_000

        /**
         * What a prompt may use in a [window]-token model while leaving room for [maxOutputTokens] of answer
         * (providers count both against the window). Null window: [UNKNOWN_WINDOW_BUDGET].
         */
        fun promptBudget(window: Int?, maxOutputTokens: Int): Int {
            if (window == null || window <= 0) return UNKNOWN_WINDOW_BUDGET
            return (window - maxOutputTokens).coerceAtLeast(window / 2)
        }
    }
}

/**
 * Builds the message list actually sent to the model. The stored conversation is never mutated:
 * compaction is a view, so the user keeps the full record.
 */
object ContextPacker {
    private const val CHARS_PER_TOKEN = 4
    private const val IMAGE_TOKENS = 1_100

    fun pack(systemPrompt: String, convo: List<ChatMessage>, policy: ContextPolicy): List<ChatMessage> {
        val shrunk = shrinkToolResults(convo, policy)
        val imaged = limitImages(shrunk, policy.keepImages)
        val budget = policy.budgetTokens ?: ContextPolicy.UNKNOWN_WINDOW_BUDGET
        val fitted = dropOldestGroups(imaged, budget - estimate(systemPrompt))
        return listOf(ChatMessage.system(systemPrompt)) + fitted
    }

    /** Estimated prompt size after the cheap shrinking steps but before anything is dropped. */
    fun measure(systemPrompt: String, convo: List<ChatMessage>, policy: ContextPolicy): Int =
        estimate(systemPrompt) + limitImages(shrinkToolResults(convo, policy), policy.keepImages).sumOf(::estimate)

    fun estimate(text: String): Int = text.length / CHARS_PER_TOKEN + 1

    /** Rough tokens one tool declaration costs in every request (name, description and JSON schema). */
    fun estimateTool(spec: ToolSpec): Int = estimate(spec.name) + estimate(spec.description) + estimate(spec.parameters.toString()) + 8

    fun estimateTools(specs: List<ToolSpec>): Int = specs.sumOf { estimateTool(it) }

    fun estimate(message: ChatMessage): Int =
        message.parts.sumOf { if (it is ContentPart.Text) estimate(it.text) else IMAGE_TOKENS } +
            message.toolCalls.sumOf { estimate(it.argumentsJson) + estimate(it.name) } + 4

    private fun shrinkToolResults(convo: List<ChatMessage>, policy: ContextPolicy): List<ChatMessage> {
        var seen = 0
        return convo.asReversed().map { msg ->
            if (msg.role != Role.Tool) return@map msg
            val limit = if (seen++ < policy.keepFullToolResults) policy.maxToolResultChars else policy.compactedToolResultChars
            msg.copy(parts = msg.parts.map { part ->
                if (part is ContentPart.Text && part.text.length > limit) {
                    ContentPart.Text(part.text.take(limit) + "\n…[${part.text.length - limit} chars trimmed]")
                } else {
                    part
                }
            })
        }.asReversed()
    }

    private fun limitImages(convo: List<ChatMessage>, keep: Int): List<ChatMessage> {
        var kept = 0
        return convo.asReversed().map { msg ->
            if (msg.parts.none { it is ContentPart.Image }) return@map msg
            if (kept++ < keep) return@map msg
            msg.copy(parts = msg.parts.map { if (it is ContentPart.Image) ContentPart.Text("[earlier screenshot omitted]") else it })
        }.asReversed()
    }

    /**
     * Drops whole exchanges (an assistant message plus its tool results) oldest-first, never splitting a
     * tool call from its result, and always keeping the original task and the latest exchanges.
     */
    private fun dropOldestGroups(convo: List<ChatMessage>, budget: Int): List<ChatMessage> {
        if (convo.sumOf(::estimate) <= budget) return convo

        val anchor = convo.indexOfFirst { it.role == Role.User }
        val head = if (anchor >= 0) convo.subList(0, anchor + 1) else emptyList()
        val groups = groupExchanges(if (anchor >= 0) convo.subList(anchor + 1, convo.size) else convo)

        val minTail = 2
        var total = convo.sumOf(::estimate)
        var dropFrom = 0
        while (total > budget && groups.size - dropFrom > minTail) {
            total -= groups[dropFrom].sumOf(::estimate)
            dropFrom++
        }
        if (dropFrom == 0) return convo

        val note = ChatMessage.user("[${dropFrom} earlier step(s) removed to save context. Re-check state if needed.]")
        return head + note + groups.drop(dropFrom).flatten()
    }

    private fun groupExchanges(messages: List<ChatMessage>): List<List<ChatMessage>> {
        val groups = mutableListOf<MutableList<ChatMessage>>()
        for (msg in messages) {
            if (msg.role == Role.Tool && groups.isNotEmpty()) groups.last() += msg else groups += mutableListOf(msg)
        }
        return groups
    }
}
