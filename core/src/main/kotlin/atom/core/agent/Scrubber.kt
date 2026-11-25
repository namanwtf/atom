package atom.core.agent

import atom.core.llm.ChatMessage
import atom.core.llm.ContentPart
import atom.core.llm.Role

/** Keeps sensitive tool output out of anything that outlives the turn: saved history, exports, summaries. */
object Scrubber {
    const val MARKER = "[Sensitive result"

    fun placeholder(toolName: String?): String =
        "$MARKER from ${toolName ?: "a tool"} was used for that turn only and is not kept. Call the tool again if it is needed.]"

    fun scrub(message: ChatMessage): ChatMessage =
        if (message.role != Role.Tool) message else message.copy(parts = listOf(ContentPart.Text(placeholder(message.toolName))))

    fun isScrubbed(message: ChatMessage): Boolean = message.role == Role.Tool && message.text.startsWith(MARKER)
}
