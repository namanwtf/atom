package atom.core

import atom.core.agent.Agent
import atom.core.agent.AgentConfig
import atom.core.agent.AgentEvent
import atom.core.agent.Compactor
import atom.core.agent.ContextPolicy
import atom.core.agent.RunControl
import atom.core.agent.Scrubber
import atom.core.llm.ChatMessage
import atom.core.llm.EngineCapabilities
import atom.core.llm.FinishReason
import atom.core.llm.LlmEngine
import atom.core.llm.LlmEvent
import atom.core.llm.LlmException
import atom.core.llm.LlmRequest
import atom.core.llm.Role
import atom.core.llm.ToolCall
import atom.core.llm.ToolSpec
import atom.core.runtime.PhoneLease
import atom.core.runtime.SteerQueue
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolContext
import atom.core.tool.ToolRegistry
import atom.core.tool.ToolResult
import atom.core.tool.schema
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class ProgrammableEngine(val script: (LlmRequest, Int) -> List<LlmEvent>) : LlmEngine {
    val requests = mutableListOf<LlmRequest>()
    override val id = "programmable"
    override val capabilities = EngineCapabilities(nativeToolCalls = true, vision = false, contextWindow = 8000, onDevice = false)
    override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
        requests += request
        script(request, requests.size - 1).forEach { emit(it) }
    }
}

private class UiTool(override val spec: ToolSpec, private val result: ToolResult) : Tool {
    override val risk = Risk.Act
    override val usesPhoneUi = true
    override suspend fun run(args: JsonObject, ctx: ToolContext) = result
}

private fun spec(name: String) = ToolSpec(name, name, schema())
private fun callEvent(name: String, id: String = "c1") = LlmEvent.ToolCallReady(ToolCall(id, name, "{}"))
private fun answer(text: String) = listOf(LlmEvent.TextDelta(text), LlmEvent.Finished(FinishReason.Stop))

class RuntimeTest {
    @Test
    fun steeringIsDeliveredBetweenStepsInOrderWithoutCancelling() = runTest {
        val queue = SteerQueue()
        val engine = ProgrammableEngine { _, n ->
            when (n) {
                0 -> listOf(callEvent("tap"), LlmEvent.Finished(FinishReason.ToolCalls))
                else -> answer("ok $n")
            }
        }
        val control = object : RunControl {
            override fun drainSteering() = queue.drainSteering().map { ChatMessage.user(it.text) }
        }
        val tool = object : Tool {
            override val spec = spec("tap")
            override val risk = Risk.Act
            override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
                queue.add("first", steer = true)
                queue.add("later, after the task")
                queue.add("second", steer = true)
                return ToolResult.ok("tapped")
            }
        }
        val events = Agent(engine, ToolRegistry(listOf(tool)), AgentConfig("m", "sys")).run(listOf(ChatMessage.user("go")), FixedContext(), control).toList()

        val steered = events.filterIsInstance<AgentEvent.Appended>().filter { it.info.steering }.map { it.message.text }
        assertEquals(listOf("first", "second"), steered)
        val secondRequest = engine.requests[1].messages.map { it.text }
        assertEquals(listOf("tapped", "first", "second"), secondRequest.takeLast(3))
        assertIs<AgentEvent.Done>(events.last())
        assertEquals(listOf("later, after the task"), queue.items.value.map { it.text })
    }

    @Test
    fun steeringArrivingWithTheFinalAnswerKeepsTheRunGoing() = runTest {
        val queue = SteerQueue()
        val engine = ProgrammableEngine { _, n ->
            if (n == 0) queue.add("also do this", steer = true)
            answer("answer $n")
        }
        val control = object : RunControl {
            override fun drainSteering() = queue.drainSteering().map { ChatMessage.user(it.text) }
        }
        val events = Agent(engine, ToolRegistry(emptyList()), AgentConfig("m", "sys")).run(listOf(ChatMessage.user("go")), FixedContext(), control).toList()
        assertEquals(2, engine.requests.size)
        assertEquals("answer 1", assertIs<AgentEvent.Done>(events.last()).finalText)
    }

    @Test
    fun imageMessagesNeverSteer() {
        val queue = SteerQueue()
        val img = queue.add("look at this", hasImages = true, steer = true)
        assertFalse(img.steer)
        assertFalse(queue.sendNow(img.id))
        val text = queue.add("plain")
        assertTrue(queue.sendNow(text.id))
        assertEquals(listOf("plain"), queue.drainSteering().map { it.text })
        assertEquals("look at this", queue.pollNext()?.text)
        assertNull(queue.pollNext())
    }

    @Test
    fun pauseHoldsTheRunAtTheNextCheckpoint() = runTest {
        val gate = CompletableDeferred<Unit>()
        var checkpoints = 0
        val engine = ProgrammableEngine { _, n ->
            if (n == 0) listOf(callEvent("tap"), LlmEvent.Finished(FinishReason.ToolCalls)) else answer("done")
        }
        val tool = UiTool(spec("tap"), ToolResult.ok("tapped"))
        val control = object : RunControl {
            override suspend fun checkpoint() {
                checkpoints++
                if (checkpoints == 2) gate.await()
            }
        }
        val run = async { Agent(engine, ToolRegistry(listOf(tool)), AgentConfig("m", "sys")).run(listOf(ChatMessage.user("go")), FixedContext(), control).toList() }
        advanceUntilIdle()
        assertEquals(1, engine.requests.size)
        assertFalse(run.isCompleted)
        gate.complete(Unit)
        val events = run.await()
        assertIs<AgentEvent.Done>(events.last())
        assertEquals(2, engine.requests.size)
    }

    @Test
    fun onlyPhoneUiToolsWaitForTheLease() = runTest {
        val lease = PhoneLease()
        lease.acquire("other")
        val engine = ProgrammableEngine { _, n ->
            when (n) {
                0 -> listOf(callEvent("read_file"), LlmEvent.Finished(FinishReason.ToolCalls))
                1 -> listOf(callEvent("tap"), LlmEvent.Finished(FinishReason.ToolCalls))
                else -> answer("done")
            }
        }
        val plain = object : Tool {
            override val spec = spec("read_file")
            override val risk = Risk.Read
            override suspend fun run(args: JsonObject, ctx: ToolContext) = ToolResult.ok("file")
        }
        val ui = UiTool(spec("tap"), ToolResult.ok("tapped"))
        val control = object : RunControl {
            override suspend fun acquirePhone() = lease.acquire("me")
        }
        val run = async { Agent(engine, ToolRegistry(listOf(plain, ui)), AgentConfig("m", "sys")).run(listOf(ChatMessage.user("go")), FixedContext(), control).toList() }
        advanceUntilIdle()
        assertEquals(2, engine.requests.size, "the non-UI tool ran and the next step started")
        assertEquals(listOf("me"), lease.state.value.waiting)
        lease.release("other")
        val events = run.await()
        assertIs<AgentEvent.Done>(events.last())
        assertTrue(lease.holds("me"))
    }

    @Test
    fun leaseIsFifoAndPauseGivesTheUserControl() = runTest {
        val lease = PhoneLease()
        lease.acquire("a")
        val order = mutableListOf<String>()
        val b = launch { lease.acquire("b"); order += "b" }
        val c = launch { lease.acquire("c"); order += "c" }
        advanceUntilIdle()
        assertEquals(listOf("b", "c"), lease.state.value.waiting)

        lease.pause("a")
        assertTrue(lease.state.value.userHasControl)
        assertFalse(lease.holds("a"))
        val aAgain = launch { lease.acquire("a"); order += "a" }
        advanceUntilIdle()
        assertTrue(order.isEmpty())
        lease.resume("a")
        advanceUntilIdle()
        assertEquals(listOf("a"), order)

        lease.release("a")
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), order)
        c.cancel()
        advanceUntilIdle()
        lease.release("b")
        assertNull(lease.state.value.holder)
        listOf(b, aAgain).forEach { it.join() }
    }

    @Test
    fun sensitiveResultsAreFlaggedAndScrubbed() = runTest {
        val engine = ProgrammableEngine { _, n ->
            if (n == 0) listOf(callEvent("otp"), LlmEvent.Finished(FinishReason.ToolCalls)) else answer("Your code is ready")
        }
        val otp = object : Tool {
            override val spec = spec("otp")
            override val risk = Risk.Read
            override suspend fun run(args: JsonObject, ctx: ToolContext) = ToolResult.sensitive("Code: 482913")
        }
        val events = Agent(engine, ToolRegistry(listOf(otp)), AgentConfig("m", "sys")).run(listOf(ChatMessage.user("go")), FixedContext()).toList()
        val appended = events.filterIsInstance<AgentEvent.Appended>().single { it.message.role == Role.Tool }
        assertTrue(appended.info.sensitive)
        assertTrue(engine.requests[1].messages.any { "482913" in it.text }, "the model sees it within the turn")
        val saved = Scrubber.scrub(appended.message)
        assertFalse("482913" in saved.text)
        assertTrue(Scrubber.isScrubbed(saved))
        assertEquals("otp", saved.toolName)
    }

    @Test
    fun retriesThreeTimesWithGrowingDelaysAndDiscardsPartialText() = runTest {
        var calls = 0
        val engine = object : LlmEngine {
            override val id = "flaky"
            override val capabilities = EngineCapabilities(true, false, 8000, false)
            override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
                calls++
                if (calls <= 3) {
                    emit(LlmEvent.TextDelta("partial"))
                    throw LlmException("connection reset", retryable = true)
                }
                answer("whole").forEach { emit(it) }
            }
        }
        val start = currentTime
        val events = Agent(engine, ToolRegistry(emptyList()), AgentConfig("m", "sys")).run(listOf(ChatMessage.user("go")), FixedContext()).toList()
        val retries = events.filterIsInstance<AgentEvent.Retrying>()
        assertEquals(listOf(2_000L, 4_000L, 8_000L), retries.map { it.delayMs })
        assertTrue(retries.all { it.discardPartial })
        assertEquals(14_000L, currentTime - start)
        assertEquals("whole", assertIs<AgentEvent.Done>(events.last()).finalText)
    }

    @Test
    fun givesUpAfterRetriesWithAPlainMessage() = runTest {
        val engine = object : LlmEngine {
            override val id = "down"
            override val capabilities = EngineCapabilities(true, false, 8000, false)
            override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
                throw LlmException("Provider request failed (HTTP 429): slow down", retryable = true, statusCode = 429)
            }
        }
        val events = Agent(engine, ToolRegistry(emptyList()), AgentConfig("m", "sys")).run(listOf(ChatMessage.user("go")), FixedContext()).toList()
        assertEquals(3, events.filterIsInstance<AgentEvent.Retrying>().size)
        val failed = assertIs<AgentEvent.Failed>(events.last())
        assertTrue(failed.message.contains("rate limiting"))
    }

    @Test
    fun compactsAutomaticallyAroundEightyFivePercentOfTheWindow() = runTest {
        val big = "x".repeat(4_000)
        val history = buildList {
            repeat(6) {
                add(ChatMessage.user("question $it $big"))
                add(ChatMessage.assistant("answer $it $big"))
            }
            add(ChatMessage.user("latest question"))
        }
        val engine = ProgrammableEngine { request, _ ->
            if (request.tools.isEmpty() && request.messages.first().text.contains("compact")) {
                answer("SUMMARY: six questions were answered")
            } else {
                answer("fine")
            }
        }
        val config = AgentConfig("m", "sys", contextWindow = 8_000, context = ContextPolicy(budgetTokens = 8_000))
        val events = Agent(engine, ToolRegistry(emptyList()), config).run(history, FixedContext()).toList()

        val compacted = events.filterIsInstance<AgentEvent.Compacted>().single()
        assertTrue(compacted.summarizedCount > 0)
        assertTrue(compacted.tokensAfter < compacted.tokensBefore)
        assertTrue(Compactor.isSummary(compacted.summary))
        val answerRequest = engine.requests.last()
        assertTrue(answerRequest.messages[1].text.startsWith(Compactor.SUMMARY_HEADER))
        assertEquals("latest question", answerRequest.messages.last().text)
    }

    @Test
    fun noCompactionWhenTheWindowIsUnknownOrRoomy() = runTest {
        val history = listOf(ChatMessage.user("hi " + "y".repeat(30_000)))
        val engine = ProgrammableEngine { _, _ -> answer("ok") }
        val unknown = Agent(engine, ToolRegistry(emptyList()), AgentConfig("m", "sys", contextWindow = null)).run(history, FixedContext()).toList()
        assertTrue(unknown.none { it is AgentEvent.Compacting })
        val roomy = Agent(engine, ToolRegistry(emptyList()), AgentConfig("m", "sys", contextWindow = 200_000, context = ContextPolicy(budgetTokens = 200_000)))
            .run(history, FixedContext()).toList()
        assertTrue(roomy.none { it is AgentEvent.Compacting })
    }

    @Test
    fun cutNeverSplitsAToolCallFromItsResult() {
        val call = ToolCall("1", "tap", "{}")
        val convo = listOf(
            ChatMessage.user("a".repeat(400)),
            ChatMessage.assistant("", listOf(call)),
            ChatMessage.toolResult(call, listOf(atom.core.llm.ContentPart.Text("r".repeat(400)))),
            ChatMessage.assistant("b".repeat(400), listOf(call)),
            ChatMessage.toolResult(call, listOf(atom.core.llm.ContentPart.Text("s".repeat(40)))),
        )
        val cut = Compactor.cutIndex(convo, keepRecentTokens = 50)
        assertEquals(3, cut)
        assertTrue(convo[cut].role != Role.Tool)
    }
}
