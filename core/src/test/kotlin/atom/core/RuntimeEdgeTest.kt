package atom.core

import atom.core.agent.Agent
import atom.core.agent.AgentConfig
import atom.core.agent.AgentEvent
import atom.core.agent.ContextPolicy
import atom.core.agent.Scrubber
import atom.core.llm.ChatMessage
import atom.core.llm.ContentPart
import atom.core.llm.EngineCapabilities
import atom.core.llm.FinishReason
import atom.core.llm.LlmEngine
import atom.core.llm.LlmEvent
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Edge cases of the runtime pieces the task runner relies on. */
class RuntimeEdgeTest {
    private class Scripted(val script: (Int) -> List<LlmEvent>) : LlmEngine {
        var calls = 0
        override val id = "scripted"
        override val capabilities = EngineCapabilities(nativeToolCalls = true, vision = false, contextWindow = 8000, onDevice = false)
        override fun stream(request: LlmRequest): Flow<LlmEvent> = flow { script(calls++).forEach { emit(it) } }
    }

    private class SlowTool(name: String, private val takesMs: Long, override val timeoutMs: Long? = null) : Tool {
        override val spec = ToolSpec(name, name, schema())
        override val risk = Risk.Read
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            delay(takesMs)
            return ToolResult.ok("finished")
        }
    }

    private fun callThenAnswer(tool: String) = Scripted { n ->
        if (n == 0) {
            listOf(LlmEvent.ToolCallReady(ToolCall("c1", tool, "{}")), LlmEvent.Finished(FinishReason.ToolCalls))
        } else {
            listOf(LlmEvent.TextDelta("done"), LlmEvent.Finished(FinishReason.Stop))
        }
    }

    // ------------------------------------------------------------------ Per-tool timeout

    @Test
    fun aToolsOwnTimeoutOverridesTheRunDefault() = runTest {
        val config = AgentConfig("m", "sys", toolTimeoutMs = 60_000)
        val long = SlowTool("spawn_subagent", takesMs = 5 * 60_000, timeoutMs = 15 * 60_000)
        val events = Agent(callThenAnswer("spawn_subagent"), ToolRegistry(listOf(long)), config).run(listOf(ChatMessage.user("go")), FixedContext()).toList()
        val result = events.filterIsInstance<AgentEvent.ToolFinished>().single().result
        assertFalse(result.isError)
        assertEquals("finished", result.text)
    }

    @Test
    fun toolsWithoutTheirOwnTimeoutAreCutAtTheDefault() = runTest {
        val config = AgentConfig("m", "sys", toolTimeoutMs = 60_000)
        val slow = SlowTool("slow", takesMs = 90_000)
        val events = Agent(callThenAnswer("slow"), ToolRegistry(listOf(slow)), config).run(listOf(ChatMessage.user("go")), FixedContext()).toList()
        val result = events.filterIsInstance<AgentEvent.ToolFinished>().single().result
        assertTrue(result.isError)
        assertTrue(result.text.contains("timed out after 60s"))
    }

    // ------------------------------------------------------------------ Waiting for an unlocked screen

    private class ScreenTool(private val takesMs: Long = 0) : Tool {
        override val spec = ToolSpec("read_screen", "read", schema())
        override val risk = Risk.Read
        override val needsUnlockedScreen = true
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            delay(takesMs)
            return ToolResult.ok("screen read")
        }
    }

    /** The wait for the user to unlock is not part of the tool's timeout: no misleading "timed out" afterwards. */
    @Test
    fun waitingForTheScreenDoesNotCountAgainstTheToolTimeout() = runTest {
        val waitedFor = mutableListOf<String>()
        atom.core.tool.PhoneScreen.gate = atom.core.tool.PhoneScreen.Gate { id ->
            waitedFor += id
            delay(5 * 60_000) // the user unlocks after five minutes
            true
        }
        try {
            val config = AgentConfig("m", "sys", toolTimeoutMs = 60_000)
            val events = Agent(callThenAnswer("read_screen"), ToolRegistry(listOf(ScreenTool())), config)
                .run(listOf(ChatMessage.user("go")), FixedContext()).toList()
            val result = events.filterIsInstance<AgentEvent.ToolFinished>().single().result
            assertFalse(result.isError, result.text)
            assertEquals("screen read", result.text)
            assertEquals(1, waitedFor.size)
        } finally {
            atom.core.tool.PhoneScreen.gate = null
        }
    }

    @Test
    fun aScreenThatStaysLockedEndsTheCallWithAClearReason() = runTest {
        atom.core.tool.PhoneScreen.gate = atom.core.tool.PhoneScreen.Gate { false }
        try {
            val events = Agent(callThenAnswer("read_screen"), ToolRegistry(listOf(ScreenTool())), AgentConfig("m", "sys"))
                .run(listOf(ChatMessage.user("go")), FixedContext()).toList()
            val result = events.filterIsInstance<AgentEvent.ToolFinished>().single().result
            assertTrue(result.isError)
            assertEquals(atom.core.tool.PhoneScreen.LOCKED_TOO_LONG, result.text)
        } finally {
            atom.core.tool.PhoneScreen.gate = null
        }
    }

    @Test
    fun toolsThatDoNotNeedTheScreenNeverWait() = runTest {
        var asked = false
        atom.core.tool.PhoneScreen.gate = atom.core.tool.PhoneScreen.Gate { asked = true; false }
        try {
            val events = Agent(callThenAnswer("slow"), ToolRegistry(listOf(SlowTool("slow", takesMs = 10))), AgentConfig("m", "sys"))
                .run(listOf(ChatMessage.user("go")), FixedContext()).toList()
            assertFalse(events.filterIsInstance<AgentEvent.ToolFinished>().single().result.isError)
            assertFalse(asked)
        } finally {
            atom.core.tool.PhoneScreen.gate = null
        }
    }

    // ------------------------------------------------------------------ Retry policy

    @Test
    fun retryDelaysAreTwoFourEightSeconds() {
        val c = AgentConfig("m", "sys")
        assertEquals(listOf(2_000L, 4_000L, 8_000L), (0 until c.maxRetries).map(c::retryDelay))
        assertEquals(3, c.maxRetries)
    }

    // ------------------------------------------------------------------ Queue and steering

    @Test
    fun sendNowSteersTextButNeverImageMessages() {
        val q = SteerQueue()
        val text = q.add("also check the inbox")
        val image = q.add("what is this", attachments = listOf("/x.jpg"), hasImages = true)
        assertTrue(q.sendNow(text.id))
        assertFalse(q.sendNow(text.id), "already steering")
        assertFalse(q.sendNow(image.id))
        assertEquals(listOf("also check the inbox"), q.drainSteering().map { it.text })
        assertEquals(listOf(image.id), q.items.value.map { it.id })
        assertEquals(image.id, q.pollNext()?.id)
        assertNull(q.pollNext())
    }

    @Test
    fun anImageMessageAddedAsSteerStillWaitsForTheTurn() {
        val q = SteerQueue()
        q.add("look", hasImages = true, steer = true)
        assertTrue(q.drainSteering().isEmpty())
        assertEquals(1, q.size)
    }

    @Test
    fun queuedTurnsStartInTheOrderTheyWereSent() {
        val q = SteerQueue()
        q.add("one")
        q.add("two")
        q.add("three")
        assertEquals(listOf("one", "two", "three"), generateSequence { q.pollNext()?.text }.toList())
    }

    // ------------------------------------------------------------------ Phone lease

    @Test
    fun aCancelledWaiterLeavesTheLineAndTheNextOneGetsControl() = runTest {
        val lease = PhoneLease()
        lease.acquire("a")
        val b = launch { lease.acquire("b") }
        var cGot = false
        launch {
            lease.acquire("c")
            cGot = true
        }
        runCurrent()
        assertEquals(listOf("b", "c"), lease.state.value.waiting)
        b.cancel()
        runCurrent()
        assertEquals(listOf("c"), lease.state.value.waiting)
        lease.release("a")
        advanceUntilIdle()
        assertTrue(cGot)
        assertEquals("c", lease.state.value.holder)
        assertTrue(lease.state.value.waiting.isEmpty())
    }

    @Test
    fun pausingKeepsOthersWaitingUntilTheHolderReleases() = runTest {
        val lease = PhoneLease()
        lease.acquire("a")
        lease.pause("a")
        assertTrue(lease.state.value.userHasControl)
        assertFalse(lease.holds("a"))
        var bGot = false
        launch {
            lease.acquire("b")
            bGot = true
        }
        runCurrent()
        assertFalse(bGot)
        lease.resume("a")
        assertTrue(lease.holds("a"))
        lease.release("a")
        advanceUntilIdle()
        assertTrue(bGot)
    }

    // ------------------------------------------------------------------ Scrubbing

    @Test
    fun scrubbingDropsTextAndImagesOfToolResultsOnly() {
        val call = ToolCall("1", "read_notifications", "{}")
        val result = ChatMessage.toolResult(call, listOf(ContentPart.Text("Code 482913"), ContentPart.Image("image/png", "AAAA")))
        val scrubbed = Scrubber.scrub(result)
        assertTrue(Scrubber.isScrubbed(scrubbed))
        assertFalse("482913" in scrubbed.text)
        assertTrue(scrubbed.parts.none { it is ContentPart.Image })
        assertEquals("read_notifications", scrubbed.toolName)
        assertEquals("1", scrubbed.toolCallId)
        val user = ChatMessage.user("my code is 482913")
        assertEquals(user, Scrubber.scrub(user))
        assertEquals(Role.Tool, scrubbed.role)
    }

    // ------------------------------------------------------------------ Compaction trigger

    @Test
    fun autoCompactionOffNeverCompactsEvenWhenFull() = runTest {
        val big = "x".repeat(4_000)
        val history = buildList {
            repeat(6) {
                add(ChatMessage.user("q$it $big"))
                add(ChatMessage.assistant("a$it $big"))
            }
            add(ChatMessage.user("latest"))
        }
        val engine = Scripted { listOf(LlmEvent.TextDelta("ok"), LlmEvent.Finished(FinishReason.Stop)) }
        val config = AgentConfig("m", "sys", contextWindow = 8_000, context = ContextPolicy(budgetTokens = 8_000), autoCompact = false)
        val events = Agent(engine, ToolRegistry(emptyList()), config).run(history, FixedContext()).toList()
        assertTrue(events.none { it is AgentEvent.Compacting })
        assertTrue(events.filterIsInstance<AgentEvent.ContextUsage>().all { it.windowTokens == 8_000 })
    }

    @Test
    fun noCompactionBelowTheThreshold() = runTest {
        // About 60% of an 8k window: under the 85% trigger.
        val history = listOf(ChatMessage.user("z".repeat(4 * 4_800)))
        val engine = Scripted { listOf(LlmEvent.TextDelta("ok"), LlmEvent.Finished(FinishReason.Stop)) }
        val config = AgentConfig("m", "sys", contextWindow = 8_000, context = ContextPolicy(budgetTokens = 8_000))
        val events = Agent(engine, ToolRegistry(emptyList()), config).run(history, FixedContext()).toList()
        assertTrue(events.none { it is AgentEvent.Compacting })
    }
}
