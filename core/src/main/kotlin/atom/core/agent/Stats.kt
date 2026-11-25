package atom.core.agent

import kotlinx.serialization.Serializable

/** One model request, as measured by the agent loop. */
data class ModelCallStats(
    val ttftMs: Long?,
    val durationMs: Long,
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheReadTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
)

/** Running totals for one conversation, shown in the session stats popover. Persisted with the conversation. */
@Serializable
data class SessionStats(
    val turns: Int = 0,
    val steps: Int = 0,
    val modelCalls: Int = 0,
    val modelTimeMs: Long = 0,
    val toolCalls: Int = 0,
    val toolFailures: Int = 0,
    val toolTimeMs: Long = 0,
    val ttftTotalMs: Long = 0,
    val ttftSamples: Int = 0,
    val generationTimeMs: Long = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val compactions: Int = 0,
    val compactionTokens: Long = 0,
    val compactionSavedTokens: Long = 0,
) {
    val averageTtftMs: Long? get() = if (ttftSamples == 0) null else ttftTotalMs / ttftSamples

    val outputTokensPerSecond: Double? get() =
        if (generationTimeMs <= 0 || outputTokens <= 0) null else outputTokens * 1000.0 / generationTimeMs

    /** Share of input tokens served from the provider's prompt cache, when it reports that. */
    val cacheHitRate: Double? get() =
        if (cacheReadTokens <= 0 || inputTokens <= 0) null else cacheReadTokens.toDouble() / inputTokens

    /** Requests after which a compaction has paid for itself (tokens spent / tokens saved per request). */
    val compactionPaybackRequests: Double? get() =
        if (compactionSavedTokens <= 0) null else compactionTokens.toDouble() / compactionSavedTokens

    fun withTurn(): SessionStats = copy(turns = turns + 1)

    fun withModelCall(call: ModelCallStats): SessionStats = copy(
        steps = steps + 1,
        modelCalls = modelCalls + 1,
        modelTimeMs = modelTimeMs + call.durationMs,
        ttftTotalMs = ttftTotalMs + (call.ttftMs ?: 0),
        ttftSamples = ttftSamples + if (call.ttftMs != null) 1 else 0,
        generationTimeMs = generationTimeMs + (call.durationMs - (call.ttftMs ?: 0)).coerceAtLeast(0),
        inputTokens = inputTokens + call.inputTokens,
        outputTokens = outputTokens + call.outputTokens,
        cacheReadTokens = cacheReadTokens + call.cacheReadTokens,
        cacheWriteTokens = cacheWriteTokens + call.cacheWriteTokens,
    )

    fun withTool(durationMs: Long, failed: Boolean): SessionStats = copy(
        toolCalls = toolCalls + 1,
        toolFailures = toolFailures + if (failed) 1 else 0,
        toolTimeMs = toolTimeMs + durationMs,
    )

    fun withCompaction(cost: ModelCallStats, tokensBefore: Int, tokensAfter: Int): SessionStats = copy(
        compactions = compactions + 1,
        compactionTokens = compactionTokens + cost.inputTokens + cost.outputTokens,
        compactionSavedTokens = compactionSavedTokens + (tokensBefore - tokensAfter).coerceAtLeast(0),
        modelTimeMs = modelTimeMs + cost.durationMs,
    )
}
