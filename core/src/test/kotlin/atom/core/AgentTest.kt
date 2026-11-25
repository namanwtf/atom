package atom.core

import atom.core.agent.Agent
import atom.core.agent.AgentConfig
import atom.core.agent.AgentEvent
import atom.core.agent.StopReason
import atom.core.llm.ChatMessage
import atom.core.llm.EngineCapabilities
import atom.core.llm.FinishReason
import atom.core.llm.LlmEngine
import atom.core.llm.LlmEvent
import atom.core.llm.LlmException
import atom.core.llm.LlmRequest
import atom.core.llm.SearchSuggestions
import atom.core.llm.ToolCall
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolContext
import atom.core.tool.ToolRegistry
import atom.core.tool.ToolResult
import atom.core.tool.schema
import atom.core.tool.string
import atom.core.llm.ToolSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ScriptedEngine(private val turns: List<List<LlmEvent>>) : LlmEngine {
    val requests = mutableListOf<LlmRequest>()
    var failuresBeforeSuccess = 0
    override val id = "scripted"
    override val capabilities = EngineCapabilities(nativeToolCalls = true, vision = false, contextWindow = 8000, onDevice = false)

    override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
        if (failuresBeforeSuccess > 0) {
            failuresBeforeSuccess--
            throw LlmException("busy", retryable = true, statusCode = 503)
        }
        requests += request
        turns[minOf(requests.size - 1, turns.lastIndex)].forEach { emit(it) }
    }
}

class EchoTool(override val risk: Risk = Risk.Read) : Tool {
    var runs = 0
    override val spec = ToolSpec("echo", "Echo text back", schema { string("text", "what to echo", required = true) })
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        runs++
        return ToolResult.ok("echo: " + args.string("text"))
    }
}

class FixedContext(private val approve: Boolean = true) : ToolContext {
    override val taskId = "t1"
    var asked = 0
    override suspend fun confirm(action: String): Boolean {
        asked++
        return approve
    }
}

class AgentTest {
    private fun config() = AgentConfig(model = "m", systemPrompt = "sys", maxSteps = 5)

    private fun call(text: String) = LlmEvent.ToolCallReady(ToolCall("c1", "echo", """{"text":"$text"}"""))

    @Test
    fun runsToolThenAnswers() = runTest {
        val engine = ScriptedEngine(
            listOf(
                listOf(call("hi"), LlmEvent.Finished(FinishReason.ToolCalls)),
                listOf(LlmEvent.TextDelta("done"), LlmEvent.Finished(FinishReason.Stop)),
            ),
        )
        val tool = EchoTool()
        val events = Agent(engine, ToolRegistry(listOf(tool)), config()).run(listOf(ChatMessage.user("go")), FixedContext()).toList()

        val done = assertIs<AgentEvent.Done>(events.last())
        assertEquals("done", done.finalText)
        assertEquals(StopReason.Completed, done.reason)
        assertEquals(1, tool.runs)
        assertEquals(3, done.newMessages.size)
        assertEquals("echo: hi", engine.requests[1].messages.last().text)
    }

    @Test
    fun sensitiveToolAsksAndRespectsDecline() = runTest {
        val engine = ScriptedEngine(
            listOf(
                listOf(call("rm"), LlmEvent.Finished(FinishReason.ToolCalls)),
                listOf(LlmEvent.TextDelta("ok"), LlmEvent.Finished(FinishReason.Stop)),
            ),
        )
        val tool = EchoTool(Risk.Sensitive)
        val ctx = FixedContext(approve = false)
        val events = Agent(engine, ToolRegistry(listOf(tool)), config()).run(listOf(ChatMessage.user("go")), ctx).toList()

        assertEquals(1, ctx.asked)
        assertEquals(0, tool.runs)
        val finished = events.filterIsInstance<AgentEvent.ToolFinished>().single()
        assertTrue(finished.result.isError)
    }

    @Test
    fun stopsAtStepLimitAndNudgesRepeats() = runTest {
        val engine = ScriptedEngine(listOf(listOf(call("same"), LlmEvent.Finished(FinishReason.ToolCalls))))
        val events = Agent(engine, ToolRegistry(listOf(EchoTool())), config()).run(listOf(ChatMessage.user("go")), FixedContext()).toList()

        assertEquals(StopReason.StepLimit, assertIs<AgentEvent.Done>(events.last()).reason)
        assertTrue(engine.requests.last().messages.any { it.text.contains("identical action") })
    }

    @Test
    fun searchSuggestionsLandOnTheAnswer() = runTest {
        val suggestions = SearchSuggestions(listOf("weather paris"), "<div>chip</div>")
        val engine = ScriptedEngine(
            listOf(listOf(LlmEvent.TextDelta("Sunny"), LlmEvent.Suggestions(suggestions), LlmEvent.Finished(FinishReason.Stop))),
        )
        val events = Agent(engine, ToolRegistry(emptyList()), config()).run(listOf(ChatMessage.user("go")), FixedContext()).toList()
        val answer = events.filterIsInstance<AgentEvent.Appended>().single { it.message.text == "Sunny" }
        assertEquals(suggestions, answer.info.searchSuggestions)
    }

    @Test
    fun retriesCleanTransientFailures() = runTest {
        val engine = ScriptedEngine(listOf(listOf(LlmEvent.TextDelta("hi"), LlmEvent.Finished(FinishReason.Stop))))
        engine.failuresBeforeSuccess = 2
        val events = Agent(engine, ToolRegistry(emptyList()), config()).run(listOf(ChatMessage.user("go")), FixedContext()).toList()
        assertIs<AgentEvent.Done>(events.last())
    }

    @Test
    fun badArgumentsBecomeToolErrorsNotCrashes() = runTest {
        val engine = ScriptedEngine(
            listOf(
                listOf(LlmEvent.ToolCallReady(ToolCall("c1", "echo", "{not json")), LlmEvent.Finished(FinishReason.ToolCalls)),
                listOf(LlmEvent.TextDelta("sorry"), LlmEvent.Finished(FinishReason.Stop)),
            ),
        )
        val events = Agent(engine, ToolRegistry(listOf(EchoTool())), config()).run(listOf(ChatMessage.user("go")), FixedContext()).toList()
        assertTrue(events.filterIsInstance<AgentEvent.ToolFinished>().single().result.isError)
        assertIs<AgentEvent.Done>(events.last())
    }
}
