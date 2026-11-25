package atom.core

import atom.core.markdown.Align
import atom.core.markdown.CalloutKind
import atom.core.markdown.MathNode
import atom.core.markdown.MathParser
import atom.core.markdown.Markdown
import atom.core.markdown.MdBlock
import atom.core.markdown.MdInline
import atom.core.markdown.SymKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MarkdownTest {
    @Test
    fun headingsParagraphsAndInlineStyles() {
        val blocks = Markdown.parse("# Title\n\nSome **bold**, *italic*, ~~gone~~ and `code` with [a link](https://x.dev).")
        val h = assertIs<MdBlock.Heading>(blocks[0])
        assertEquals(1, h.level)
        val p = assertIs<MdBlock.Paragraph>(blocks[1]).inlines
        assertTrue(p.any { it is MdInline.Bold })
        assertTrue(p.any { it is MdInline.Italic })
        assertTrue(p.any { it is MdInline.Strike })
        assertTrue(p.any { it is MdInline.Code && it.code == "code" })
        assertEquals("https://x.dev", p.filterIsInstance<MdInline.Link>().single().url)
    }

    @Test
    fun snakeCaseIsNotItalicAndBareUrlsLink() {
        val p = assertIs<MdBlock.Paragraph>(Markdown.parse("call read_screen_now at https://a.b/c.").single()).inlines
        assertTrue(p.none { it is MdInline.Italic })
        assertEquals("https://a.b/c", p.filterIsInstance<MdInline.Link>().single().url)
    }

    @Test
    fun nestedAndTaskLists() {
        val list = assertIs<MdBlock.ListBlock>(Markdown.parse("- [x] done\n- [ ] todo\n  - child\n- plain").single())
        assertEquals(3, list.items.size)
        assertTrue(list.items[0].task && list.items[0].checked)
        assertTrue(list.items[1].task && !list.items[1].checked)
        assertTrue(list.items[1].blocks.any { it is MdBlock.ListBlock })
        assertFalse(list.items[2].task)

        val ordered = assertIs<MdBlock.ListBlock>(Markdown.parse("3. three\n4. four").single())
        assertTrue(ordered.ordered)
        assertEquals(3, ordered.start)
    }

    @Test
    fun fencedCodeIncludingUnclosedWhileStreaming() {
        val code = assertIs<MdBlock.Code>(Markdown.parse("```kotlin\nval x = 1\n```").single())
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1", code.code)
        val open = assertIs<MdBlock.Code>(Markdown.parse("```\npartial **not bold**").single())
        assertFalse(open.closed)
        assertEquals("partial **not bold**", open.code)
    }

    @Test
    fun tablesWithAlignment() {
        val table = assertIs<MdBlock.Table>(Markdown.parse("| A | B |\n|:--|--:|\n| 1 | `a|b` |\n| 2 | 3 |").single())
        assertEquals(listOf(Align.Start, Align.End), table.align)
        assertEquals(2, table.rows.size)
        assertEquals("a|b", (table.rows[0][1].single() as MdInline.Code).code)
    }

    @Test
    fun quotesAndCallouts() {
        val callout = assertIs<MdBlock.Callout>(Markdown.parse("> [!WARNING] Careful\n> This deletes data.").single())
        assertEquals(CalloutKind.Warning, callout.kind)
        assertEquals("Careful", callout.title)
        assertIs<MdBlock.Paragraph>(callout.blocks.single())
        val quote = assertIs<MdBlock.Quote>(Markdown.parse("> quoted\n> more").single())
        assertEquals(1, quote.blocks.size)
    }

    @Test
    fun mathInlineAndBlockWithCurrencySafety() {
        val d = "$"
        val p = assertIs<MdBlock.Paragraph>(Markdown.parse("Energy ${d}E=mc^2$d costs ${d}5 and ${d}10 today, also \\(a+b\\).").single()).inlines
        val maths = p.filterIsInstance<MdInline.Math>().map { it.tex }
        assertEquals(listOf("E=mc^2", "a+b"), maths)
        assertTrue(Markdown.plain(p).contains("${d}5 and ${d}10"))

        val blocks = Markdown.parse("Before\n\n$d$d\n\\frac{a}{b}\n$d$d\n\n\\[x^2\\]\n\n```math\ny\n```")
        assertEquals(listOf("\\frac{a}{b}", "x^2", "y"), blocks.filterIsInstance<MdBlock.Math>().map { it.tex })
    }

    @Test
    fun rulesSetextAndSoftBreaks() {
        val blocks = Markdown.parse("Title\n===\n\nline one\nline two\n\n---")
        assertEquals(1, assertIs<MdBlock.Heading>(blocks[0]).level)
        assertTrue(assertIs<MdBlock.Paragraph>(blocks[1]).inlines.contains(MdInline.LineBreak))
        assertEquals(MdBlock.Rule, blocks[2])
    }

    @Test
    fun garbageNeverThrows() {
        val nasty = "**[`$\\(" + "[".repeat(5_000) + "*".repeat(3_000) + "> > > ```"
        val blocks = Markdown.parse(nasty)
        assertTrue(blocks.isNotEmpty())
    }

    @Test
    fun mathParserBuildsStructure() {
        val frac = assertIs<MathNode.Frac>(MathParser.parse("\\frac{1}{2}"))
        assertEquals(MathNode.Sym("1", SymKind.Num), frac.num)
        val sum = assertIs<MathNode.BigOp>(MathParser.parse("\\sum_{i=1}^{n}"))
        assertEquals("∑", sum.symbol)
        assertTrue(sum.sub != null && sum.sup != null)
        val row = assertIs<MathNode.Row>(MathParser.parse("\\alpha \\leq \\beta"))
        assertEquals(listOf("α", "≤", "β"), row.children.map { (it as MathNode.Sym).text })
        val matrix = assertIs<MathNode.Fenced>(MathParser.parse("\\left( \\begin{matrix} a & b \\\\ c & d \\end{matrix} \\right)"))
        val m = assertIs<MathNode.Matrix>(matrix.body)
        assertEquals(2, m.rows.size)
        assertEquals(2, m.rows[0].size)
        val cases = assertIs<MathNode.Matrix>(MathParser.parse("f(x)=\\begin{cases} 1 & x>0 \\\\ 0 & \\text{otherwise} \\end{cases}").let { (it as MathNode.Row).children.last() })
        assertEquals("{", cases.left)
        val sqrt = assertIs<MathNode.Sqrt>(MathParser.parse("\\sqrt[3]{x}"))
        assertTrue(sqrt.index != null)
        assertIs<MathNode.Scripts>(MathParser.parse("x^2_i"))
        assertIs<MathNode.Sym>(MathParser.parse("\\unknowncommand"))
    }
}
