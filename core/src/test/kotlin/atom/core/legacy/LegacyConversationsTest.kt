package atom.core.legacy

import atom.core.legacy.ImportedEntry.Message
import atom.core.legacy.ImportedEntry.Notice
import atom.core.llm.ReasoningEffort
import atom.core.llm.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyConversationsTest {
    private val conv = LegacyConversationRow(id = "c1", title = "Trip", createdAt = 1_000, updatedAt = 10_000, modelRowId = "m1", pinned = true)

    private fun row(id: String, sort: Long, type: String, content: String = "", block: LegacyMessageRow.() -> LegacyMessageRow = { this }) =
        LegacyMessageRow(id = id, sortIndex = sort, type = type, content = content).block()

    private val journalJson = """
        [
          {"role":"user","content":"List files","messageId":"user-r1"},
          {"role":"assistant","reasoningContent":"I should run ls","messageId":"assistant-r1-1",
           "toolCallsJson":"[{\"id\":\"call_A\",\"type\":\"function\",\"function\":{\"name\":\"terminal\",\"arguments\":\"{\\\"command\\\":\\\"ls -la\\\"}\"}}]"},
          {"role":"tool","toolCallId":"call_A","content":"a.txt\nb.txt"},
          {"role":"assistant","content":"Two files.","reasoningContent":"Summarize","messageId":"assistant-r1-2"},
          {"role":"assistant","contextSummary":true,"content":"[Atom context summary · archive fold-1]","messageId":"s"}
        ]
    """.trimIndent()

    private fun sample(): List<LegacyMessageRow> = listOf(
        // Deliberately out of order, with gaps: sort_index decides.
        row("assistant-r1-2-result", 40, LegacyMessageRow.ASSISTANT, "Two files."),
        row("user-r1", 3, LegacyMessageRow.USER, "List files") { copy(edited = true, imagePaths = listOf("/x/a.jpg"), droppedImages = 1) },
        row("r1-thinking-1-0", 10, LegacyMessageRow.THINKING, "Thinking about ls") { copy(elapsedSeconds = 2) },
        row("r1-tool-1-call_A", 20, LegacyMessageRow.TOOL, "ls -la") { copy(toolName = "terminal", toolStatus = "Success", resultSummary = "a.txt…") },
        row("r1-tools", 25, LegacyMessageRow.TOOL_SUMMARY),
        row("r1-notice", 50, LegacyMessageRow.SYSTEM_NOTICE, "stopped") { copy(resultSummary = "by user") },
        row("r1-unknown", 60, "mystery", "x"),
        row("r1-notice-2", 70, LegacyMessageRow.SYSTEM_NOTICE, "no_such_code"),
    )

    @Test
    fun mapsEveryRowTypeInSortOrder() {
        val c = LegacyConversations.map(conv, sample(), LegacyJournal.parse(journalJson))
        val e = c.entries
        assertEquals(5, e.size, e.toString())

        val user = assertIs<Message>(e[0])
        assertEquals(Role.User, user.message.role)
        assertEquals("List files\n[image]", user.message.text)
        assertTrue(user.edited)
        assertEquals(listOf("/x/a.jpg"), user.imagePaths)

        // Thinking + tool card: one model turn with the call (full arguments from the journal) and its result.
        val call = assertIs<Message>(e[1])
        assertEquals(Role.Assistant, call.message.role)
        assertEquals("Thinking about ls", call.reasoning)
        assertEquals(2000L, call.reasoningMs)
        val tc = call.message.toolCalls.single()
        assertEquals("call_A", tc.id)
        assertEquals("terminal", tc.name)
        assertEquals("""{"command":"ls -la"}""", tc.argumentsJson)

        val result = assertIs<Message>(e[2])
        assertEquals(Role.Tool, result.message.role)
        assertEquals("call_A", result.message.toolCallId)
        assertEquals("a.txt\nb.txt", result.message.text)
        assertFalse(result.isError)

        // The answer has no thinking row before it, so its reasoning comes from the journal.
        val answer = assertIs<Message>(e[3])
        assertEquals("Two files.", answer.message.text)
        assertEquals("Summarize", answer.reasoning)

        val notice = assertIs<Notice>(e[4])
        assertEquals("stopped", notice.code)
        assertEquals("by user", notice.detail)
        assertFalse(notice.isError)
    }

    @Test
    fun keepsConversationFields() {
        val c = LegacyConversations.map(conv.copy(reasoningEffort = "x-high"), sample())
        assertEquals("c1", c.sourceId)
        assertEquals("Trip", c.title)
        assertFalse(c.titleIsPlaceholder)
        assertTrue(c.pinned)
        assertEquals("m1", c.modelRowId)
        assertEquals(true, c.reasoningEnabled)
        assertEquals(ReasoningEffort.XHigh, c.reasoningEffort)
        assertFalse(c.isCharacterChat)
        assertEquals(1_000, c.createdAt)
        assertEquals(10_000, c.updatedAt)
    }

    @Test
    fun reasoningOffAndDefault() {
        val off = LegacyConversations.map(conv.copy(reasoningEffort = "off"), emptyList())
        assertEquals(false, off.reasoningEnabled)
        assertNull(off.reasoningEffort)
        val def = LegacyConversations.map(conv.copy(reasoningEffort = "default"), emptyList())
        assertEquals(true, def.reasoningEnabled)
        assertNull(def.reasoningEffort)
        assertEquals(ReasoningEffort.Low, LegacyConversations.map(conv.copy(reasoningEffort = "minimal"), emptyList()).reasoningEffort)
    }

    @Test
    fun timestampsAreStrictlyIncreasingWithinTheConversationSpan() {
        val c = LegacyConversations.map(conv, sample(), LegacyJournal.parse(journalJson))
        val times = c.entries.map { it.createdAt }
        assertEquals(1_000, times.first())
        assertEquals(10_000, times.last())
        assertTrue(times.zipWithNext().all { (a, b) -> b > a }, times.toString())

        assertEquals(listOf(5L, 6L, 7L), LegacyConversations.timestamps(5, 5, 3))
        assertEquals(listOf(5L, 6L, 7L), LegacyConversations.timestamps(5, 1, 3), "update before creation")
        assertEquals(listOf(900L), LegacyConversations.timestamps(0, 900, 1), "no creation time")
        assertTrue(LegacyConversations.timestamps(1, 2, 0).isEmpty())
    }

    @Test
    fun withoutAJournalToolRowsUseTheirPreviews() {
        val rows = listOf(
            row("u", 0, LegacyMessageRow.USER, "go"),
            row("r-tool-1-call_X", 1, LegacyMessageRow.TOOL) { copy(toolName = "open_app", toolStatus = "Failed", argumentsSummary = "Maps", resultSummary = "not installed") },
            row("r-tool-1-call_Y", 2, LegacyMessageRow.TOOL) { copy(toolName = "open_app", toolStatus = "Running", argumentsSummary = "Clock") },
            row("a", 3, LegacyMessageRow.ASSISTANT, "Done"),
        )
        val e = LegacyConversations.map(conv, rows).entries
        val call = assertIs<Message>(e[1])
        assertEquals(2, call.message.toolCalls.size)
        val (x, y) = call.message.toolCalls
        assertEquals("""{"summary":"Maps"}""", x.argumentsJson)
        assertTrue(x.id.startsWith("call_") && x.id != y.id)
        val failed = assertIs<Message>(e[2])
        assertEquals("not installed", failed.message.text)
        assertTrue(failed.isError)
        // The running call has no result; Atom 4 leaves unanswered calls out of what the model sees.
        assertEquals("Done", assertIs<Message>(e[3]).message.text)
        assertEquals(4, e.size)
    }

    @Test
    fun aSuccessWithoutAnyResultTextStillCloses() {
        val rows = listOf(row("r-tool-1-c", 0, LegacyMessageRow.TOOL) { copy(toolName = "t", toolStatus = "Success") })
        val e = LegacyConversations.map(conv, rows).entries
        assertEquals(LegacyConversations.NO_RESULT, assertIs<Message>(e[1]).message.text)
    }

    @Test
    fun aThinkingRowAfterToolsStartsANewRound() {
        val rows = listOf(
            row("t1", 0, LegacyMessageRow.THINKING, "round one"),
            row("r-tool-1-a", 1, LegacyMessageRow.TOOL) { copy(toolName = "x", toolStatus = "Success", resultSummary = "ok") },
            row("t2", 2, LegacyMessageRow.THINKING, "round two"),
            row("r-tool-2-b", 3, LegacyMessageRow.TOOL) { copy(toolName = "x", toolStatus = "Success", resultSummary = "ok") },
        )
        val turns = LegacyConversations.map(conv, rows).entries.filterIsInstance<Message>().filter { it.message.toolCalls.isNotEmpty() }
        assertEquals(listOf("round one", "round two"), turns.map { it.reasoning })
    }

    @Test
    fun danglingReasoningKeepsItsCardOnAnEmptyTurn() {
        val rows = listOf(
            row("u", 0, LegacyMessageRow.USER, "hi"),
            row("t", 1, LegacyMessageRow.THINKING, "hmm"),
            row("n", 2, LegacyMessageRow.SYSTEM_NOTICE, "interrupted"),
        )
        val e = LegacyConversations.map(conv, rows).entries
        val turn = assertIs<Message>(e[1])
        assertEquals("", turn.message.text)
        assertEquals("hmm", turn.reasoning)
        assertIs<Notice>(e[2])
    }

    @Test
    fun blankAssistantRowsAreSkippedAndFailuresAreErrors() {
        val rows = listOf(
            row("a", 0, LegacyMessageRow.ASSISTANT, "  "),
            row("n", 1, LegacyMessageRow.SYSTEM_NOTICE, "runtime_failed") { copy(resultSummary = "HTTP 500") },
        )
        val e = LegacyConversations.map(conv, rows).entries
        assertEquals(1, e.size)
        assertTrue(assertIs<Notice>(e[0]).isError)
    }

    @Test
    fun untitledAndLegacyTitlesAreDerivedFromTheFirstMessage() {
        val rows = listOf(row("u", 0, LegacyMessageRow.USER, "\n  What is the weather in Lisbon tomorrow afternoon, roughly speaking?\nmore"))
        for (title in listOf("", "New conversation")) {
            val c = LegacyConversations.map(conv.copy(title = title), rows)
            assertTrue(c.titleIsPlaceholder)
            assertEquals("What is the weather in Lisbon tomorrow afternoo…", c.title)
        }
    }

    @Test
    fun characterChatsImportAsNormalChatsNamedAfterTheCharacter() {
        val roleplay = """{"characterId":"ch1","cardSnapshotJson":"{}","characterName":"Mira","avatarPath":null,"userName":"User","userDescription":""}"""
        val rows = listOf(row("greeting-1", 0, LegacyMessageRow.ASSISTANT, "Hello traveller"), row("u", 1, LegacyMessageRow.USER, "hi"))
        val c = LegacyConversations.map(conv.copy(title = "", roleplayJson = roleplay), rows)
        assertTrue(c.isCharacterChat)
        assertEquals("Mira", c.characterName)
        assertEquals("Mira", c.title)
        assertFalse(c.titleIsPlaceholder)
        assertEquals("Hello traveller", assertIs<Message>(c.entries[0]).message.text)
        // A damaged roleplay value still counts as a character chat.
        assertTrue(LegacyConversations.map(conv.copy(roleplayJson = "{oops"), rows).isCharacterChat)
    }

    @Test
    fun oversizedValuesAreCut() {
        val rows = listOf(row("u", 0, LegacyMessageRow.USER, "x".repeat(50)))
        val c = LegacyConversations.map(conv, rows, maxChars = 10)
        assertEquals("x".repeat(10) + LegacyConversations.TRUNCATED_NOTE, assertIs<Message>(c.entries[0]).message.text)
        assertEquals(1, c.truncatedEntries)
    }

    @Test
    fun damagedRowsAreCounted() {
        val rows = listOf(row("u", 0, LegacyMessageRow.USER, "") { copy(damaged = true) })
        assertEquals(1, LegacyConversations.map(conv, rows).damagedRows)
    }

    @Test
    fun journalLookups() {
        val j = LegacyJournal.parse(journalJson)
        assertEquals("call_A", j.callIdFor("r1-tool-1-call_A"))
        assertEquals("call_A", j.callIdFor("branch-r1-tool-x-call_A"), "falls back to a suffix match")
        assertNull(j.callIdFor("r1-tool-1-call_B"))
        assertEquals("Summarize", j.reasoningFor("assistant-r1-2-result"))
        assertEquals("I should run ls", j.reasoningFor("assistant-r1-retry-1"))
        assertTrue(LegacyJournal.parse("not json").isEmpty)
        assertTrue(LegacyJournal.parse("").isEmpty)
        // Summaries never contribute.
        assertNull(j.reasoning["s"])
    }

    @Test
    fun imagesJson() {
        assertEquals(listOf("data:image/png;base64,AAA"), LegacyConversations.images("""["data:image/png;base64,AAA",""," "]"""))
        assertTrue(LegacyConversations.images("").isEmpty())
        assertTrue(LegacyConversations.images("{").isEmpty())
    }
}
