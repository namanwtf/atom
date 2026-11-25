package atom.web.files

import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileOpsTest {
    @Test
    fun editRequiresUniqueMatch() {
        val text = "a = 1\nb = 2\na = 1\n"
        val dup = TextEdit.apply(text, "a = 1", "a = 3")
        assertIs<EditOutcome.Failed>(dup)
        assertTrue("2 times" in dup.reason && "lines 1, 3" in dup.reason, dup.reason)

        val unique = TextEdit.apply(text, "b = 2", "b = 5")
        assertIs<EditOutcome.Applied>(unique)
        assertEquals("a = 1\nb = 5\na = 1\n", unique.content)
        assertEquals(2, unique.firstLine)

        val all = TextEdit.apply(text, "a = 1", "a = 9", all = true)
        assertIs<EditOutcome.Applied>(all)
        assertEquals(2, all.replacements)
        assertEquals("a = 9\nb = 2\na = 9\n", all.content)
    }

    @Test
    fun editExplainsMisses() {
        assertIs<EditOutcome.Failed>(TextEdit.apply("x", "", "y"))
        assertIs<EditOutcome.Failed>(TextEdit.apply("x", "x", "x"))
        val ws = TextEdit.apply("    indented()\n", "  indented()  \n", "z")
        assertIs<EditOutcome.Failed>(ws)
        assertTrue("whitespace" in ws.reason)
        val crlf = TextEdit.apply("one\r\ntwo\r\n", "one\ntwo", "z")
        assertIs<EditOutcome.Failed>(crlf)
        assertTrue("CRLF" in crlf.reason)
        assertEquals(listOf(0, 2), TextEdit.occurrences("aaaa", "aa"))
    }

    @Test
    fun globMatching() {
        val name = Glob.matcher("*.kt")
        assertTrue(name("src/main/App.kt"))
        assertTrue(name("App.KT"))
        assertFalse(name("App.kts"))
        val path = Glob.matcher("src/**/*.kt")
        assertTrue(path("src/a/b/C.kt"))
        assertTrue(path("src/C.kt"))
        assertFalse(path("test/C.kt"))
        val alt = Glob.matcher("*.{jpg,png}")
        assertTrue(alt("x/photo.png"))
        assertFalse(alt("photo.gif"))
        assertTrue(Glob.matcher("IMG_????.jpg")("DCIM/IMG_0001.jpg"))
        assertFalse(Glob.matcher("IMG_????.jpg")("img_0001.jpg"))
        assertTrue(Glob.matcher("[ab]*.txt")("b-notes.txt"))
        assertTrue(Glob.matcher("[!ab]*.txt")("c.txt"))
        assertTrue(Glob.matcher("**")("deep/any/file"))
        assertTrue(Glob.matcher("a+b(1).txt")("a+b(1).txt"))
    }

    @Test
    fun linePagingNumbersAndCuts() {
        val text = (1..10).joinToString("\n") { "row $it" } + "\n"
        val p = LinePaging.page(text, 3, 4)
        assertEquals(3, p.firstLine)
        assertEquals(6, p.lastLine)
        assertEquals(10, p.totalLines)
        assertTrue(p.hasMore)
        assertTrue(p.text.startsWith("3\trow 3\n"), p.text)
        val long = LinePaging.page("x".repeat(5000), 1, 10, maxLineChars = 100)
        assertTrue("line cut, 5000 chars" in long.text)
        assertEquals("", LinePaging.page(text, 50, 10).text)
        val capped = LinePaging.page(text, 1, 100, maxChars = 20)
        assertTrue(capped.lastLine in 1..3)
    }

    @Test
    fun localFsFindAndGrep() = runTest {
        val root = kotlin.io.path.createTempDirectory("webfs").toFile()
        try {
            File(root, "a/b").mkdirs()
            File(root, "a/one.md").writeText("hello World\nsecond line\n")
            File(root, "a/b/two.md").writeText("nothing\nHELLO again\n")
            File(root, "a/b/bin.dat").writeBytes(byteArrayOf(0, 1, 2, 'h'.code.toByte()))
            val fs = LocalFs()
            val files = fs.files(root.path, 10, 100).sorted()
            assertEquals(listOf("a/b/bin.dat", "a/b/two.md", "a/one.md"), files)
            assertEquals(listOf("a/b/two.md", "a/one.md"), files.filter(Glob.matcher("*.md")))
            val hits = fs.grep(root.path, "hello", ignoreCase = true, limit = 10)
            assertEquals(2, hits.size)
            assertEquals(1, fs.grep(root.path, "hello", ignoreCase = false, limit = 10).size)
            fs.write(File(root, "new/c.txt").path, "x".toByteArray(), append = false)
            fs.write(File(root, "new/c.txt").path, "y".toByteArray(), append = true)
            assertEquals("xy", File(root, "new/c.txt").readText())
            assertTrue(fs.stat(File(root, "a").path)!!.isDir)
            assertEquals(null, fs.stat(File(root, "zzz").path))
            assertEquals(Sizes.looksBinary(File(root, "a/b/bin.dat").readBytes()), true)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun shellQuoting() {
        assertEquals("'it'\\''s here'", ShellFs.q("it's here"))
    }
}
