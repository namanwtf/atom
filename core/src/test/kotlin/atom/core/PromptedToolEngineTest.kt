package atom.core

import atom.core.agent.ContextPacker
import atom.core.agent.ContextPolicy
import atom.core.llm.ChatMessage
import atom.core.llm.ContentPart
import atom.core.llm.FinishReason
import atom.core.llm.LlmEvent
import atom.core.llm.LlmRequest
import atom.core.llm.PromptedToolEngine
import atom.core.llm.Role
import atom.core.llm.ToolCall
import atom.core.tool.schema
import atom.core.llm.ToolSpec
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PromptedToolEngineTest {
    private val tools = listOf(ToolSpec("tap", "Tap", schema { string("id", "node id") }))

    private fun chunked(vararg chunks: String) = ScriptedEngine(
        listOf(chunks.map { LlmEvent.TextDelta(it) } + LlmEvent.Finished(FinishReason.Stop)),
    )

    @Test
    fun parsesCallSplitAcrossChunksAndHidesTag() = runTest {
        val inner = chunked("Opening it. <tool", "_call>{\"name\":\"tap\",", "\"arguments\":{\"id\":\"7\"}}</tool_", "call>")
        val events = PromptedToolEngine(inner).stream(LlmRequest("m", listOf(ChatMessage.user("x")), tools)).toList()

        val text = events.filterIsInstance<LlmEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("Opening it. ", text)
        val call = events.filterIsInstance<LlmEvent.ToolCallReady>().single().call
        assertEquals("tap", call.name)
        assertEquals("""{"id":"7"}""", call.argumentsJson)
        assertEquals(FinishReason.ToolCalls, (events.last() as LlmEvent.Finished).reason)
    }

    @Test
    fun acceptsMissingClosingTag() = runTest {
        val inner = chunked("<tool_call>{\"name\":\"tap\",\"arguments\":{}}")
        val events = PromptedToolEngine(inner).stream(LlmRequest("m", listOf(ChatMessage.user("x")), tools)).toList()
        assertEquals(1, events.filterIsInstance<LlmEvent.ToolCallReady>().size)
    }

    @Test
    fun plainLessThanIsNotSwallowed() = runTest {
        val inner = chunked("a < b and <to", "day")
        val events = PromptedToolEngine(inner).stream(LlmRequest("m", listOf(ChatMessage.user("x")), tools)).toList()
        assertEquals("a < b and <today", events.filterIsInstance<LlmEvent.TextDelta>().joinToString("") { it.text })
    }

    @Test
    fun rewritesHistoryIntoPlainMessages() = runTest {
        val inner = chunked("ok")
        val call = ToolCall("c1", "tap", """{"id":"1"}""")
        val history = listOf(
            ChatMessage.system("sys"),
            ChatMessage.user("go"),
            ChatMessage.assistant("", listOf(call)),
            ChatMessage.toolResult(call, listOf(ContentPart.Text("tapped"))),
        )
        PromptedToolEngine(inner).stream(LlmRequest("m", history, tools)).toList()
        val sent = inner.requests.single()
        assertTrue(sent.tools.isEmpty())
        assertTrue(sent.messages.first().text.contains("## tap"))
        assertTrue(sent.messages[2].text.contains("<tool_call>"))
        assertEquals(Role.User, sent.messages[3].role)
    }

    @Test
    fun requestWithoutToolsStillWritesOutEarlierCalls() = runTest {
        val inner = chunked("Done for now.")
        val call = ToolCall("c1", "tap", """{"id":"1"}""")
        val history = listOf(
            ChatMessage.system("sys"),
            ChatMessage.user("go"),
            ChatMessage.assistant("", listOf(call)),
            ChatMessage.toolResult(call, listOf(ContentPart.Text("tapped"))),
            ChatMessage.user("wrap up"),
        )
        val events = PromptedToolEngine(inner).stream(LlmRequest("m", history)).toList()
        val sent = inner.requests.single()
        assertEquals("sys", sent.messages.first().text, "no tool instructions without tools")
        assertTrue(sent.messages[2].text.contains("<tool_call>"))
        assertTrue(sent.messages[2].toolCalls.isEmpty())
        assertEquals(Role.User, sent.messages[3].role)
        assertTrue(sent.messages[3].text.contains("<tool_result name=\"tap\">"))
        assertEquals("Done for now.", events.filterIsInstance<LlmEvent.TextDelta>().joinToString("") { it.text })
    }
}

class ContextPackerTest {
    @Test
    fun keepsTaskAndRecentStepsWithinBudget() {
        val convo = buildList {
            add(ChatMessage.user("the task"))
            repeat(40) { i ->
                val call = ToolCall("c$i", "read_screen", "{}")
                add(ChatMessage.assistant("", listOf(call)))
                add(ChatMessage.toolResult(call, listOf(ContentPart.Text("x".repeat(4000)))))
            }
        }
        val packed = ContextPacker.pack("sys", convo, ContextPolicy(budgetTokens = 3000))

        assertEquals("the task", packed[1].text)
        assertTrue(packed.sumOf(ContextPacker::estimate) <= 3200)
        // Tool results never lose their tool call.
        packed.forEachIndexed { i, m -> if (m.role == Role.Tool) assertTrue(packed[i - 1].role in setOf(Role.Assistant, Role.Tool)) }
    }

    @Test
    fun onlyNewestScreenshotSurvives() {
        val img = ContentPart.Image("image/png", "AAAA")
        val convo = listOf(
            ChatMessage.user("task"),
            ChatMessage(Role.User, listOf(img)),
            ChatMessage(Role.User, listOf(img)),
        )
        val packed = ContextPacker.pack("sys", convo, ContextPolicy())
        assertEquals(1, packed.count { m -> m.parts.any { it is ContentPart.Image } })
    }
}
