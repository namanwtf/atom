package atom.core.agent

import atom.core.llm.ChatMessage
import atom.core.llm.ContentPart
import atom.core.llm.Role

data class ContextPolicy(
    /** Rough prompt budget. Smaller prompts are faster, cheaper, and on-device they are directly battery. */
    val budgetTokens: Int = 24_000,
    /** Most recent tool results kept at full length; older ones are shrunk to a stub. */
    val keepFullToolResults: Int = 3,
    val maxToolResultChars: Int = 8_000,
    val compactedToolResultChars: Int = 280,
    /** Screenshots are the most expensive input; only the newest few survive. */
    val keepImages: Int = 1,
)

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
        val fitted = dropOldestGroups(imaged, policy.budgetTokens - estimate(systemPrompt))
        return listOf(ChatMessage.system(systemPrompt)) + fitted
    }

    fun estimate(text: String): Int = text.length / CHARS_PER_TOKEN + 1

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
