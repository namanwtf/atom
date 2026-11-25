package atom.linux.term

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimpleTranscriptTest {
    private var now = 1_000L
    private fun transcript() = SimpleTranscript(columns = 40, clock = { now })
    private fun SimpleTranscript.feed(s: String) = feed(s.toByteArray(Charsets.UTF_8))
    private val m0 = SimpleTranscript.marker(0)

    @Test
    fun dropsEverythingBeforeTheFirstMarker() {
        val t = transcript()
        t.feed("Welcome to Alpine!\r\n# PS1=... setup line echo\r\n")
        assertFalse(t.isReady)
        t.feed(m0)
        assertTrue(t.isReady)
        assertTrue(t.snapshot().isEmpty())
    }

    @Test
    fun splitsCommandsIntoBlocksWithExitCodes() {
        val t = transcript()
        t.feed(m0)
        t.submit("echo hi")
        assertTrue(t.isRunning)
        t.feed("echo hi\r\nhi\r\n")
        now = 1_500L
        t.feed(m0)
        t.submit("false")
        t.feed("false\r\n" + SimpleTranscript.marker(1))
        val blocks = t.snapshot()
        assertEquals(2, blocks.size)
        assertEquals("echo hi", blocks[0].command)
        assertEquals("hi", blocks[0].outputText()) // the echoed command line is dropped
        assertEquals(0, blocks[0].exitCode)
        assertEquals(1_500L, blocks[0].finishedAtMs)
        assertFalse(blocks[0].running)
        assertEquals(1, blocks[1].exitCode)
        assertEquals("", blocks[1].outputText())
        assertFalse(t.isRunning)
    }

    @Test
    fun markerSplitAcrossReadsAndFalseStarts() {
        val t = transcript()
        t.feed(m0)
        t.submit("printf x")
        val bytes = ("printf x\r\n\u001b[31mred\u001b[0m\r\n\u001b]0;title\u0007\u001b]7771;1\u0007x not a marker\r\n" + SimpleTranscript.marker(42)).toByteArray()
        for (b in bytes) t.feed(byteArrayOf(b))
        val b = t.snapshot().single()
        assertEquals(42, b.exitCode)
        val text = b.outputText()
        assertTrue(text.startsWith("red"), text)
        assertTrue(text.contains("x not a marker"), text)
        // colour survived into the rendered row
        assertEquals(atom.linux.term.CellStyle.indexed(1), atom.linux.term.CellStyle.fg(b.rows[0].styleAt(0)))
    }

    @Test
    fun markerTerminatedByStringTerminator() {
        val t = transcript()
        t.feed("\u001b]7770;0\u001b\\")
        assertTrue(t.isReady)
    }

    @Test
    fun progressBarsCollapseToTheirFinalState() {
        val t = transcript()
        t.feed(m0)
        t.submit("download")
        t.feed("download\r\n")
        t.feed("Downloading  10%\rDownloading  50%\rDownloading 100%\r\n")
        // apt/pip style: redraw the previous line with cursor-up + erase
        t.feed("step 1/3\r\n\u001b[1A\u001b[2Kstep 3/3\r\ndone\r\n")
        t.feed(m0)
        assertEquals(listOf("Downloading 100%", "step 3/3", "done"), t.snapshot().single().rows.map { it.trimmedText() })
    }

    @Test
    fun outputWithoutACommandBecomesItsOwnBlock() {
        val t = transcript()
        t.feed(m0)
        t.feed("[1]+  Done   sleep 1\r\n")
        val note = t.snapshot().single()
        assertNull(note.command)
        assertEquals("[1]+  Done   sleep 1", note.outputText())
        t.submit("ls")
        t.feed("ls\r\nfile\r\n$m0")
        val blocks = t.snapshot()
        assertEquals(2, blocks.size)
        assertFalse(blocks[0].running)
        assertEquals("file", blocks[1].outputText())
    }

    @Test
    fun inputWhileRunningStaysInTheSameBlock() {
        val t = transcript()
        t.feed(m0)
        t.submit("python3")
        t.feed("python3\r\n>>> ")
        assertTrue(t.isRunning)
        t.feed("1+1\r\n2\r\n>>> exit()\r\n$m0")
        val b = t.snapshot().single()
        assertEquals(listOf(">>> 1+1", "2", ">>> exit()"), b.rows.map { it.trimmedText() })
        assertEquals(0, b.exitCode)
    }

    @Test
    fun unchangedBlocksKeepTheirSnapshot() {
        val t = transcript()
        t.feed(m0)
        t.submit("a")
        t.feed("a\r\n1\r\n$m0")
        val first = t.snapshot()[0]
        t.submit("b")
        t.feed("b\r\n2\r\n")
        assertTrue(first === t.snapshot()[0])
    }
}
