package atom.core

import atom.core.agent.Agent
import atom.core.agent.AgentConfig
import atom.core.agent.AgentEvent
import atom.core.agent.Compactor
import atom.core.agent.ContextPacker
import atom.core.agent.ContextPolicy
import atom.core.agent.LimitOverrides
import atom.core.agent.ModelLimits
import atom.core.llm.ChatMessage
import atom.core.llm.ContentPart
import atom.core.llm.EngineCapabilities
import atom.core.llm.FinishReason
import atom.core.llm.LlmEngine
import atom.core.llm.LlmEvent
import atom.core.llm.LlmException
import atom.core.llm.LlmRequest
import atom.core.llm.ReasoningEffort
import atom.core.llm.Role
import atom.core.llm.ToolCall
import atom.core.tool.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Limits follow the selected model: frontier cloud models get room, small on-device models keep their tight values. */
class ModelLimitsTest {
    private val million = ModelLimits(window = 1_048_576, maxOutput = 128_000, vision = true)
    private val cloud128 = ModelLimits(window = 128_000, vision = true)
    private val local32 = ModelLimits(onDevice = true, window = 32_768, vision = true)

    @Test
    fun aMillionTokenCloudModelGetsRoom() {
        assertEquals(52_428, million.toolResultChars)
        assertEquals(6, million.keepFullToolSteps)
        assertEquals(6, million.imagesKept)
        assertEquals(30_000, million.terminalStdoutChars)
        assertEquals(8_000, million.terminalStderrChars)
        assertEquals(24_000, million.screenTreeChars)
        assertEquals(8_000, million.memoryTokens)
        assertEquals(16_000, million.compactionSummaryTokens(900_000))
        assertEquals(8, million.maxParallelSubagents)
        assertEquals(ModelLimits.SUBAGENT_MS, million.subagentBudgetMs)
        assertEquals(15, million.taskMinutes)
    }

    @Test
    fun aMidSizeCloudModelStaysNearTheOldValues() {
        assertEquals(8_000, cloud128.toolResultChars)
        assertEquals(3, cloud128.keepFullToolSteps)
        assertEquals(3, cloud128.imagesKept)
        assertEquals(4_000, cloud128.terminalStdoutChars)
        assertEquals(6_000, cloud128.screenTreeChars)
        assertEquals(3_840, cloud128.memoryTokens)
        assertEquals(4_000, cloud128.compactionSummaryTokens(120_000))
    }

    @Test
    fun onDeviceModelsKeepTheirTightLimits() {
        assertEquals(ModelLimits.TOOL_RESULT_CHARS, local32.toolResultChars)
        assertEquals(1, local32.imagesKept)
        assertEquals(3_900, local32.terminalStdoutChars)
        assertEquals(6_000, local32.screenTreeChars)
        assertEquals(983, local32.memoryTokens)
        assertEquals(2_000, local32.compactionSummaryTokens(24_000))
        assertEquals(4, local32.maxParallelSubagents)
        assertEquals(ModelLimits.LOCAL_SUBAGENT_MS, local32.subagentBudgetMs)
        assertEquals(5, local32.taskMinutes)
        // The packing policy of an on-device run is the one its plan set.
        val plan = ContextPolicy(budgetTokens = 6_000, keepFullToolResults = 2, maxToolResultChars = 3_000, sharedWindow = 8_192)
        assertEquals(plan, local32.policy(plan))
    }

    @Test
    fun anUnknownModelKeepsWhatAtomAlwaysUsed() {
        val d = ModelLimits.Default
        assertEquals(ContextPolicy(), d.policy().copy(keepImages = 1))
        assertEquals(0, d.imagesKept, "no vision known")
        assertEquals(1_000, d.mcpDescriptionChars)
        assertEquals(6_000, d.historyEntryChars)
        assertEquals((60_000 / 8).coerceIn(400, 2_000), d.compactionSummaryTokens(60_000))
    }

    @Test
    fun userOverridesWin() {
        val custom = million.copy(
            overrides = LimitOverrides(
                maxOutputTokens = 200_000,
                maxSteps = 50,
                toolResultChars = 100_000,
                subagentMinutes = 30,
                approvalWaitMinutes = 2,
                imagesKept = 2,
                taskMinutes = 60,
            ),
        )
        assertEquals(128_000, custom.maxOutputTokens(65_536), "never above the model's own limit")
        assertEquals(65_536, million.maxOutputTokens(65_536))
        assertEquals(50, custom.maxSteps(400))
        assertEquals(100_000, custom.toolResultChars)
        assertEquals(30 * 60_000L, custom.subagentBudgetMs)
        assertEquals(2 * 60_000L, custom.approvalWaitMs)
        assertEquals(2, custom.imagesKept)
        assertEquals(60, custom.taskMinutes)
        assertEquals(ModelLimits.APPROVAL_WAIT_MS, million.approvalWaitMs)
        // An on-device subagent stays short whatever the setting says.
        assertEquals(ModelLimits.LOCAL_SUBAGENT_MS, local32.copy(overrides = custom.overrides).subagentBudgetMs)
    }

    // ------------------------------------------------------------------ packing

    private fun exchange(id: String, text: String): List<ChatMessage> {
        val call = ToolCall(id, "read_page", "{}")
        return listOf(ChatMessage.assistant("", listOf(call)), ChatMessage.toolResult(call, listOf(ContentPart.Text(text))))
    }

    @Test
    fun parallelCallsOfOneStepStayInFullTogether() {
        val long = "x".repeat(5_000)
        val calls = (1..4).map { ToolCall("p$it", "read_page", "{}") }
        val convo = buildList {
            add(ChatMessage.user("task"))
            addAll(exchange("old1", long))
            addAll(exchange("old2", long))
            add(ChatMessage.assistant("", calls))
            calls.forEach { add(ChatMessage.toolResult(it, listOf(ContentPart.Text(long)))) }
        }
        val policy = ContextPolicy(keepFullToolResults = 1, maxToolResultChars = 8_000, compactedToolResultChars = 100)
        val packed = ContextPacker.pack("sys", convo, policy)
        val results = packed.filter { it.role == Role.Tool }
        assertEquals(6, results.size)
        // The newest step (four parallel results) is whole; the two older steps shrink.
        assertTrue(results.takeLast(4).all { it.text == long })
        assertTrue(results.take(2).all { it.text.length < 200 })
    }

    @Test
    fun trimmingKeepsThePagingFooter() {
        val footer = "[Showing characters 0-12000 of 90000. Call again with offset=12000 to read on.]"
        val text = "Title\n" + "body ".repeat(5_000) + "\n" + footer
        val trimmed = ContextPacker.trimResult(text, 2_000)
        assertTrue(trimmed.startsWith("Title"))
        assertTrue(trimmed.endsWith(footer), trimmed.takeLast(200))
        assertTrue(trimmed.length <= 2_000 + 60)
        // Without a paging footer the end is simply cut.
        val plain = "a".repeat(3_000) + "\nthe end"
        assertTrue(ContextPacker.trimResult(plain, 1_000).contains("chars trimmed]"))
        assertTrue(!ContextPacker.trimResult(plain, 1_000).endsWith("the end"))
    }

    // ------------------------------------------------------------------ retries and compaction

    private class Busy(private val retryAfterMs: Long?) : LlmEngine {
        var calls = 0
        override val id = "busy"
        override val capabilities = EngineCapabilities(nativeToolCalls = true, vision = false, contextWindow = 8_000, onDevice = false)
        override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
            calls++
            if (calls == 1) throw LlmException("rate limited", retryable = true, statusCode = 429, retryAfterMs = retryAfterMs)
            emit(LlmEvent.TextDelta("ok"))
            emit(LlmEvent.Finished(FinishReason.Stop))
        }
    }

    private suspend fun waitOf(retryAfterMs: Long?): Long {
        val events = Agent(Busy(retryAfterMs), ToolRegistry(emptyList()), AgentConfig("m", "s", retryDelayMs = 2_000))
            .run(listOf(ChatMessage.user("hi")), FixedContext()).toList()
        assertIs<AgentEvent.Done>(events.last())
        return events.filterIsInstance<AgentEvent.Retrying>().single().delayMs
    }

    @Test
    fun retryAfterIsHonouredWithinBounds() = runTest {
        assertEquals(20_000, waitOf(20_000))
        assertEquals(AgentConfig.MAX_RETRY_AFTER_MS, waitOf(10 * 60_000), "a long Retry-After is cut to a minute")
        assertEquals(2_000, waitOf(500), "never shorter than the usual backoff")
        assertEquals(2_000, waitOf(null))
        assertTrue(currentTime >= 20_000)
    }

    @Test
    fun anEmptySummaryGetsOneMoreTryWithThinkingOff() = runTest {
        val requests = mutableListOf<LlmRequest>()
        val engine = object : LlmEngine {
            override val id = "sum"
            override val capabilities = EngineCapabilities(nativeToolCalls = true, vision = false, contextWindow = 8_000, onDevice = false)
            override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
                requests += request
                if (requests.size == 1) {
                    emit(LlmEvent.ReasoningDelta("thinking it all through..."))
                    emit(LlmEvent.Finished(FinishReason.Length))
                } else {
                    emit(LlmEvent.TextDelta("The user asked for X; done so far: Y."))
                    emit(LlmEvent.Finished(FinishReason.Stop))
                }
            }
        }
        val request = Compactor.request("m", listOf(ChatMessage.user("do X")), maxOutputTokens = 1_000, reasoning = ReasoningEffort.High)
        val result = Compactor.summarize(engine, request)
        assertEquals("The user asked for X; done so far: Y.", result.summary)
        assertEquals(ReasoningEffort.Off, requests[1].reasoning)
        assertEquals(2_000, requests[1].maxOutputTokens)
    }
}
