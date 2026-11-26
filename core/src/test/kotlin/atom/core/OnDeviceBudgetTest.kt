package atom.core

import atom.core.agent.Agent
import atom.core.agent.AgentConfig
import atom.core.agent.ContextPacker
import atom.core.agent.ContextPolicy
import atom.core.llm.ChatMessage
import atom.core.llm.ContentPart
import atom.core.llm.FinishReason
import atom.core.llm.LlmEvent
import atom.core.llm.ToolCall
import atom.core.tool.ToolRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A 4096-token on-device model (Qwen3 0.6B) must get prompts that fit: messages + tool descriptions + answer. */
class OnDeviceBudgetTest {
    private val local = AgentConfig(
        model = "m",
        systemPrompt = "s",
        maxOutputTokens = 512,
        context = ContextPolicy(budgetTokens = 3_200, sharedWindow = 4_096),
    )

    @Test
    fun messagesGetWhatTheToolsAndTheAnswerLeave() {
        assertEquals(4_096 - 512 - 2_400 - ContextPolicy.SHARED_MARGIN, local.packingFor(2_400).budgetTokens)
        assertEquals(3_200, local.packingFor(0).budgetTokens) // never above the deliberate cap
        assertEquals(ContextPolicy.SHARED_MIN_BUDGET, local.packingFor(5_000).budgetTokens)
    }

    @Test
    fun withoutASharedWindowNothingChanges() {
        val cloud = AgentConfig(model = "m", systemPrompt = "s", contextWindow = 200_000)
        assertEquals(cloud.packing, cloud.packingFor(9_000))
    }

    @Test
    fun everyRequestFitsTheWindow() = runTest {
        // Ten steps of a browser task, each with a 1500-character page, and 2400 tokens of tool descriptions.
        val tools = (1..20).map { i ->
            atom.core.llm.ToolSpec("tool_$i", "d".repeat(400), atom.core.tool.schema { string("x", "y".repeat(80)) })
        }
        val toolTokens = ContextPacker.estimateTools(tools)
        val history = buildList {
            add(ChatMessage.user("search the news and summarize it"))
            repeat(10) { i ->
                val call = ToolCall("c$i", "tool_1", "{}")
                add(ChatMessage.assistant("", listOf(call)))
                add(ChatMessage.toolResult(call, listOf(ContentPart.Text("p".repeat(1_500)))))
            }
        }
        val engine = ScriptedEngine(listOf(listOf(LlmEvent.TextDelta("done"), LlmEvent.Finished(FinishReason.Stop))))
        val registry = ToolRegistry(tools.map { s -> object : atom.core.tool.Tool by EchoTool() { override val spec = s } })
        Agent(engine, registry, local).run(history, FixedContext()).toList()
        val sent = engine.requests.single()
        val prompt = sent.messages.sumOf(ContextPacker::estimate) + toolTokens + local.maxOutputTokens
        assertTrue(prompt <= 4_096 - ContextPolicy.SHARED_MARGIN + 200, "prompt + tools + answer = $prompt tokens")
        assertEquals("search the news and summarize it", sent.messages[1].text)
    }
}
