package atom.linux.term

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TerminalEmulatorTest {
    private fun emu(cols: Int = 10, rows: Int = 4, scrollback: Int = 100) = TerminalEmulator(cols, rows, scrollback)

    private fun TerminalEmulator.feed(s: String) = process(s)
    private fun TerminalEmulator.line(y: Int) = snapshot().lines[y].trimmedText()
    private fun TerminalEmulator.cursor() = snapshot().let { it.cursorRow to it.cursorColumn }

    @Test
    fun printsAndWrapsWithPendingWrap() {
        val t = emu(cols = 5)
        t.feed("abcde")
        assertEquals("abcde", t.line(0))
        assertEquals(0 to 4, t.cursor()) // pending wrap: still on the last column
        t.feed("f")
        assertEquals("f", t.line(1))
        assertTrue(t.snapshot().lines[0].wrapped)
        assertEquals(1 to 1, t.cursor())
    }

    @Test
    fun crLfBackspaceTab() {
        val t = emu(cols = 20)
        t.feed("hello\rj")
        assertEquals("jello", t.line(0))
        t.feed("\n")
        assertEquals(1 to 1, t.cursor()) // LF keeps the column (onlcr is the tty's job)
        t.feed("\rab\bX")
        assertEquals("aX", t.line(1))
        t.feed("\r\tZ")
        assertEquals("aX      Z", t.line(1))
        t.feed("\u0007") // BEL is harmless
        assertEquals("aX      Z", t.line(1))
    }

    @Test
    fun cursorMovement() {
        val t = emu(cols = 10, rows = 5)
        t.feed("\u001b[3;4H")
        assertEquals(2 to 3, t.cursor())
        t.feed("\u001b[A")
        assertEquals(1 to 3, t.cursor())
        t.feed("\u001b[2B")
        assertEquals(3 to 3, t.cursor())
        t.feed("\u001b[3C")
        assertEquals(3 to 6, t.cursor())
        t.feed("\u001b[10D")
        assertEquals(3 to 0, t.cursor())
        t.feed("\u001b[7G")
        assertEquals(3 to 6, t.cursor())
        t.feed("\u001b[2d")
        assertEquals(1 to 6, t.cursor())
        t.feed("\u001b[99;99f")
        assertEquals(4 to 9, t.cursor()) // clamped
        t.feed("\u001b[H")
        assertEquals(0 to 0, t.cursor())
    }

    @Test
    fun eraseInLineAndDisplay() {
        val t = emu(cols = 6, rows = 3)
        t.feed("aaaaaa\r\nbbbbbb\r\ncccccc")
        t.feed("\u001b[2;3H\u001b[K")
        assertEquals("bb", t.line(1))
        t.feed("\u001b[1K")
        assertEquals("", t.line(1))
        t.feed("\u001b[1;4H\u001b[J")
        assertEquals("aaa", t.line(0))
        assertEquals("", t.line(2))
        t.feed("\u001b[2J")
        assertEquals("", t.snapshot().text())
    }

    @Test
    fun insertDeleteCharsAndLines() {
        val t = emu(cols = 6, rows = 4)
        t.feed("abcdef\u001b[1;3H\u001b[2@")
        assertEquals("ab  cd", t.line(0))
        t.feed("\u001b[3P")
        assertEquals("abd", t.line(0))
        t.feed("\u001b[2;1H11\r\n22\r\n33")
        t.feed("\u001b[2;1H\u001b[L")
        assertEquals(listOf("abd", "", "11", "22"), (0..3).map { t.line(it) })
        t.feed("\u001b[2M")
        assertEquals(listOf("abd", "22", "", ""), (0..3).map { t.line(it) })
        t.feed("\u001b[1;1H\u001b[2X")
        assertEquals("  d", t.snapshot().lines[0].text.trimEnd())
    }

    @Test
    fun scrollRegionKeepsOutsideLines() {
        val t = emu(cols = 5, rows = 5)
        t.feed("top\r\n1\r\n2\r\n3\r\nbot")
        t.feed("\u001b[2;4r") // rows 2..4
        assertEquals(0 to 0, t.cursor()) // DECSTBM homes
        t.feed("\u001b[4;1H\nnew")
        assertEquals(listOf("top", "2", "3", "new", "bot"), (0..4).map { t.line(it) })
        t.feed("\u001b[2;1H\u001bM") // reverse index at the top margin scrolls down
        assertEquals(listOf("top", "", "2", "3", "bot"), (0..4).map { t.line(it) })
        assertTrue(t.snapshot().scrollback.isEmpty()) // region scrolls never feed scrollback
    }

    @Test
    fun scrollbackCollectsLinesAndIsCapped() {
        val t = emu(cols = 5, rows = 2, scrollback = 3)
        t.feed("1\r\n2\r\n3\r\n4\r\n5\r\n6")
        val s = t.snapshot()
        assertEquals(listOf("2", "3", "4"), s.scrollback.map { it.trimmedText() })
        assertEquals(listOf("5", "6"), s.lines.map { it.trimmedText() })
        t.feed("\u001b[3J")
        assertTrue(t.snapshot().scrollback.isEmpty())
    }

    @Test
    fun sgrColorsAndAttributes() {
        val t = emu(cols = 20)
        t.feed("\u001b[1;3;4;31;42mA\u001b[0mB\u001b[38;5;196;48;2;1;2;3mC\u001b[38:2::10:20:30mD\u001b[7;95mE\u001b[22;27;39mF")
        val row = t.snapshot().lines[0]
        val a = row.styleAt(0)
        assertEquals(CellStyle.BOLD or CellStyle.ITALIC or CellStyle.UNDERLINE, CellStyle.attrs(a))
        assertEquals(CellStyle.indexed(1), CellStyle.fg(a))
        assertEquals(CellStyle.indexed(2), CellStyle.bg(a))
        assertEquals(CellStyle.DEFAULT, row.styleAt(1))
        val c = row.styleAt(2)
        assertEquals(CellStyle.indexed(196), CellStyle.fg(c))
        assertEquals(CellStyle.rgb(1, 2, 3), CellStyle.bg(c))
        assertEquals(CellStyle.rgb(10, 20, 30), CellStyle.fg(row.styleAt(3)))
        val e = row.styleAt(4)
        assertEquals(CellStyle.INVERSE, CellStyle.attrs(e))
        assertEquals(CellStyle.indexed(13), CellStyle.fg(e))
        val f = row.styleAt(5)
        assertEquals(0, CellStyle.attrs(f))
        assertEquals(0, CellStyle.fg(f))
        assertEquals(CellStyle.rgb(1, 2, 3), CellStyle.bg(f)) // background survives 22/27/39
    }

    @Test
    fun eraseUsesBackgroundColour() {
        val t = emu(cols = 4, rows = 1)
        t.feed("\u001b[44m\u001b[2J")
        assertEquals(CellStyle.indexed(4), CellStyle.bg(t.snapshot().lines[0].styleAt(3)))
    }

    @Test
    fun alternateScreen1049RestoresMainAndCursor() {
        val t = emu(cols = 10, rows = 3)
        t.feed("shell$ ls")
        t.feed("\u001b[?1049h")
        assertTrue(t.snapshot().altScreen)
        assertEquals("", t.snapshot().text())
        t.feed("\u001b[2;2Hvim")
        assertEquals(" vim", t.line(1))
        assertTrue(t.snapshot().scrollback.isEmpty())
        t.feed("\u001b[?1049l")
        val s = t.snapshot()
        assertFalse(s.altScreen)
        assertEquals("shell$ ls", s.lines[0].trimmedText())
        assertEquals(0 to 9, s.cursorRow to s.cursorColumn)
    }

    @Test
    fun alternateScreen47KeepsContentUntilCleared() {
        val t = emu(cols = 10, rows = 2)
        t.feed("main\u001b[?47hALT\u001b[?47l")
        assertEquals("main", t.line(0).trimEnd())
        t.feed("\u001b[?47h")
        assertTrue(t.snapshot().text().contains("ALT"))
    }

    @Test
    fun utf8SplitAcrossChunksAndWideChars() {
        val t = emu(cols = 10, rows = 2)
        val bytes = "é€😀中".toByteArray(Charsets.UTF_8)
        for (b in bytes) t.process(byteArrayOf(b)) // worst case: one byte per read
        val row = t.snapshot().lines[0]
        assertEquals('é'.code, row.codePointAt(0))
        assertEquals('€'.code, row.codePointAt(1))
        assertEquals(0x1F600, row.codePointAt(2))
        assertEquals(TerminalRow.WIDE_TAIL, row.codePointAt(3))
        assertEquals('中'.code, row.codePointAt(4))
        assertEquals("é€😀中", row.trimmedText())
        assertEquals(0 to 6, t.cursor())
    }

    @Test
    fun malformedUtf8BecomesReplacement() {
        val t = emu()
        t.process(byteArrayOf(0xE2.toByte(), 0x82.toByte(), 'A'.code.toByte(), 0xFF.toByte()))
        assertEquals("�A�", t.line(0))
    }

    @Test
    fun cursorPositionReportAndDeviceAttributes() {
        val t = emu(cols = 20, rows = 5)
        t.feed("\u001b[3;7H\u001b[6n")
        assertEquals("\u001b[3;7R", t.takeResponses())
        assertNull(t.takeResponses())
        t.feed("\u001b[5n\u001b[c")
        assertEquals("\u001b[0n\u001b[?62;22c", t.takeResponses())
        t.feed("\u001b[2;4r\u001b[?6h\u001b[2;2H\u001b[6n") // origin mode reports relative to the margin
        assertEquals("\u001b[2;2R", t.takeResponses())
    }

    @Test
    fun modesTitleAndSaveRestore() {
        val t = emu(cols = 20, rows = 3)
        t.feed("\u001b]0;hello there\u0007")
        assertEquals("hello there", t.snapshot().title)
        t.feed("\u001b]2;via ST\u001b\\")
        assertEquals("via ST", t.snapshot().title)
        t.feed("\u001b[?25l\u001b[?2004h\u001b[?1h")
        t.snapshot().let {
            assertFalse(it.cursorVisible)
            assertTrue(it.bracketedPaste)
            assertTrue(it.applicationCursorKeys)
        }
        t.feed("\u001b[2;5H\u001b7\u001b[H\u001b8")
        assertEquals(1 to 4, t.cursor())
        t.feed("\u001b[3;1H\u001b[s\u001b[1;1H\u001b[u")
        assertEquals(2 to 0, t.cursor())
    }

    @Test
    fun decLineDrawing() {
        val t = emu()
        t.feed("\u001b(0lqk\u001b(Bx")
        assertEquals("┌─┐x", t.line(0))
    }

    @Test
    fun unchangedRowsKeepTheirInstance() {
        val t = emu(cols = 5, rows = 3)
        t.feed("a\r\nb")
        val first = t.snapshot()
        t.feed("c")
        val second = t.snapshot()
        assertSame(first.lines[0], second.lines[0])
        assertTrue(first.lines[1] !== second.lines[1])
        assertEquals("bc", second.lines[1].trimmedText())
    }

    @Test
    fun resizeShrinksIntoScrollbackAndGrowsBack() {
        val t = emu(cols = 6, rows = 4)
        t.feed("1\r\n2\r\n3\r\n4")
        t.resize(6, 2)
        assertEquals(listOf("3", "4"), t.snapshot().lines.map { it.trimmedText() })
        assertEquals(listOf("1", "2"), t.snapshot().scrollback.map { it.trimmedText() })
        assertEquals(1 to 1, t.cursor())
        t.resize(6, 4)
        assertEquals(listOf("1", "2", "3", "4"), t.snapshot().lines.map { it.trimmedText() })
        t.resize(3, 4)
        t.feed("xyz") // fills the narrowed last row, then wraps and scrolls
        assertEquals("4xy", t.line(2))
        assertEquals("z", t.line(3))
    }

    @Test
    fun styleRunsCoverTheRow() {
        val t = emu(cols = 6, rows = 1)
        t.feed("ab\u001b[31mcd\u001b[0mef")
        val runs = mutableListOf<Triple<Int, Int, Long>>()
        t.snapshot().lines[0].forEachStyleRun { s, e, st -> runs += Triple(s, e, st) }
        assertEquals(listOf(0 to 2, 2 to 4, 4 to 6), runs.map { it.first to it.second })
        assertEquals(CellStyle.indexed(1), CellStyle.fg(runs[1].third))
    }

    @Test
    fun keyEncoding() {
        assertEquals("\u001b[A", TerminalKeys.key(TerminalKeys.Key.Up, appCursor = false))
        assertEquals("\u001bOA", TerminalKeys.key(TerminalKeys.Key.Up, appCursor = true))
        assertEquals("\u001b[1;5C", TerminalKeys.key(TerminalKeys.Key.Right, appCursor = true, ctrl = true))
        assertEquals("\u0003", TerminalKeys.modified("c", ctrl = true, alt = false))
        assertEquals("\u001bx", TerminalKeys.modified("x", ctrl = false, alt = true))
        assertEquals("\u001b[200~a\rb\u001b[201~", TerminalKeys.paste("a\nb", bracketed = true))
    }
}
