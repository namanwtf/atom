package atom.core

import atom.core.agent.Agent
import atom.core.agent.AgentConfig
import atom.core.agent.ContextPacker
import atom.core.agent.ContextPolicy
import atom.core.llm.ChatMessage
import atom.core.llm.ContentPart
import atom.core.llm.FinishReason
import atom.core.llm.LlmEvent
import atom.core.llm.PromptedTools
import atom.core.llm.ToolCall
import atom.core.llm.ToolSpec
import atom.core.tool.Tool
import atom.core.tool.ToolRegistry
import atom.core.tool.schema
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A 4096-token on-device model (Qwen3 0.6B) must get prompts that fit: messages + tool descriptions + answer. */
class OnDeviceBudgetTest {
    private fun local(window: Int = 4_096) = AgentConfig(
        model = "m",
        systemPrompt = "s",
        maxOutputTokens = 512,
        context = ContextPolicy(budgetTokens = 3_200, sharedWindow = window),
    )

    private val tools = (1..30).map { i ->
        ToolSpec(
            "tool_$i",
            "Does thing $i on the phone and reports what happened.",
            schema {
                string("query", "The text to look for; see the tool description for what is allowed.", required = true)
                integer("count", "How many results to return; the default is five when it is left out.")
            },
        )
    }

    @Test
    fun messagesGetWhatTheToolsAndTheAnswerLeave() {
        val toolTokens = ContextPacker.estimate(PromptedTools.instructions(tools, compact = true))
        assertEquals(4_096 - 512 - toolTokens - ContextPolicy.SHARED_MARGIN, local().packingFor(tools).budgetTokens)
        assertEquals(3_200, local().packingFor(emptyList()).budgetTokens) // never above the deliberate cap
        assertEquals(ContextPolicy.SHARED_MIN_BUDGET, local(window = 1_500).packingFor(tools).budgetTokens)
    }

    @Test
    fun withoutASharedWindowNothingChanges() {
        val cloud = AgentConfig(model = "m", systemPrompt = "s", contextWindow = 200_000)
        assertEquals(cloud.packing, cloud.packingFor(tools))
    }

    @Test
    fun smallWindowsGetTheCompactToolList() {
        val full = PromptedTools.instructions(tools, compact = false)
        val compact = PromptedTools.instructions(tools, compact = true)
        assertTrue(compact.length * 2 < full.length, "compact ${compact.length} vs full ${full.length} chars")
        assertTrue("Arguments: query (string, required), count (integer)" in compact, compact)
        assertTrue(PromptedTools.compact(4_096))
        assertTrue(!PromptedTools.compact(8_192) && !PromptedTools.compact(0))
        val enumSchema = schema { string("mode", "m", required = true, enum = listOf("up", "down")) }
        assertEquals("mode (one of: up, down, required)", PromptedTools.compactArguments(enumSchema))
        assertEquals("none", PromptedTools.compactArguments(schema()))
    }

    @Test
    fun everyRequestFitsTheWindow() = runTest {
        // Ten steps of a browser task, each with a 1500-character page result.
        val history = buildList {
            add(ChatMessage.user("search the news and summarize it"))
            repeat(10) { i ->
                val call = ToolCall("c$i", "tool_1", "{}")
                add(ChatMessage.assistant("", listOf(call)))
                add(ChatMessage.toolResult(call, listOf(ContentPart.Text("p".repeat(1_500)))))
            }
        }
        val engine = ScriptedEngine(listOf(listOf(LlmEvent.TextDelta("done"), LlmEvent.Finished(FinishReason.Stop))))
        val registry = ToolRegistry(tools.map { s -> object : Tool by EchoTool() { override val spec = s } })
        val config = local()
        Agent(engine, registry, config).run(history, FixedContext()).toList()
        val sent = engine.requests.single()
        val toolTokens = ContextPacker.estimate(PromptedTools.instructions(tools, compact = true))
        val total = sent.messages.sumOf(ContextPacker::estimate) + toolTokens + config.maxOutputTokens
        assertTrue(total <= 4_096 - ContextPolicy.SHARED_MARGIN + 100, "messages + tools + answer = $total tokens")
        assertEquals("search the news and summarize it", sent.messages[1].text)
    }
}
