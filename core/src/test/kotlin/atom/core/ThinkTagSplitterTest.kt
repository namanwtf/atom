package atom.core

import atom.core.llm.LlmEvent
import atom.core.llm.ThinkTagSplitter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThinkTagSplitterTest {
    private fun split(vararg chunks: String): Pair<String, String> = splitFrom(false, *chunks)

    private fun splitFrom(startsInThinking: Boolean, vararg chunks: String): Pair<String, String> {
        val s = ThinkTagSplitter(startsInThinking)
        val events = chunks.flatMap { s.feed(it) } + s.finish()
        val text = events.filterIsInstance<LlmEvent.TextDelta>().joinToString("") { it.text }
        val reasoning = events.filterIsInstance<LlmEvent.ReasoningDelta>().joinToString("") { it.text }
        return text to reasoning
    }

    @Test
    fun qwen3ThinkingGoesToReasoning() {
        // Token by token, as llama.cpp streams a Qwen3 GGUF.
        val (text, reasoning) = split("<think>", "\n", "The user", " asks for", " the capital.", "\n", "</think>", "\n\n", "Paris", ".")
        assertEquals("Paris.", text)
        assertEquals("\nThe user asks for the capital.\n", reasoning)
    }

    @Test
    fun tagsSplitAcrossChunks() {
        val (text, reasoning) = split("<th", "ink>plan</thi", "nk>\n\nDone")
        assertEquals("Done", text)
        assertEquals("plan", reasoning)
    }

    @Test
    fun controlMarkersNeverShow() {
        assertEquals("Hello!" to "", split("Hello!", "<|im", "_end|>"))
        assertEquals("ab" to "", split("a<|endoftext|>b"))
        assertEquals("Hi" to "", split("Hi<|eot_id|>"))
    }

    @Test
    fun ordinaryTextAndToolCallsPassThrough() {
        assertEquals("a < b and <today>" to "", split("a < b and <to", "day>"))
        val call = "<tool_call>{\"name\": \"tap\", \"arguments\": {}}</tool_call>"
        assertEquals(call to "", split(call))
        assertEquals("x <| y" to "", split("x <| y"))
    }

    @Test
    fun noThinkingMeansAllAnswer() {
        assertEquals("The capital of France is Paris." to "", split("The capital", " of France is Paris."))
    }

    @Test
    fun orphanClosingTagsFromThePhoneNeverShow() {
        // Qwen3 0.6B with thinking off (its template already wrote "<think>\n\n</think>\n\n"), as the phone showed it.
        val (text, reasoning) = split(
            "</think>", "\n", "more tools tools\n", "enable the user\n", "the user:  (language, tone,, tone,, units, recurring,,,,\n,,\n", "</think>",
        )
        assertEquals("more tools tools\nenable the user\nthe user:  (language, tone,, tone,, units, recurring,,,,\n,,\n", text)
        assertEquals("", reasoning)
    }

    @Test
    fun aDuplicateClosingTagAfterReasoningIsDropped() {
        val (text, reasoning) = split("<think>plan</think>", "\n\nDone.", "</think>", " Really.")
        assertEquals("Done. Really.", text)
        assertEquals("plan", reasoning)
        assertEquals("Paris" to "", split("</think></think>\n\nParis"))
    }

    @Test
    fun anOrphanClosingTagSplitAcrossChunksIsHeldBackAndDropped() {
        assertEquals("It is set." to "", split("It is", " set.</thi", "nk>"))
        assertEquals("Okay." to "", split("</", "think>", "\nOkay."))
        // Text that only looks like the start of a tag still shows.
        assertEquals("a </b> c" to "", split("a </", "b> c"))
    }

    @Test
    fun aSecondOpeningTagInsideReasoningIsDropped() {
        assertEquals("Hi" to "ab", split("<think>a<think>b</think>Hi"))
    }

    @Test
    fun aTemplateThatOpensTheReasoningStartsInsideIt() {
        // DeepSeek-R1 style templates end the prompt with "<think>\n": the output has only the closing tag.
        val (text, reasoning) = splitFrom(true, "The user wants", " Paris.", "</think>", "\n\n", "Paris.")
        assertEquals("Paris.", text)
        assertEquals("The user wants Paris.", reasoning)
    }

    @Test
    fun knowsWhenAPromptEndsInsideReasoning() {
        assertTrue(ThinkTagSplitter.endsInsideThinking("<|im_start|>assistant\n<think>\n"))
        assertTrue(ThinkTagSplitter.endsInsideThinking("<|im_start|>assistant\n<think>"))
        // Qwen3 with thinking off: the template closes the block itself.
        assertFalse(ThinkTagSplitter.endsInsideThinking("<|im_start|>assistant\n<think>\n\n</think>\n\n"))
        assertFalse(ThinkTagSplitter.endsInsideThinking("<|im_start|>assistant\n"))
        // A user who writes the tag mid-message does not count.
        assertFalse(ThinkTagSplitter.endsInsideThinking("user: what does <think> do?<|im_end|>\n<|im_start|>assistant\n"))
    }
}
