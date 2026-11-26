package atom.app.ui.chat

import atom.app.agent.ChatItem
import atom.app.agent.Transcript
import atom.app.agent.WorkEntry
import atom.app.data.StoredMessage
import atom.core.llm.ChatMessage
import atom.core.llm.ContentPart
import atom.core.llm.ToolCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The run's own tool preload (an enable_tools call marked internal) is history for the model, not a step for the user. */
class PreloadTranscriptTest {
    private val preload = ToolCall("preload_browser", "enable_tools", """{"groups":"browser"}""")
    private val open = ToolCall("c1", "browser_open", """{"url":"https://example.com"}""")

    private val stored = listOf(
        StoredMessage(ChatMessage.user("open the website")),
        StoredMessage(ChatMessage.assistant("", listOf(preload)), hidden = true),
        StoredMessage(ChatMessage.toolResult(preload, listOf(ContentPart.Text("Loaded for this task:\n- browser: browser_open"))), hidden = true),
        StoredMessage(ChatMessage.assistant("", listOf(open))),
        StoredMessage(ChatMessage.toolResult(open, listOf(ContentPart.Text("Example Domain")))),
        StoredMessage(ChatMessage.assistant("It shows Example Domain.")),
    )

    @Test
    fun theHiddenPreloadIsNoStep() {
        val turn = Transcript.of(stored).filterIsInstance<ChatItem.Turn>().single()
        val steps = turn.work.filterIsInstance<WorkEntry.Steps>().flatMap { it.calls }
        assertEquals(listOf("browser_open"), steps.map { it.name })
        assertEquals("It shows Example Domain.", turn.answer)
    }

    @Test
    fun theModelStillSeesIt() {
        val sent = atom.app.agent.ModelHistory.build(stored).messages
        assertTrue(sent.any { m -> m.toolCalls.any { it.name == "enable_tools" } })
        assertTrue(sent.any { it.toolCallId == "preload_browser" })
    }
}
