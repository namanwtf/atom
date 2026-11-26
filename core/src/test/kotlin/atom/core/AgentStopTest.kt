package atom.core

import atom.core.agent.Agent
import atom.core.agent.AgentConfig
import atom.core.agent.AgentEvent
import atom.core.llm.ChatMessage
import atom.core.llm.EngineCapabilities
import atom.core.llm.FinishReason
import atom.core.llm.LlmEngine
import atom.core.llm.LlmEvent
import atom.core.llm.LlmException
import atom.core.llm.LlmRequest
import atom.core.llm.ToolCall
import atom.core.llm.ToolSpec
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolContext
import atom.core.tool.ToolRegistry
import atom.core.tool.ToolResult
import atom.core.tool.schema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Stop cancels the run's coroutine at whatever the agent is doing. At every stage the agent must end with plain
 * cancellation (nothing else escapes), release what it was waiting on, and have reported everything finished so far,
 * which is what the app saves as the partial history.
 */
class AgentStopTest {
    private enum class Stage { Streaming, Tool, Approval, RetryWait }

    private class Probe {
        val reached = CompletableDeferred<Unit>()
        var released = false
    }

    private fun engineFor(stage: Stage, probe: Probe): LlmEngine = object : LlmEngine {
        var calls = 0
        override val id = "stop-test"
        override val capabilities = EngineCapabilities(nativeToolCalls = true, vision = false, contextWindow = 8000, onDevice = false)

        override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
            calls++
            when (stage) {
                Stage.Streaming -> {
                    emit(LlmEvent.TextDelta("Half an ans"))
                    try {
                        probe.reached.complete(Unit)
                        awaitCancellation()
                    } finally {
                        probe.released = true
                    }
                }
                Stage.RetryWait -> {
                    probe.reached.complete(Unit)
                    throw LlmException("busy", retryable = true, statusCode = 503)
                }
                Stage.Tool, Stage.Approval -> {
                    emit(LlmEvent.TextDelta("Opening it."))
                    emit(LlmEvent.ToolCallReady(ToolCall("c1", "slow", "{}")))
                    emit(LlmEvent.Finished(FinishReason.ToolCalls))
                }
            }
        }
    }

    private fun slowTool(stage: Stage, probe: Probe) = object : Tool {
        override val risk = if (stage == Stage.Approval) Risk.Sensitive else Risk.Read
        override val spec = ToolSpec("slow", "Takes a while", schema())
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            try {
                probe.reached.complete(Unit)
                awaitCancellation()
            } finally {
                probe.released = true
            }
        }
    }

    private fun context(stage: Stage, probe: Probe) = object : ToolContext {
        override val taskId = "stop"
        override suspend fun confirm(action: String): Boolean {
            check(stage == Stage.Approval)
            try {
                probe.reached.complete(Unit)
                awaitCancellation()
            } finally {
                probe.released = true
            }
        }
    }

    private suspend fun stopAt(stage: Stage): Pair<List<AgentEvent>, Throwable?> {
        val probe = Probe()
        val agent = Agent(engineFor(stage, probe), ToolRegistry(listOf(slowTool(stage, probe))), AgentConfig(model = "m", systemPrompt = "sys"))
        val events = ArrayList<AgentEvent>()
        var ended: Throwable? = null
        kotlinx.coroutines.coroutineScope {
            val run = launch {
                try {
                    agent.run(listOf(ChatMessage.user("open the app")), context(stage, probe)).collect { events += it }
                } catch (t: Throwable) {
                    ended = t
                    throw t
                }
            }
            probe.reached.await()
            run.cancelAndJoin()
            assertTrue(run.isCancelled, "$stage: the run ends cancelled")
        }
        if (stage != Stage.RetryWait) assertTrue(probe.released, "$stage: what the run waited on was released")
        return events to ended
    }

    @Test
    fun stoppingWhileStreamingKeepsTheTextSoFar() = runTest {
        val (events, ended) = stopAt(Stage.Streaming)
        assertIs<CancellationException>(ended)
        assertEquals("Half an ans", events.filterIsInstance<AgentEvent.Text>().joinToString("") { it.delta })
        assertTrue(events.none { it is AgentEvent.Failed || it is AgentEvent.Done })
    }

    @Test
    fun stoppingDuringAToolKeepsTheCallThatStarted() = runTest {
        val (events, ended) = stopAt(Stage.Tool)
        assertIs<CancellationException>(ended)
        val appended = events.filterIsInstance<AgentEvent.Appended>().map { it.message }
        // The assistant turn with the call is reported (the app closes the open call as "stopped").
        assertEquals(listOf("slow"), appended.single().toolCalls.map { it.name })
        assertTrue(events.any { it is AgentEvent.ToolStarted })
        assertTrue(events.none { it is AgentEvent.ToolFinished })
    }

    @Test
    fun stoppingWhileWaitingForApprovalNeverRunsTheTool() = runTest {
        val (events, ended) = stopAt(Stage.Approval)
        assertIs<CancellationException>(ended)
        assertTrue(events.none { it is AgentEvent.ToolFinished })
    }

    @Test
    fun stoppingDuringARetryWaitEndsAtOnce() = runTest {
        val (events, ended) = stopAt(Stage.RetryWait)
        assertIs<CancellationException>(ended)
        assertTrue(events.none { it is AgentEvent.Failed })
    }
}
