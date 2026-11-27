package atom.core

import atom.core.agent.Agent
import atom.core.agent.AgentConfig
import atom.core.agent.AgentEvent
import atom.core.agent.ContextPacker
import atom.core.agent.ContextPolicy
import atom.core.llm.ChatMessage
import atom.core.llm.ContentPart
import atom.core.llm.EngineCapabilities
import atom.core.llm.FinishReason
import atom.core.llm.LlmEngine
import atom.core.llm.LlmEvent
import atom.core.llm.LlmRequest
import atom.core.llm.ToolCall
import atom.core.tool.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reasoning a thinking vendor gets back with each tool-call turn is part of the prompt, so the context budget
 * counts it: a long conversation reopened after a restart (its reasoning read back from storage) still fits.
 */
class ReasoningContextTest {
    private class Engine(val replays: Boolean, val script: (LlmRequest) -> List<LlmEvent>) : LlmEngine {
        val requests = mutableListOf<LlmRequest>()
        override val id = "thinking"
        override val capabilities = EngineCapabilities(nativeToolCalls = true, vision = false, contextWindow = 0, onDevice = false, replaysReasoning = replays)
        override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
            requests += request
            script(request).forEach { emit(it) }
        }
    }

    private val thought = "The user wants this step; checking the result before the next one. ".repeat(90) // ~6 KB

    /** A finished thinking conversation as read back from storage: [turns] tool steps, each with its reasoning. */
    private fun reopened(turns: Int): List<ChatMessage> = buildList {
        add(ChatMessage.user("tidy up my phone"))
        repeat(turns) { i ->
            val call = ToolCall("call$i", "echo", """{"n":$i}""")
            add(ChatMessage.assistant("", listOf(call)).copy(reasoning = thought))
            add(ChatMessage.toolResult(call, listOf(ContentPart.Text("ok $i"))))
        }
        add(ChatMessage.assistant("All done."))
        add(ChatMessage.user("and one more thing"))
    }

    @Test
    fun theEstimateCountsTheReasoningOfToolCallTurnsOnly() {
        val call = ToolCall("c", "echo", "{}")
        val bare = ChatMessage.assistant("", listOf(call))
        assertTrue(ContextPacker.estimate(bare.copy(reasoning = thought)) >= ContextPacker.estimate(bare) + thought.length / 4)
        // A plain answer never sends reasoning back: it does not count.
        val answer = ChatMessage.assistant("done")
        assertEquals(ContextPacker.estimate(answer), ContextPacker.estimate(answer.copy(reasoning = thought)))
    }

    @Test
    fun aLongReopenedThinkingConversationIsPackedUnderTheBudget() {
        val convo = reopened(40)
        val budget = 12_000
        // The text and calls alone would fit; with the reasoning sent back they do not.
        assertTrue(convo.sumOf { ContextPacker.estimate(it.copy(reasoning = null)) } < budget)
        assertTrue(convo.sumOf(ContextPacker::estimate) > budget * 4)
        val packed = ContextPacker.pack("sys", convo, ContextPolicy(budgetTokens = budget))
        assertTrue(packed.sumOf(ContextPacker::estimate) <= budget, "packed to ${packed.sumOf(ContextPacker::estimate)} tokens")
        // The task and the newest steps stay, with their reasoning; older steps make way, never half an exchange.
        assertEquals("tidy up my phone", packed[1].text)
        assertEquals("and one more thing", packed.last().text)
        assertTrue(packed.any { it.toolCalls.any { c -> c.id == "call39" } && it.reasoning == thought })
        val calls = packed.flatMap { m -> m.toolCalls.map { it.id } }
        val results = packed.mapNotNull { it.toolCallId }
        assertEquals(calls, results)
        assertTrue(packed.any { it.midTurn && "removed to save context" in it.text })
    }

    @Test
    fun aLongReopenedThinkingConversationIsCompactedUnderTheWindow() = runTest {
        val engine = Engine(replays = true) { request ->
            if (request.tools.isEmpty() && request.messages.last().text.endsWith("Write the summary now.")) {
                listOf(LlmEvent.TextDelta("SUMMARY of the tidy-up"), LlmEvent.Finished(FinishReason.Stop))
            } else {
                listOf(LlmEvent.TextDelta("done"), LlmEvent.Finished(FinishReason.Stop))
            }
        }
        val config = AgentConfig("m", "sys", contextWindow = 24_000)
        val window = config.usableWindow!!
        val events = Agent(engine, ToolRegistry(emptyList()), config).run(reopened(40), FixedContext()).toList()

        // Measured with the reasoning, the reopened conversation is far over the window: it is compacted first.
        assertTrue(events.any { it is AgentEvent.Compacted }, events.toString())
        val answer = engine.requests.last()
        assertTrue(answer.messages.sumOf(ContextPacker::estimate) <= window, "request of ${answer.messages.sumOf(ContextPacker::estimate)} tokens in a $window window")
        assertTrue(events.last() is AgentEvent.Done)
    }

    @Test
    fun aNewToolCallTurnCarriesItsReasoningOnlyForAnEngineThatSendsItBack() = runTest {
        for (replays in listOf(true, false)) {
            var n = 0
            val engine = Engine(replays) {
                if (n++ == 0) {
                    listOf(LlmEvent.ReasoningDelta("think first"), LlmEvent.ToolCallReady(ToolCall("c1", "echo", "{}")), LlmEvent.Finished(FinishReason.ToolCalls))
                } else {
                    listOf(LlmEvent.TextDelta("done"), LlmEvent.Finished(FinishReason.Stop))
                }
            }
            val echo = object : atom.core.tool.Tool {
                override val spec = atom.core.llm.ToolSpec("echo", "Echo.", atom.core.tool.schema())
                override val risk = atom.core.tool.Risk.Read
                override suspend fun run(args: kotlinx.serialization.json.JsonObject, ctx: atom.core.tool.ToolContext) = atom.core.tool.ToolResult.ok("ok")
            }
            Agent(engine, ToolRegistry(listOf(echo)), AgentConfig("m", "sys", contextWindow = 128_000))
                .run(listOf(ChatMessage.user("go")), FixedContext()).toList()
            val turn = engine.requests.last().messages.single { it.toolCalls.isNotEmpty() }
            assertEquals(if (replays) "think first" else null, turn.reasoning, "replays=$replays")
        }
    }
}
