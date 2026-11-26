package atom.characters

import atom.characters.prompt.Macros
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The JVM accepts a bare "}" in a regex, Android's ICU engine throws PatternSyntaxException on it. That made
 * Macros fail to initialise on the phone, and opening any character's detail screen (Lumen first) crashed the app.
 * These tests run on the JVM, so they check the patterns are written the strict way as well as what they match.
 */
class MacroPatternTest {
    /** Positions of "}" that are neither escaped nor the end of a {m,n} quantifier. */
    private fun bareClosingBraces(pattern: String): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        var inClass = false
        while (i < pattern.length) {
            val ch = pattern[i]
            when {
                ch == '\\' -> i++ // skip the escaped character
                inClass -> if (ch == ']') inClass = false
                ch == '[' -> inClass = true
                ch == '{' -> {
                    val end = pattern.indexOf('}', i)
                    if (end > 0 && Regex("\\{\\d+(,\\d*)?").matches(pattern.substring(i, end))) i = end
                }
                ch == '}' -> out += i
            }
            i++
        }
        return out
    }

    @Test
    fun macroPatternsEscapeEveryBrace() {
        for (r in listOf(Macros.CHAR, Macros.USER, Macros.ORIGINAL)) {
            assertEquals(emptyList(), bareClosingBraces(r.pattern), r.pattern)
        }
        // The checker itself: it catches the old pattern and accepts quantifiers.
        assertEquals(listOf(14, 15), bareClosingBraces("""\{\{\s*char\s*}}"""))
        assertTrue(bareClosingBraces("^(#{1,6})\\s+x{2}").isEmpty())
    }

    @Test
    fun macrosStillReplace() {
        val text = "{{char}} waves at {{ USER }}. <BOT> and <user>.{{original}}"
        assertEquals("Lumen waves at Sam. Lumen and Sam.", Macros.apply(text, "Lumen", "Sam"))
    }
}
