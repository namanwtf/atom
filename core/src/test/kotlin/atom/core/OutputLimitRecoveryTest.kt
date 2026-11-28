package atom.core

import atom.core.agent.Agent
import atom.core.agent.AgentConfig
import atom.core.agent.AgentEvent
import atom.core.agent.StopReason
import atom.core.llm.ChatMessage
import atom.core.llm.FinishReason
import atom.core.llm.LlmEvent
import atom.core.llm.ReasoningEffort
import atom.core.llm.Role
import atom.core.llm.ToolCall
import atom.core.tool.ToolRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A run whose answer the output limit cut off (finish reason "length"). DeepSeek Flash with Deep Think on reasoned
 * until the limit and the run ended with no answer at all; a tool call cut off mid-arguments went back as invalid JSON
 * and the provider refused the next request.
 */
class OutputLimitRecoveryTest {
    private fun config(recover: Boolean = true) = AgentConfig(
        model = "m",
        systemPrompt = "sys",
        maxSteps = 5,
        reasoning = ReasoningEffort.High,
        briefReasoning = ReasoningEffort.Off,
        recoverOutputLimit = recover,
    )

    private val thoughtOnly = listOf(LlmEvent.ReasoningDelta("Let me think about every option at length..."), LlmEvent.Finished(FinishReason.Length))

    private suspend fun run(engine: ScriptedEngine, tool: EchoTool = EchoTool(), config: AgentConfig = config()): List<AgentEvent> =
        Agent(engine, ToolRegistry(listOf(tool)), config).run(listOf(ChatMessage.user("plan my week")), FixedContext()).toList()

    @Test
    fun reasoningThatUsedTheWholeBudgetGetsOneRetryForTheAnswer() = runTest {
        val engine = ScriptedEngine(
            listOf(
                thoughtOnly,
                listOf(LlmEvent.TextDelta("Monday: gym. Tuesday: groceries."), LlmEvent.Finished(FinishReason.Stop)),
            ),
        )
        val events = run(engine)
        val done = assertIs<AgentEvent.Done>(events.last())
        assertEquals(StopReason.Completed, done.reason)
        assertEquals("Monday: gym. Tuesday: groceries.", done.finalText)
        assertEquals(2, engine.requests.size)

        val retry = engine.requests[1]
        // Thinking off (the brief reasoning), no tools: the model can only answer.
        assertEquals(ReasoningEffort.Off, retry.reasoning)
        assertTrue(retry.tools.isEmpty())
        assertEquals(Agent.ANSWER_NOW, retry.messages.last().text)
        assertTrue(retry.messages.last().midTurn)
        // The reasoning is not sent back: the empty reasoning-only turn carries none.
        assertTrue(retry.messages.none { it.reasoning != null })

        // The thoughts stay with the turn for the user; the nudge is internal; the answer follows.
        val appended = events.filterIsInstance<AgentEvent.Appended>()
        val thought = appended.first { it.message.role == Role.Assistant }
        assertEquals("", thought.message.text)
        assertEquals("Let me think about every option at length...", thought.info.reasoning)
        assertTrue(appended.single { it.message.text == Agent.ANSWER_NOW }.info.internal)
        assertEquals("Monday: gym. Tuesday: groceries.", appended.last().message.text)
    }

    @Test
    fun theRetryHappensOnlyOnce() = runTest {
        val engine = ScriptedEngine(listOf(thoughtOnly, thoughtOnly, thoughtOnly))
        val done = assertIs<AgentEvent.Done>(run(engine).last())
        assertEquals(StopReason.OutputLimit, done.reason)
        assertEquals(2, engine.requests.size)
        // A failed retry leaves no nudge in the history.
        assertTrue(done.newMessages.none { it.text == Agent.ANSWER_NOW })
    }

    @Test
    fun withoutRecoveryTheRunEndsAtTheLimit() = runTest {
        val engine = ScriptedEngine(listOf(thoughtOnly))
        val done = assertIs<AgentEvent.Done>(run(engine, config = config(recover = false)).last())
        assertEquals(StopReason.OutputLimit, done.reason)
        assertEquals(1, engine.requests.size)
    }

    @Test
    fun aCutOffAnswerIsContinuedOnceAndKeptAsOneAnswer() = runTest {
        val engine = ScriptedEngine(
            listOf(
                listOf(LlmEvent.TextDelta("Monday: gym. Tues"), LlmEvent.Finished(FinishReason.Length)),
                listOf(LlmEvent.TextDelta("day: groceries."), LlmEvent.Finished(FinishReason.Stop)),
            ),
        )
        val events = run(engine)
        val done = assertIs<AgentEvent.Done>(events.last())
        assertEquals(StopReason.Completed, done.reason)
        assertEquals("Monday: gym. Tuesday: groceries.", done.finalText)

        val continuation = engine.requests[1]
        assertEquals(Agent.CONTINUE_CUT, continuation.messages.last().text)
        assertEquals("Monday: gym. Tues", continuation.messages[continuation.messages.lastIndex - 1].text)
        assertTrue(continuation.tools.isEmpty())
        // One answer in the history, nothing internal left behind.
        assertEquals(listOf("Monday: gym. Tuesday: groceries."), done.newMessages.filter { it.role == Role.Assistant }.map { it.text })
        assertTrue(done.newMessages.none { it.text == Agent.CONTINUE_CUT })
    }

    @Test
    fun aContinuationCutOffAgainEndsAtTheLimitWithEverythingKept() = runTest {
        val engine = ScriptedEngine(
            listOf(
                listOf(LlmEvent.TextDelta("Part one, "), LlmEvent.Finished(FinishReason.Length)),
                listOf(LlmEvent.TextDelta("part two, "), LlmEvent.Finished(FinishReason.Length)),
            ),
        )
        val done = assertIs<AgentEvent.Done>(run(engine).last())
        assertEquals(StopReason.OutputLimit, done.reason)
        assertEquals("Part one, part two, ", done.finalText)
        assertEquals(2, engine.requests.size)
    }

    @Test
    fun aToolCallCutOffMidArgumentsDoesNotRunAndIsNeverReplayed() = runTest {
        val tool = EchoTool()
        val engine = ScriptedEngine(
            listOf(
                listOf(
                    LlmEvent.TextDelta("Saving it."),
                    LlmEvent.ToolCallReady(ToolCall("c1", "echo", """{"text":"a very long te""")),
                    LlmEvent.Finished(FinishReason.Length),
                ),
                listOf(LlmEvent.ToolCallReady(ToolCall("c2", "echo", """{"text":"short"}""")), LlmEvent.Finished(FinishReason.ToolCalls)),
                listOf(LlmEvent.TextDelta("Saved."), LlmEvent.Finished(FinishReason.Stop)),
            ),
        )
        val done = assertIs<AgentEvent.Done>(run(engine, tool).last())
        assertEquals(StopReason.Completed, done.reason)
        assertEquals(1, tool.runs, "only the complete call ran")

        // Every request after the cut: no half-written arguments, and the model is told what happened.
        for (request in engine.requests.drop(1)) {
            val calls = request.messages.flatMap { it.toolCalls }
            assertTrue(calls.none { it.id == "c1" })
            assertTrue(calls.all { Json.parseToJsonElement(it.argumentsJson) is JsonObject })
        }
        val note = engine.requests[1].messages.last()
        assertEquals(Role.User, note.role)
        assertTrue(note.midTurn)
        assertTrue("cut off by the output limit" in note.text && "echo" in note.text, note.text)
        // The text before the cut call is kept as an answer of its own.
        assertTrue(done.newMessages.any { it.role == Role.Assistant && it.text == "Saving it." && it.toolCalls.isEmpty() })
    }

    @Test
    fun aCompleteCallBeforeTheCutStillRuns() = runTest {
        val tool = EchoTool()
        val engine = ScriptedEngine(
            listOf(
                listOf(
                    LlmEvent.ToolCallReady(ToolCall("c1", "echo", """{"text":"first"}""")),
                    LlmEvent.ToolCallReady(ToolCall("c2", "echo", """{"text":"sec""")),
                    LlmEvent.Finished(FinishReason.Length),
                ),
                listOf(LlmEvent.TextDelta("Done."), LlmEvent.Finished(FinishReason.Stop)),
            ),
        )
        val done = assertIs<AgentEvent.Done>(run(engine, tool).last())
        assertEquals(StopReason.Completed, done.reason)
        assertEquals(1, tool.runs)
        val second = engine.requests[1].messages
        assertEquals(listOf("c1"), second.flatMap { it.toolCalls }.map { it.id })
        // The tool result pairs with its call, and the note comes after it.
        assertEquals(Role.Tool, second[second.lastIndex - 1].role)
        assertTrue("cut off" in second.last().text)
    }

    @Test
    fun aCallCutOffAgainAndAgainEndsAtTheLimit() = runTest {
        val tool = EchoTool()
        val cutCall = listOf(LlmEvent.ToolCallReady(ToolCall("c1", "echo", """{"text":"lo""")), LlmEvent.Finished(FinishReason.Length))
        val engine = ScriptedEngine(listOf(cutCall))
        val done = assertIs<AgentEvent.Done>(run(engine, tool).last())
        assertEquals(StopReason.OutputLimit, done.reason)
        assertEquals(0, tool.runs)
        assertEquals(2, engine.requests.size)
    }
}
