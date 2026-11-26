package atom.core.legacy

import atom.core.legacy.LegacyChunks.Chunk
import atom.core.legacy.LegacyChunks.Resolved
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyChunksTest {
    private fun ref(count: Int, length: Int) = "${LegacyChunks.PREFIX}$count:$length"

    @Test
    fun literalValuesAreReturnedWithoutReadingChunks() {
        var fetched = false
        val r = LegacyChunks.resolve("hello") { fetched = true; emptyList() }
        assertEquals(Resolved.Ok("hello"), r)
        assertFalse(fetched)
        assertNull(LegacyChunks.parseRef("hello"))
        assertNull(LegacyChunks.parseRef("@atom:chunk:v1:1:2"))
    }

    @Test
    fun joinsChunksInIndexOrderWhateverOrderTheyArrive() {
        val r = LegacyChunks.resolve(ref(3, 9)) { listOf(Chunk(2, "ghi"), Chunk(0, "abc"), Chunk(1, "def")) }
        assertEquals(Resolved.Ok("abcdefghi"), r)
        assertEquals(LegacyChunks.Ref(3, 9), LegacyChunks.parseRef(ref(3, 9)))
    }

    @Test
    fun aMissingChunkMakesTheValueCorruptAndEmpty() {
        val r = LegacyChunks.resolve(ref(3, 9)) { listOf(Chunk(0, "abc"), Chunk(2, "ghi")) }
        assertIs<Resolved.Corrupt>(r)
        assertEquals("", r.text)
    }

    @Test
    fun aGapInIndexesIsCorruptEvenWhenTheCountMatches() {
        val r = LegacyChunks.resolve(ref(2, 6)) { listOf(Chunk(0, "abc"), Chunk(2, "ghi")) }
        assertIs<Resolved.Corrupt>(r)
    }

    @Test
    fun duplicateOrExtraChunksAreCorrupt() {
        assertIs<Resolved.Corrupt>(LegacyChunks.resolve(ref(2, 6)) { listOf(Chunk(0, "abc"), Chunk(0, "abc")) })
        assertIs<Resolved.Corrupt>(LegacyChunks.resolve(ref(1, 3)) { listOf(Chunk(0, "abc"), Chunk(1, "def")) })
    }

    @Test
    fun noChunkRowsAtAllIsCorrupt() {
        assertIs<Resolved.Corrupt>(LegacyChunks.resolve(ref(2, 10)) { emptyList() })
    }

    @Test
    fun aMalformedReferenceIsCorruptNotLiteral() {
        assertIs<Resolved.Corrupt>(LegacyChunks.resolve(LegacyChunks.PREFIX + "x:y") { emptyList() })
        assertIs<Resolved.Corrupt>(LegacyChunks.resolve(LegacyChunks.PREFIX + "3") { emptyList() })
        assertIs<Resolved.Corrupt>(LegacyChunks.resolve(LegacyChunks.PREFIX + "-1:4") { emptyList() })
    }

    @Test
    fun aLengthMismatchIsOnlyAWarning() {
        val r = LegacyChunks.resolve(ref(2, 100)) { listOf(Chunk(0, "abc"), Chunk(1, "def")) }
        assertIs<Resolved.Ok>(r)
        assertEquals("abcdef", r.text)
        assertTrue(r.lengthMismatch)
    }

    @Test
    fun lengthCountsUtf16Units() {
        val emoji = "😀"
        val r = LegacyChunks.resolve(ref(2, 4)) { listOf(Chunk(0, emoji), Chunk(1, "ab")) }
        assertEquals(Resolved.Ok(emoji + "ab", lengthMismatch = false), r)
    }

    @Test
    fun anOversizedValueIsSkippedWithoutFetching() {
        var fetched = false
        val r = LegacyChunks.resolve(ref(100, 1_600_000), maxLength = 1_000_000) { fetched = true; emptyList() }
        assertEquals(Resolved.TooLarge(1_600_000), r)
        assertFalse(fetched)
    }

    @Test
    fun aReferenceThatUnderstatesItsLengthIsStillCapped() {
        val r = LegacyChunks.resolve(ref(2, 4), maxLength = 5) { listOf(Chunk(0, "abcd"), Chunk(1, "efgh")) }
        assertIs<Resolved.TooLarge>(r)
    }

    @Test
    fun zeroChunksIsTheEmptyString() {
        assertEquals(Resolved.Ok(""), LegacyChunks.resolve(ref(0, 0)) { emptyList() })
    }
}
