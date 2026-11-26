package atom.app.data

import atom.core.llm.ChatMessage
import atom.core.llm.ContentPart
import atom.core.llm.ToolCall
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationExportTest {
    @Test
    fun hiddenTurnsAreLeftOut() {
        val preload = ToolCall("Ab3dE6gH9", "enable_tools", """{"groups":"browser"}""")
        val open = ToolCall("c1", "browser_open", """{"url":"https://example.com"}""")
        val c = Conversation.empty("x", 0).copy(
            title = "Example",
            messages = listOf(
                StoredMessage(ChatMessage.user("open the website")),
                StoredMessage(ChatMessage.assistant("", listOf(preload)), hidden = true),
                StoredMessage(ChatMessage.toolResult(preload, listOf(ContentPart.Text("Loaded for this task"))), hidden = true),
                StoredMessage(ChatMessage.user("You have issued the identical action 3 times."), hidden = true),
                StoredMessage(ChatMessage.assistant("", listOf(open))),
                StoredMessage(ChatMessage.toolResult(open, listOf(ContentPart.Text("Example Domain")))),
                StoredMessage(ChatMessage.assistant("It shows Example Domain.")),
            ),
        )
        val md = ConversationExport.markdown(c, now = 0)
        assertFalse("enable_tools" in md || "Loaded for this task" in md || "identical action" in md, md)
        assertTrue("browser_open" in md && "Example Domain" in md && "open the website" in md, md)
    }
}
