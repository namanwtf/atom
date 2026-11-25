package atom.characters

import atom.characters.png.Png
import atom.characters.png.PngException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PngTest {
    @Test
    fun crcMatchesKnownValues() {
        assertEquals(0xAE426082.toInt(), Png.crc("IEND".toByteArray()))
        assertEquals(0xCBF43926.toInt(), Png.crc("123456789".toByteArray()))
    }

    @Test
    fun writeThenReadRoundTrips() {
        val png = TestPngs.tiny()
        val chunks = Png.readChunks(png)
        assertEquals(listOf("IHDR", "IDAT", "IEND"), chunks.map { it.type })
        assertEquals(1 to 1, Png.dimensions(chunks))
        assertContentEquals(png, Png.write(chunks))
    }

    @Test
    fun corruptCrcIsRejectedWhenVerifying() {
        val png = TestPngs.tiny(listOf(Png.textChunk("note", "hello")))
        val idx = String(png, Charsets.ISO_8859_1).indexOf("hello")
        png[idx] = 'j'.code.toByte()
        assertFailsWith<PngException> { Png.readChunks(png) }
        val lenient = Png.readChunks(png, verifyCrc = false)
        assertFalse(lenient.any { it.type == "tEXt" })
        assertEquals(3, lenient.size)
    }

    @Test
    fun rejectsNonPngAndTruncated() {
        assertFailsWith<PngException> { Png.readChunks("hello".toByteArray()) }
        val png = TestPngs.tiny()
        assertFailsWith<PngException> { Png.readChunks(png.copyOf(png.size - 6)) }
    }

    @Test
    fun readsTextZTextAndCompressedIText() {
        val itxt = "chara".toByteArray() + byteArrayOf(0, 1, 0) + "en".toByteArray() + byteArrayOf(0) + byteArrayOf(0) +
            TestPngs.deflate("héllo ✓".toByteArray(Charsets.UTF_8))
        val ztxt = "Comment".toByteArray() + byteArrayOf(0, 0) + TestPngs.deflate("zipped".toByteArray())
        val png = TestPngs.tiny(listOf(Png.textChunk("Title", "plain"), Png.Chunk("zTXt", ztxt), Png.Chunk("iTXt", itxt)))
        val texts = Png.texts(Png.readChunks(png))
        assertEquals("plain", texts["Title"])
        assertEquals("zipped", texts["Comment"])
        assertEquals("héllo ✓", texts["chara"])
    }

    @Test
    fun replaceTextsDropsOldAndInsertsBeforeIend() {
        val png = TestPngs.tiny(listOf(Png.textChunk("Chara", "old"), Png.textChunk("Title", "keep")))
        val out = Png.replaceTexts(png, setOf("chara"), listOf("chara" to "new"))
        val chunks = Png.readChunks(out)
        assertEquals(listOf("IHDR", "tEXt", "IDAT", "tEXt", "IEND"), chunks.map { it.type })
        val texts = Png.texts(chunks)
        assertEquals("new", texts["chara"])
        assertEquals("keep", texts["Title"])
        assertTrue("Chara" !in texts)
    }
}

class UiLogicTest {
    @Test
    fun splitKeysKeepsRegexCommas() {
        assertEquals(listOf("cat", "/a,b/i", "dog"), atom.characters.ui.splitKeys(" cat, /a,b/i ,dog,, "))
    }

    @Test
    fun routesParse() {
        val r = atom.characters.ui.CharacterRoutes
        assertEquals(atom.characters.ui.CharacterRoutes.Parsed.Entry("c1", "e1"), r.parse(r.entry("c1", "e1")))
        assertEquals(atom.characters.ui.CharacterRoutes.Parsed.Detail("x"), r.parse(r.detail("x")))
        assertEquals(atom.characters.ui.CharacterRoutes.Parsed.Library, r.parse(r.LIBRARY))
        assertEquals(null, r.parse("other/thing"))
    }
}
