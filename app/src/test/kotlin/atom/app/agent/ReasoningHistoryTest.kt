package atom.app.agent

import atom.app.data.MessageDto
import atom.app.data.StoredMessage
import atom.app.data.toDto
import atom.app.data.toModel
import atom.core.llm.ChatMessage
import atom.core.llm.ContentPart
import atom.core.llm.ToolCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/** A tool-call turn's reasoning is stored with it and comes back with the history the model sees. */
class ReasoningHistoryTest {
    private val call = ToolCall("call_reasoning_1", "set_volume", """{"level":20}""")

    private val stored = listOf(
        StoredMessage(ChatMessage.user("turn the volume down")),
        StoredMessage(ChatMessage.assistant("", listOf(call)), reasoning = "The user wants it quieter; 20% is fine."),
        StoredMessage(ChatMessage.toolResult(call, listOf(ContentPart.Text("Volume 20%")))),
        StoredMessage(ChatMessage.assistant("Done, the volume is at 20%."), reasoning = "Report it."),
    )

    @Test
    fun theToolCallTurnCarriesItsStoredReasoning() {
        val sent = ModelHistory.build(stored, replaysReasoning = true).messages
        val turn = sent.single { it.toolCalls.isNotEmpty() }
        assertEquals("The user wants it quieter; 20% is fine.", turn.reasoning)
        // Only tool-call turns need it back; a plain answer goes without.
        assertNull(sent.last().reasoning)
    }

    @Test
    fun itSurvivesSavingAndLoading() {
        // As the conversation file stores it, and as the app reads it back after a restart.
        val again = stored.map { s -> Json.decodeFromString(MessageDto.serializer(), Json.encodeToString(MessageDto.serializer(), s.toDto())).toModel()!! }
        assertEquals("The user wants it quieter; 20% is fine.", ModelHistory.build(again, replaysReasoning = true).messages.single { it.toolCalls.isNotEmpty() }.reasoning)
    }

    @Test
    fun anEngineThatNeverSendsReasoningGetsNone() {
        // Other wires ignore it, so it is not carried (and does not count against the context).
        assertTrue(ModelHistory.build(stored).messages.all { it.reasoning == null })
    }

    @Test
    fun nudgesAndSteeringAreMarkedAsInsideTheTurn() {
        val more = stored + listOf(
            StoredMessage(ChatMessage.user("You repeated the last step."), hidden = true),
            StoredMessage(ChatMessage.user("make it 10% instead"), steering = true),
            StoredMessage(ChatMessage.user("thanks, now mute it")),
        )
        val users = ModelHistory.build(more).messages.filter { it.role == atom.core.llm.Role.User }
        assertEquals(listOf(false, true, true, false), users.map { it.midTurn })
    }
}
