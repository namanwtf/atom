package atom.core

import atom.core.llm.LlmEvent
import atom.core.llm.ThinkTagSplitter
import kotlin.test.Test
import kotlin.test.assertEquals

class ThinkTagSplitterTest {
    private fun split(vararg chunks: String): Pair<String, String> {
        val s = ThinkTagSplitter()
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
}
