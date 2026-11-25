package atom.overlay

import atom.overlay.ui.Markdown
import atom.overlay.ui.MdBlock
import atom.overlay.ui.MdSpan
import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownTest {
    @Test
    fun blocks() {
        val src = """
            # Title
            Some text
            on two lines

            - one
              - nested
            2. two
            > quoted
            ---
            ```kotlin
            val x = 1
            ```
        """.trimIndent()
        assertEquals(
            listOf(
                MdBlock.Heading(1, "Title"),
                MdBlock.Paragraph("Some text\non two lines"),
                MdBlock.ListItem("•", 0, "one"),
                MdBlock.ListItem("•", 1, "nested"),
                MdBlock.ListItem("2.", 0, "two"),
                MdBlock.Quote("quoted"),
                MdBlock.Rule,
                MdBlock.Code("kotlin", "val x = 1"),
            ),
            Markdown.blocks(src),
        )
    }

    @Test
    fun unclosedFenceRunsToTheEndWhileStreaming() {
        assertEquals(listOf(MdBlock.Paragraph("Here:"), MdBlock.Code("", "line 1\nline 2")), Markdown.blocks("Here:\n```\nline 1\nline 2"))
    }

    @Test
    fun tablesBecomeMonospaceWithoutTheDivider() {
        assertEquals(listOf(MdBlock.Code("table", "| a | b |\n| 1 | 2 |")), Markdown.blocks("| a | b |\n|---|---|\n| 1 | 2 |"))
    }

    @Test
    fun inlineStyles() {
        assertEquals(
            listOf(
                MdSpan("a "),
                MdSpan("bold", bold = true),
                MdSpan(" "),
                MdSpan("it", italic = true),
                MdSpan(" "),
                MdSpan("code", code = true),
                MdSpan(" "),
                MdSpan("gone", strike = true),
                MdSpan(" "),
                MdSpan("link", link = "https://x.y"),
            ),
            Markdown.inline("a **bold** *it* `code` ~~gone~~ [link](https://x.y)"),
        )
    }

    @Test
    fun nestedAndLiteralMarkers() {
        assertEquals(listOf(MdSpan("x "), MdSpan("y", bold = true, italic = true)), Markdown.inline("x ***y***"))
        assertEquals(listOf(MdSpan("a "), MdSpan("b ", bold = true), MdSpan("c", bold = true, italic = true)), Markdown.inline("a **b *c***"))
        assertEquals(listOf(MdSpan("snake_case_name and 2 * 3")), Markdown.inline("snake_case_name and 2 * 3"))
        assertEquals(listOf(MdSpan("open **bold")), Markdown.inline("open **bold"))
        assertEquals(listOf(MdSpan("*not*")), Markdown.inline("\\*not\\*"))
    }

    @Test
    fun plainStripsMarkup() {
        assertEquals("Title\nHello world\none", Markdown.plain("# Title\nHello **world**\n- one"))
    }
}
