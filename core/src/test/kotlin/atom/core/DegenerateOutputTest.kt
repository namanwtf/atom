package atom.core

import atom.core.agent.Agent
import atom.core.agent.AgentConfig
import atom.core.agent.AgentEvent
import atom.core.llm.ChatMessage
import atom.core.llm.DegenerateOutputDetector
import atom.core.llm.DegenerateOutputGuard
import atom.core.llm.FinishReason
import atom.core.llm.LlmErrors
import atom.core.llm.LlmEvent
import atom.core.llm.LlmException
import atom.core.llm.LlmFailure
import atom.core.llm.LlmRequest
import atom.core.tool.ToolRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DegenerateOutputTest {
    /** Feeds [chunks] one by one; returns the index of the chunk that tripped the detector, or -1. */
    private fun tripAt(chunks: List<String>): Int {
        val d = DegenerateOutputDetector()
        chunks.forEachIndexed { i, c -> if (d.feed(c)) return i }
        return -1
    }

    private fun trips(text: String, chunk: Int = 3) = tripAt(text.chunked(chunk)) >= 0

    /** The stream reported from the phone with Qwen3 0.6B. */
    private val reported = listOf(
        "<", "#1", "1", "#22", "#2", "#1", "#11", "#111", "#11111", "1111",
        "#1111111111111111111111#1111111#111111111111111111", "#11", "1111111", "#111111111111", "#1111111111",
    )

    @Test
    fun stopsTheReportedStream() {
        val at = tripAt(reported)
        assertTrue(at in 0 until reported.lastIndex, "tripped at chunk $at of ${reported.size}")
    }

    @Test
    fun stopsTextOfAFewSymbols() {
        assertTrue(trips("Sure! " + "1#".repeat(60)))
        assertTrue(trips("The answer is " + "ab ".repeat(60)))
        assertTrue(trips("#1#2#1#2#11#22#1#2#11#1#2#22#1#11#2#1#12#21#1#2#11#22".repeat(3)))
        // A data word is skipped only up to a sane length.
        assertTrue(trips("1".repeat(600)))
    }

    @Test
    fun stopsAPieceRepeatedOverAndOver() {
        assertTrue(trips("Let me check. " + "ok ".repeat(40)))
        assertTrue(trips("I will open the browser now. ".repeat(10)))
        assertTrue(trips("news ".repeat(30)))
        assertTrue(trips("Result:\n" + "<tool_result>\n".repeat(20)))
    }

    @Test
    fun ordinaryAnswersPass() = assertNoFalseAlarm(
        "The capital of France is Paris. It has been the country's capital since the 10th century, and today " +
            "about 2.1 million people live in the city itself.",
        "Here are today's top technology stories:\n\n1. Chip maker reports record results as demand for AI " +
            "accelerators grows.\n2. A new phone processor is due next spring.\n3. Regulators open an inquiry " +
            "into app store fees.\n4. A startup raises \$200 million for battery research.",
        "fun main() {\n    val xs = listOf(1, 2, 3)\n    for (x in xs) {\n        println(x * x)\n    }\n}\n",
        "for i in range(10):\n    print(i)\n".repeat(3),
        "-".repeat(80) + "\nSection two\n" + "=".repeat(80) + "\n",
        "Pi is about 3.14159265358979323846264338327950288419716939937510, and e is 2.718281828459045.",
        "Done: Do Not Disturb is on. " + "😂".repeat(24),
        "Ich habe die Seite geöffnet. Die wichtigsten Nachrichten: neue Chips, neue Telefone und ein Gerichtsurteil.",
        "1. Open the app\n2. Tap Settings\n3. Tap Display\n4. Turn on Dark mode\n5. Go back\n6. Check the result\n",
        "The year 2000 was 1000 years after 1000, and 10000 is ten times 1000.",
        // A repetitive but real summary, as Qwen3 0.6B wrote it on the runner.
        (1..6).joinToString("\n") { "- **Chip maker $it**: Revenue rose $it%, shares moved ${it % 2}%, and similar." },
        "{\"items\": [" + (1..8).joinToString(", ") { "{\"id\": $it, \"ok\": true}" } + "]}",
    )

    @Test
    fun binaryStringsPass() = assertNoFalseAlarm(
        "The binary form is " + "0110100001100101011011000110110001101111".repeat(2) + " which spells hello hello.",
        "Bits: " + "01".repeat(40),
    )

    @Test
    fun numberArraysPass() = assertNoFalseAlarm(
        "The buffer starts {" + List(40) { "0" }.joinToString(", ") + "} and then fills up.",
        "Zeros: [" + List(60) { "0" }.joinToString(", ") + "]",
        "```\n" + "0, ".repeat(200) + "\n```\nThat is the zero array.",
    )

    @Test
    fun base64Passes() = assertNoFalseAlarm(
        "An empty image in base64: " + "A".repeat(120) + "== done.",
        "data:image/png;base64," + "AAAA".repeat(100),
    )

    @Test
    fun tablesOfZerosAndOnesPass() = assertNoFalseAlarm(
        "Truth table:\n\n| A | B | A and B |\n|---|---|---|\n" +
            "| 0 | 0 | 0 |\n| 0 | 1 | 0 |\n| 1 | 0 | 0 |\n| 1 | 1 | 1 |\n".repeat(4) + "\nThat is AND.",
        "| Name | Price | Stock |\n|------|------:|:-----:|\n| Apple | 1.20 | 14 |\n| Pear | 0.90 | 3 |\n",
    )

    @Test
    fun repeatedToolCallsPass() = assertNoFalseAlarm(
        "Scrolling five times.\n" + "<tool_call>{\"name\": \"scroll\", \"arguments\": {\"direction\": \"down\"}}</tool_call>\n".repeat(5),
        "<tool_call>{\"name\": \"browser_open\", \"arguments\": {\"url\": \"https://news.google.com/topics/technology\"}}</tool_call>",
    )

    private fun assertNoFalseAlarm(vararg samples: String) {
        for (s in samples) {
            assertFalse(trips(s), "false alarm on: $s")
            assertFalse(trips(s, chunk = 1), "false alarm (1-char chunks) on: $s")
            assertFalse(trips(s, chunk = 7), "false alarm (7-char chunks) on: $s")
        }
    }

    @Test
    fun guardFailsTheStreamWithAClearMessage() = runTest {
        val looping = ScriptedEngine(listOf(reported.map { LlmEvent.TextDelta(it) } + LlmEvent.Finished(FinishReason.Stop)))
        val seen = mutableListOf<LlmEvent>()
        val e = assertFailsWith<LlmException> {
            DegenerateOutputGuard(looping).stream(LlmRequest("m", listOf(ChatMessage.user("x")))).collect { seen += it }
        }
        assertFalse(e.retryable)
        assertEquals(LlmFailure.Unreadable, LlmErrors.classify(e))
        assertEquals("This model produced unreadable output. Try again or pick a larger model.", LlmErrors.explain(e))
        // Collection stopped at the loop: the rest of the stream (and its Finished) never came through.
        assertTrue(seen.none { it is LlmEvent.Finished })
        assertTrue(seen.size < reported.size)
    }

    @Test
    fun guardPassesNormalStreamsThrough() = runTest {
        val events = listOf(LlmEvent.TextDelta("Paris "), LlmEvent.TextDelta("is the capital."), LlmEvent.Finished(FinishReason.Stop))
        val out = DegenerateOutputGuard(ScriptedEngine(listOf(events))).stream(LlmRequest("m", listOf(ChatMessage.user("x")))).toList()
        assertEquals(events, out)
    }

    @Test
    fun agentReportsTheLoopAsAFailedRun() = runTest {
        val looping = ScriptedEngine(listOf(reported.map { LlmEvent.TextDelta(it) } + LlmEvent.Finished(FinishReason.Stop)))
        val agent = Agent(DegenerateOutputGuard(looping), ToolRegistry(emptyList()), AgentConfig(model = "m", systemPrompt = "s"))
        val events = agent.run(listOf(ChatMessage.user("search the news")), FixedContext()).toList()
        val failed = events.filterIsInstance<AgentEvent.Failed>().single()
        assertEquals(LlmFailure.Unreadable, failed.failure)
        assertEquals("This model produced unreadable output. Try again or pick a larger model.", failed.message)
        assertTrue(events.none { it is AgentEvent.Retrying })
        assertTrue(failed.newMessages.isEmpty())
    }
}
