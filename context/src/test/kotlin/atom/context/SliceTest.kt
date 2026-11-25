package atom.context

import atom.context.search.Slice
import atom.context.search.likeArg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SliceTest {
    private val all = (1..45).toList()

    @Test
    fun firstPage() {
        val s = Slice.of(all, 0, 20)
        assertEquals((1..20).toList(), s.items)
        assertTrue(s.hasMore)
        assertEquals("Showing messages 1–20 of 45. More: call again with offset=20.", s.header("messages"))
    }

    @Test
    fun lastPage() {
        val s = Slice.of(all, 40, 20)
        assertEquals((41..45).toList(), s.items)
        assertFalse(s.hasMore)
        assertEquals("Showing messages 41–45 of 45.", s.header("messages"))
    }

    @Test
    fun pastTheEnd() = assertEquals("No more messages after offset 60.", Slice.of(all, 60, 20).header("messages"))

    @Test
    fun empty() = assertEquals("No events found.", Slice.of(emptyList<Int>(), 0, 20).header("events"))

    @Test
    fun unknownTotalAssumesMoreWhenPageIsFull() {
        val s = Slice(listOf(1, 2, 3), 0, null, 3)
        assertTrue(s.hasMore)
        assertEquals("Showing x 1–3. More: call again with offset=3.", s.header("x"))
    }

    @Test
    fun limitsAreClamped() {
        assertEquals(20, Slice.clampLimit(null))
        assertEquals(50, Slice.clampLimit(10_000))
        assertEquals(1, Slice.clampLimit(0))
        assertEquals(0, Slice.clampOffset(-5))
    }

    @Test
    fun renderJoinsHeaderAndLines() =
        assertEquals("Showing n 1–2 of 2.\n#1\n#2", Slice.of(listOf(1, 2), 0, 5).render("n") { "#$it" })

    @Test
    fun likeEscapesWildcards() = assertEquals("%50\\% off\\_now%", likeArg("50% off_now"))
}
