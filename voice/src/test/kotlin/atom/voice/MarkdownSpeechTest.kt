package atom.voice

import atom.voice.speech.MarkdownSpeech
import atom.voice.speech.SpeechChunker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkdownSpeechTest {
    private fun speak(md: String) = MarkdownSpeech.toSpeech(md)

    @Test
    fun skipsFencedCode() {
        val out = speak("Run this:\n\n```kotlin\nval x = 1\nprintln(x)\n```\n\nThat's it.")
        assertEquals("Run this: That's it.", out)
    }

    @Test
    fun skipsTablesEntirely() {
        val out = speak("Prices:\n\n| Item | Cost |\n|---|---:|\n| Tea | 2 |\n| Cake | 4 |\n\nEnjoy.")
        assertEquals("Prices: Enjoy.", out)
    }

    @Test
    fun skipsImagesAndKeepsLinkText() {
        val out = speak("See ![diagram](https://x.y/a.png) and [the docs](https://example.com/docs) for more.")
        assertEquals("See and the docs for more.", out)
    }

    @Test
    fun dropsBareUrlsAndHtml() {
        val out = speak("Visit https://example.com/path?q=1 or www.test.org now. <b>Bold</b> <br/> text")
        assertFalse(out.contains("http"))
        assertFalse(out.contains("www"))
        assertFalse(out.contains("<"))
        assertTrue(out.contains("Bold"))
    }

    @Test
    fun skipsScriptBlocks() {
        val out = speak("Hello\n<script>\nalert(1)\n</script>\nWorld")
        assertEquals("Hello. World.", out)
    }

    @Test
    fun speaksListNumbersAndTaskState() {
        val out = speak("Steps:\n1. Open settings\n2. Tap Speech\n\n- [x] Buy milk\n- [ ] Call mom\n- plain item")
        assertEquals("Steps: 1. Open settings. 2. Tap Speech. Done: Buy milk. To do: Call mom. plain item.", out)
    }

    @Test
    fun stripsHeadingsEmphasisAndQuotes() {
        val out = speak("## Summary\n\n> **Important**: this is *really* ~~not~~ `fine`.")
        assertEquals("Summary. Important: this is really not fine.", out)
    }

    @Test
    fun keepsSnakeCaseWords() {
        assertEquals("Edit my_config_file now.", speak("Edit my_config_file now"))
    }

    @Test
    fun skipsDisplayMathAndRules() {
        val out = speak("Area:\n\n$$\nA = \\pi r^2\n$$\n\n---\n\nDone")
        assertEquals("Area: Done.", out)
    }

    @Test
    fun filterOffKeepsText() {
        assertEquals("**bold** `x`", MarkdownSpeech.toSpeech("**bold**\n`x`", filter = false))
    }

    @Test
    fun chineseSentencesKeepTheirPunctuation() {
        assertEquals("你好。今天天气很好！", speak("你好。\n今天天气很好！"))
    }

    @Test
    fun chunkerSplitsAtSentences() {
        val text = (1..30).joinToString(" ") { "Sentence number $it is here." }
        val chunks = SpeechChunker.split(text, 120)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 120 })
        assertEquals(text.replace(" ", ""), chunks.joinToString("").replace(" ", ""))
        assertTrue(chunks.all { it.endsWith(".") })
    }

    @Test
    fun chunkerHardSplitsLongSentence() {
        val text = "word ".repeat(200).trim()
        val chunks = SpeechChunker.split(text, 100)
        assertTrue(chunks.all { it.length <= 100 })
        assertEquals(200, chunks.sumOf { it.split(' ').size })
    }
}
