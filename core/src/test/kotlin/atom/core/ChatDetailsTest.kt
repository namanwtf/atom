package atom.core

import atom.core.agent.Agent
import atom.core.agent.AgentConfig
import atom.core.agent.AgentEvent
import atom.core.agent.ModelCallStats
import atom.core.agent.SessionStats
import atom.core.llm.ChatMessage
import atom.core.llm.FinishReason
import atom.core.llm.HostedToolKind
import atom.core.llm.LlmEvent
import atom.core.runtime.QueuedMessage
import atom.core.runtime.SteerQueue
import atom.core.runtime.interrupting
import atom.core.tool.ToolRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatDetailsTest {
    @Test
    fun queuedPlaceholderCountsImagesThenFiles() {
        val q = SteerQueue()
        val images = q.add("", listOf("/a.jpg", "/b.jpg", "/doc.pdf"), hasImages = true, imageCount = 2)
        assertEquals(QueuedMessage.Placeholder(images = 2), images.placeholder)
        assertEquals(1, images.fileCount)
        val files = q.add("  ", listOf("/x", "/y"))
        assertEquals(QueuedMessage.Placeholder(files = 2), files.placeholder)
        assertNull(q.add("hello", listOf("/x")).placeholder)
        assertNull(q.add("").placeholder)
    }

    @Test
    fun imageCountIsClampedToTheAttachments() {
        val q = SteerQueue()
        assertEquals(1, q.add("", listOf("/a.jpg"), hasImages = true, imageCount = 5).imageCount)
        assertEquals(0, q.add("", listOf("/a"), hasImages = false, imageCount = 3).imageCount)
    }

    @Test
    fun interruptingOnlyWhileASteerWaitsOnARunningTask() {
        val q = SteerQueue()
        val m = q.add("later")
        assertFalse(q.items.value.interrupting(running = true))
        q.sendNow(m.id)
        assertTrue(q.items.value.interrupting(running = true))
        assertFalse(q.items.value.interrupting(running = false))
    }

    @Test
    fun cacheMissesCountRequestsWithoutCacheReads() {
        val s = SessionStats()
            .withModelCall(ModelCallStats(ttftMs = 100, durationMs = 500, inputTokens = 1000, outputTokens = 10, cacheReadTokens = 800))
            .withModelCall(ModelCallStats(ttftMs = 100, durationMs = 500, inputTokens = 1200, outputTokens = 10, cacheReadTokens = 0, cacheWriteTokens = 1200))
            .withModelCall(ModelCallStats(ttftMs = null, durationMs = 10, inputTokens = 0, outputTokens = 0))
        assertEquals(1, s.cacheMisses)
        assertTrue(s.cacheReported)
        assertFalse(SessionStats().cacheReported)
    }

    @Test
    fun subagentUsageAccumulates() {
        val s = SessionStats().withSubagents(2, 7, 3000, 400).withSubagents(1, 2, 100, 50).withSubagents(0, 9, 9, 9)
        assertEquals(3, s.subagentRuns)
        assertEquals(9, s.subagentToolCalls)
        assertEquals(3100, s.subagentInputTokens)
        assertEquals(450, s.subagentOutputTokens)
    }

    @Test
    fun hostedToolKindsFromWireTypes() {
        assertEquals(HostedToolKind.WebSearch, HostedToolKind.fromWireType("web_search_call"))
        assertEquals(HostedToolKind.FileSearch, HostedToolKind.fromWireType("file_search_call"))
        assertEquals(HostedToolKind.CodeExecution, HostedToolKind.fromWireType("code_interpreter_call"))
        assertEquals(HostedToolKind.ComputerUse, HostedToolKind.fromWireType("computer_call"))
        assertEquals(HostedToolKind.ImageGeneration, HostedToolKind.fromWireType("image_generation_call"))
        assertEquals(HostedToolKind.Mcp, HostedToolKind.fromWireType("mcp_call"))
        assertNull(HostedToolKind.fromWireType("function_call"))
        assertNull(HostedToolKind.fromWireType(null))
    }

    @Test
    fun agentForwardsHostedToolEvents() = runTest {
        val engine = ScriptedEngine(
            listOf(
                listOf(
                    LlmEvent.HostedTool(HostedToolKind.WebSearch, running = true),
                    LlmEvent.HostedTool(HostedToolKind.WebSearch, running = false),
                    LlmEvent.TextDelta("found it"),
                    LlmEvent.Finished(FinishReason.Stop),
                ),
            ),
        )
        val events = Agent(engine, ToolRegistry(emptyList()), AgentConfig(model = "m", systemPrompt = "s", maxSteps = 3))
            .run(listOf(ChatMessage.user("look it up")), FixedContext()).toList()
        val hosted = events.filterIsInstance<AgentEvent.HostedTool>()
        assertEquals(listOf(true, false), hosted.map { it.running })
        assertTrue(hosted.all { it.kind == HostedToolKind.WebSearch })
        assertEquals("found it", (events.last() as AgentEvent.Done).finalText)
    }

    @Test
    fun hostedCallsKeepOneSlotPerCall() {
        val calls = atom.core.agent.HostedCalls()
        assertEquals(HostedToolKind.WebSearch, calls.update(HostedToolKind.WebSearch, started = true, callId = "a"))
        assertEquals(HostedToolKind.CodeExecution, calls.update(HostedToolKind.CodeExecution, started = true, callId = "b"))
        // The first call finishing leaves the second on screen.
        assertEquals(HostedToolKind.CodeExecution, calls.update(HostedToolKind.WebSearch, started = false, callId = "a"))
        assertNull(calls.update(HostedToolKind.CodeExecution, started = false, callId = "b"))
        // Two searches at once: the label stays until both are done.
        calls.update(HostedToolKind.WebSearch, started = true, callId = "c")
        calls.update(HostedToolKind.WebSearch, started = true, callId = "d")
        assertEquals(HostedToolKind.WebSearch, calls.update(HostedToolKind.WebSearch, started = false, callId = "c"))
        assertNull(calls.update(HostedToolKind.WebSearch, started = false, callId = "d"))
        // Without ids a call has a slot per kind.
        calls.update(HostedToolKind.Mcp, started = true, callId = null)
        assertNull(calls.update(HostedToolKind.Mcp, started = false, callId = null))
    }
}
