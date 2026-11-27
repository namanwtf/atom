package atom.core

import atom.core.agent.Agent
import atom.core.agent.AgentConfig
import atom.core.agent.AgentEvent
import atom.core.agent.StopReason
import atom.core.llm.ChatMessage
import atom.core.llm.FinishReason
import atom.core.llm.LlmEvent
import atom.core.llm.Role
import atom.core.llm.ToolCall
import atom.core.tool.ToolRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A reply that hits the output limit partway through a tool call (finish reason Length) leaves that call's arguments
 * cut off. The call must not run, the model must be told why, and its arguments must never be replayed as invalid
 * JSON: DeepSeek refuses every later request of the conversation otherwise ("EOF while parsing a string").
 */
class OutputLimitToolCallTest {
    private fun config() = AgentConfig(model = "m", systemPrompt = "sys", maxSteps = 5, maxOutputTokens = 2048)

    private val cutArgs = """{"text":"<!doctype html><html><head><style>body{margin:0"""
    private val cutCall = ToolCall("c_cut", "echo", cutArgs)
    private val wholeCall = ToolCall("c_ok", "echo", """{"text":"hi"}""")

    private fun validObject(json: String) = runCatching { Json.parseToJsonElement(json) is JsonObject }.getOrDefault(false)

    @Test
    fun aCallCutOffAtTheOutputLimitIsNotRunAndNamesTheLimit() = runTest {
        val engine = ScriptedEngine(
            listOf(
                listOf(LlmEvent.ToolCallReady(wholeCall), LlmEvent.ToolCallReady(cutCall), LlmEvent.Finished(FinishReason.Length)),
                listOf(LlmEvent.TextDelta("split it"), LlmEvent.Finished(FinishReason.Stop)),
            ),
        )
        val tool = EchoTool()
        val events = Agent(engine, ToolRegistry(listOf(tool)), config()).run(listOf(ChatMessage.user("make a page")), FixedContext()).toList()

        // The complete call before it still runs; the cut one does not.
        assertEquals(1, tool.runs)
        val finished = events.filterIsInstance<AgentEvent.ToolFinished>()
        assertEquals(listOf("c_ok", "c_cut"), finished.map { it.call.id })
        assertFalse(finished[0].result.isError)
        val error = finished[1].result
        assertTrue(error.isError)
        assertTrue("output limit" in error.text, error.text)
        assertTrue("2048 tokens" in error.text, error.text)
        assertTrue("${cutArgs.length} characters" in error.text, error.text)
        assertTrue("not run" in error.text, error.text)
        assertFalse("not a valid JSON object" in error.text, error.text)

        val done = assertIs<AgentEvent.Done>(events.last())
        assertEquals(StopReason.Completed, done.reason)
        assertEquals("split it", done.finalText)
    }

    @Test
    fun theCutCallIsStoredAndReplayedWithValidArguments() = runTest {
        val engine = ScriptedEngine(
            listOf(
                listOf(LlmEvent.TextDelta("Writing it."), LlmEvent.ToolCallReady(cutCall), LlmEvent.Finished(FinishReason.Length)),
                listOf(LlmEvent.TextDelta("ok"), LlmEvent.Finished(FinishReason.Stop)),
            ),
        )
        val events = Agent(engine, ToolRegistry(listOf(EchoTool())), config()).run(listOf(ChatMessage.user("go")), FixedContext()).toList()

        // What the app persists: the call keeps its id and name, with a valid placeholder for the arguments.
        val stored = assertIs<AgentEvent.Done>(events.last()).newMessages
        val assistant = stored.first { it.role == Role.Assistant && it.toolCalls.isNotEmpty() }
        assertEquals("Writing it.", assistant.text)
        assertEquals(listOf(ToolCall("c_cut", "echo", "{}")), assistant.toolCalls)
        assertEquals("c_cut", stored.first { it.role == Role.Tool }.toolCallId)

        // What the next request carries: every call's arguments parse as a JSON object.
        val replayed = engine.requests[1].messages.flatMap { it.toolCalls }
        assertEquals(listOf("c_cut"), replayed.map { it.id })
        replayed.forEach { assertTrue(validObject(it.argumentsJson), it.argumentsJson) }
        assertTrue(engine.requests[1].messages.last().text.contains("output limit"))
    }

    @Test
    fun aCallCutBeforeItsNameIsDropped() = runTest {
        val engine = ScriptedEngine(
            listOf(listOf(LlmEvent.TextDelta("Here is"), LlmEvent.ToolCallReady(ToolCall("c_x", "", """{"te""")), LlmEvent.Finished(FinishReason.Length))),
        )
        val tool = EchoTool()
        val events = Agent(engine, ToolRegistry(listOf(tool)), config()).run(listOf(ChatMessage.user("go")), FixedContext()).toList()

        assertEquals(0, tool.runs)
        assertTrue(events.none { it is AgentEvent.ToolStarted })
        val done = assertIs<AgentEvent.Done>(events.last())
        assertEquals(StopReason.OutputLimit, done.reason)
        assertTrue(done.newMessages.all { it.toolCalls.isEmpty() })
    }

    @Test
    fun invalidArgumentsWithoutTheLimitKeepTheGeneralError() = runTest {
        val engine = ScriptedEngine(
            listOf(
                listOf(LlmEvent.ToolCallReady(ToolCall("c1", "echo", "not json")), LlmEvent.Finished(FinishReason.ToolCalls)),
                listOf(LlmEvent.TextDelta("ok"), LlmEvent.Finished(FinishReason.Stop)),
            ),
        )
        val events = Agent(engine, ToolRegistry(listOf(EchoTool())), config()).run(listOf(ChatMessage.user("go")), FixedContext()).toList()

        val result = events.filterIsInstance<AgentEvent.ToolFinished>().single().result
        assertTrue(result.isError)
        assertTrue("not a valid JSON object" in result.text, result.text)
    }
}
