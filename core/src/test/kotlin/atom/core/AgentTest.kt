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
import atom.core.llm.Role
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
import kotlin.test.assertNull
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

    /** Calls a tool whenever tools are offered; [wrapUp] answers the request that offers none. */
    private class StepLimitEngine(private val wrapUp: (LlmRequest, Int) -> List<LlmEvent>) : LlmEngine {
        val requests = mutableListOf<LlmRequest>()
        var wrapUps = 0
        override val id = "steps"
        override val capabilities = EngineCapabilities(nativeToolCalls = true, vision = false, contextWindow = 8000, onDevice = false)

        override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
            requests += request
            if (request.tools.isNotEmpty()) {
                val n = requests.size
                emit(LlmEvent.ToolCallReady(ToolCall("c$n", "echo", """{"text":"step $n"}""")))
                emit(LlmEvent.Finished(FinishReason.ToolCalls))
            } else {
                wrapUp(request, wrapUps++).forEach { emit(it) }
            }
        }
    }

    @Test
    fun stepLimitEndsWithAWrapUpReplyWithoutTools() = runTest {
        val engine = StepLimitEngine { _, _ -> listOf(LlmEvent.TextDelta("I opened the app and read 5 screens."), LlmEvent.Finished(FinishReason.Stop)) }
        val tool = EchoTool()
        val events = Agent(engine, ToolRegistry(listOf(tool)), config()).run(listOf(ChatMessage.user("go")), FixedContext()).toList()

        val done = assertIs<AgentEvent.Done>(events.last())
        assertEquals(StopReason.StepLimit, done.reason)
        assertEquals("I opened the app and read 5 screens.", done.finalText)
        assertEquals(5, tool.runs, "no tool runs after the limit")
        assertEquals(6, engine.requests.size, "five steps plus one wrap-up call")
        val last = engine.requests.last()
        assertTrue(last.tools.isEmpty(), "the wrap-up offers no tools")
        assertEquals(Agent.STEP_LIMIT_REASON + " " + Agent.WRAP_UP, last.messages.last().text)
        assertEquals(Role.User, last.messages.last().role)
        // The nudge is internal, and the reply is the last message the run appended.
        val nudge = events.filterIsInstance<AgentEvent.Appended>().single { it.message.text.endsWith(Agent.WRAP_UP) }
        assertTrue(nudge.info.internal)
        assertEquals("I opened the app and read 5 screens.", done.newMessages.last().text)
        assertEquals(Role.Assistant, done.newMessages.last().role)
        assertTrue(events.filterIsInstance<AgentEvent.Text>().any { it.delta.contains("5 screens") }, "the reply streams like any answer")
    }

    @Test
    fun wrapUpDropsToolCallsTheModelMakesAnyway() = runTest {
        val engine = StepLimitEngine { _, _ ->
            listOf(
                LlmEvent.TextDelta("Here is where it stands."),
                LlmEvent.ToolCallReady(ToolCall("x", "echo", """{"text":"more"}""")),
                LlmEvent.Finished(FinishReason.ToolCalls),
            )
        }
        val tool = EchoTool()
        val events = Agent(engine, ToolRegistry(listOf(tool)), config()).run(listOf(ChatMessage.user("go")), FixedContext()).toList()

        val done = assertIs<AgentEvent.Done>(events.last())
        assertEquals("Here is where it stands.", done.finalText)
        assertEquals(5, tool.runs)
        assertTrue(done.newMessages.last().toolCalls.isEmpty())
    }

    @Test
    fun failingWrapUpFallsBackToTheFixedText() = runTest {
        val engine = StepLimitEngine { _, _ -> throw LlmException("bad request", retryable = false, statusCode = 400) }
        val events = Agent(engine, ToolRegistry(listOf(EchoTool())), config()).run(listOf(ChatMessage.user("go")), FixedContext()).toList()

        val done = assertIs<AgentEvent.Done>(events.last())
        assertEquals(StopReason.StepLimit, done.reason)
        assertEquals("Stopped after 5 steps without finishing.", done.finalText)
        assertTrue(events.none { it is AgentEvent.Failed })
        assertEquals(2, engine.wrapUps, "native history first, then once as plain text")
    }

    @Test
    fun wrapUpRetriesAsPlainTextWhenToolHistoryIsRefused() = runTest {
        val engine = StepLimitEngine { request, _ ->
            if (request.messages.any { it.role == Role.Tool || it.toolCalls.isNotEmpty() }) {
                throw LlmException("tool_use blocks need tools", retryable = false, statusCode = 400)
            }
            listOf(LlmEvent.TextDelta("Summary from plain text."), LlmEvent.Finished(FinishReason.Stop))
        }
        val events = Agent(engine, ToolRegistry(listOf(EchoTool())), config()).run(listOf(ChatMessage.user("go")), FixedContext()).toList()

        assertEquals("Summary from plain text.", assertIs<AgentEvent.Done>(events.last()).finalText)
        val plain = engine.requests.last()
        assertTrue(plain.messages.any { it.text.contains("<tool_result name=\"echo\">") })
    }

    @Test
    fun emptyOrTransientlyFailingWrapUpKeepsTheFixedText() = runTest {
        val empty = StepLimitEngine { _, _ -> listOf(LlmEvent.Finished(FinishReason.Stop)) }
        val a = Agent(empty, ToolRegistry(listOf(EchoTool())), config()).run(listOf(ChatMessage.user("go")), FixedContext()).toList()
        assertEquals("Stopped after 5 steps without finishing.", assertIs<AgentEvent.Done>(a.last()).finalText)

        val busy = StepLimitEngine { _, _ -> throw LlmException("busy", retryable = true, statusCode = 503) }
        val b = Agent(busy, ToolRegistry(listOf(EchoTool())), config().copy(maxRetries = 1, retryDelayMs = 1))
            .run(listOf(ChatMessage.user("go")), FixedContext()).toList()
        val done = assertIs<AgentEvent.Done>(b.last())
        assertEquals(StopReason.StepLimit, done.reason)
        assertEquals("Stopped after 5 steps without finishing.", done.finalText)
        assertEquals(2, busy.wrapUps, "one try and one retry, no plain-text attempt for a transient failure")
    }

    @Test
    fun wrapUpCanBeTurnedOff() = runTest {
        val engine = StepLimitEngine { _, _ -> error("no wrap-up expected") }
        val events = Agent(engine, ToolRegistry(listOf(EchoTool())), config().copy(wrapUpPrompt = null))
            .run(listOf(ChatMessage.user("go")), FixedContext()).toList()
        assertEquals(5, engine.requests.size)
        assertEquals(StopReason.StepLimit, assertIs<AgentEvent.Done>(events.last()).reason)
        assertNull(events.filterIsInstance<AgentEvent.Appended>().firstOrNull { it.message.text.endsWith(Agent.WRAP_UP) })
    }

    /** Calls echo with the texts of [pattern] in turn while tools are offered; answers the wrap-up with "wrapped". */
    private class PatternEngine(private val pattern: List<String>) : LlmEngine {
        val requests = mutableListOf<LlmRequest>()
        override val id = "pattern"
        override val capabilities = EngineCapabilities(nativeToolCalls = true, vision = false, contextWindow = 8000, onDevice = false)

        override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
            requests += request
            if (request.tools.isEmpty()) {
                emit(LlmEvent.TextDelta("wrapped"))
                emit(LlmEvent.Finished(FinishReason.Stop))
                return@flow
            }
            val text = pattern[(requests.size - 1) % pattern.size]
            emit(LlmEvent.ToolCallReady(ToolCall("c${requests.size}", "echo", """{"text":"$text"}""")))
            emit(LlmEvent.Finished(FinishReason.ToolCalls))
        }
    }

    @Test
    fun aRunThatKeepsRepeatingItselfIsStoppedWithAWrapUp() = runTest {
        val engine = PatternEngine(listOf("same"))
        val tool = EchoTool()
        val events = Agent(engine, ToolRegistry(listOf(tool)), config().copy(maxSteps = 400)).run(listOf(ChatMessage.user("go")), FixedContext()).toList()

        val done = assertIs<AgentEvent.Done>(events.last())
        assertEquals(StopReason.Stuck, done.reason)
        assertEquals("wrapped", done.finalText)
        assertEquals(9, done.steps, "one fresh step, then eight that brought nothing new")
        assertEquals(9, tool.runs)
        val last = engine.requests.last()
        assertTrue(last.tools.isEmpty())
        assertEquals(Agent.STUCK_REASON + " " + Agent.WRAP_UP, last.messages.last().text)
    }

    @Test
    fun circlingBetweenTwoActionsIsStuckToo() = runTest {
        val engine = PatternEngine(listOf("scroll down", "scroll up"))
        val events = Agent(engine, ToolRegistry(listOf(EchoTool())), config().copy(maxSteps = 400)).run(listOf(ChatMessage.user("go")), FixedContext()).toList()
        val done = assertIs<AgentEvent.Done>(events.last())
        assertEquals(StopReason.Stuck, done.reason)
        assertEquals(10, done.steps)
        // A nudge came first, before the run was stopped.
        assertTrue(engine.requests.any { r -> r.messages.any { it.text.contains("repeated earlier actions and returned nothing new") } })
    }

    @Test
    fun aLongRunThatKeepsMakingProgressIsNotStopped() = runTest {
        val engine = PatternEngine((1..200).map { "step $it" })
        val tool = EchoTool()
        val events = Agent(engine, ToolRegistry(listOf(tool)), config().copy(maxSteps = 120)).run(listOf(ChatMessage.user("go")), FixedContext()).toList()
        val done = assertIs<AgentEvent.Done>(events.last())
        assertEquals(StopReason.StepLimit, done.reason)
        assertEquals(120, tool.runs)
        assertEquals("wrapped", done.finalText)
    }

    @Test
    fun sameCallWithChangingResultsIsProgress() = runTest {
        // A screen read whose content changes each time (the user is scrolling, a page is loading) is not stuck.
        var n = 0
        val changing = object : Tool {
            override val risk = Risk.Read
            override val spec = ToolSpec("echo", "Read", schema { string("text", "x") })
            override suspend fun run(args: JsonObject, ctx: ToolContext) = ToolResult.ok("screen ${n++}")
        }
        val engine = PatternEngine(listOf("read"))
        val events = Agent(engine, ToolRegistry(listOf(changing)), config().copy(maxSteps = 30)).run(listOf(ChatMessage.user("go")), FixedContext()).toList()
        assertEquals(StopReason.StepLimit, assertIs<AgentEvent.Done>(events.last()).reason)
    }

    @Test
    fun stuckDetectionCanBeTurnedOff() = runTest {
        val engine = PatternEngine(listOf("same"))
        val events = Agent(engine, ToolRegistry(listOf(EchoTool())), config().copy(maxSteps = 12, stopWhenStuck = false))
            .run(listOf(ChatMessage.user("go")), FixedContext()).toList()
        assertEquals(StopReason.StepLimit, assertIs<AgentEvent.Done>(events.last()).reason)
    }

    @Test
    fun approvalFollowsTheRiskOfEachCall() = runTest {
        class Shell : Tool, atom.core.tool.PerCallRisk {
            var runs = 0
            override val risk = Risk.Sensitive
            override val spec = ToolSpec("echo", "Run", schema { string("text", "command", required = true) })
            override fun riskFor(args: JsonObject): Risk =
                atom.core.tool.ShellRisk.classify(args.string("text"), atom.core.tool.ShellScope.distro("atom.app", privileged = false))
            override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
                runs++
                return ToolResult.ok("ran")
            }
        }
        val engine = ScriptedEngine(
            listOf(
                listOf(call("ls -la /workspace"), LlmEvent.Finished(FinishReason.ToolCalls)),
                listOf(call("rm -rf /sdcard"), LlmEvent.Finished(FinishReason.ToolCalls)),
                listOf(LlmEvent.TextDelta("done"), LlmEvent.Finished(FinishReason.Stop)),
            ),
        )
        val shell = Shell()
        val ctx = FixedContext(approve = false)
        Agent(engine, ToolRegistry(listOf(shell)), config()).run(listOf(ChatMessage.user("go")), ctx).toList()
        assertEquals(1, ctx.asked, "only the dangerous command asked")
        assertEquals(1, shell.runs, "the declined one did not run")
    }
}
