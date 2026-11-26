package atom.device.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TapAreaTest {
    private val screen = Box(0, 0, 1080, 2400)

    @Test
    fun parsesBothForms() {
        assertEquals(Box(100, 200, 300, 400), TapArea.parse("100,200,300,400"))
        assertEquals(Box(100, 200, 300, 400), TapArea.parse(" 100, 200 ,300,400 "))
        assertEquals(Box(100, 200, 300, 400), TapArea.parse("[100,200][300,400]"))
        // Corners in either order.
        assertEquals(Box(100, 200, 300, 400), TapArea.parse("300,400,100,200"))
        assertNull(TapArea.parse("100,200,100,400"))
        assertNull(TapArea.parse("a,b,c,d"))
        assertNull(TapArea.parse("1,2,3"))
    }

    @Test
    fun centreIsClippedToTheScreen() {
        assertEquals(200 to 300, TapArea.centre(Box(100, 200, 300, 400), screen))
        assertEquals(1040 to 300, TapArea.centre(Box(1000, 200, 1300, 400), screen))
        assertNull(TapArea.centre(Box(2000, 0, 2100, 100), screen))
    }

    @Test
    fun picksTheClickableElementInsideTheRegion() {
        val region = Box(0, 2000, 1080, 2400)
        val candidates = listOf(
            TapArea.Candidate("n1", Box(0, 0, 1080, 2400), clickable = true), // whole screen: mostly outside
            TapArea.Candidate("n2", Box(100, 2100, 500, 2200), clickable = false),
            TapArea.Candidate("n3", Box(600, 2100, 1000, 2200), clickable = true),
            TapArea.Candidate("n4", Box(100, 1000, 500, 1100), clickable = true), // outside
        )
        assertEquals("n3", TapArea.best(region, candidates)?.id)
    }

    @Test
    fun nearestToTheCentreWinsAmongEquals() {
        val region = Box(0, 0, 1000, 1000)
        val candidates = listOf(
            TapArea.Candidate("a", Box(0, 0, 100, 100), clickable = true),
            TapArea.Candidate("b", Box(450, 450, 550, 550), clickable = true),
        )
        assertEquals("b", TapArea.best(region, candidates)?.id)
    }

    @Test
    fun noElementMeansTapTheCentre() {
        assertNull(TapArea.best(Box(0, 0, 100, 100), listOf(TapArea.Candidate("x", Box(80, 80, 400, 400), clickable = true))))
        assertNull(TapArea.best(Box(0, 0, 100, 100), emptyList()))
    }

    @Test
    fun insideShare() {
        assertEquals(1.0, TapArea.insideShare(Box(10, 10, 20, 20), Box(0, 0, 100, 100)))
        assertEquals(0.5, TapArea.insideShare(Box(50, 0, 150, 100), Box(0, 0, 100, 100)))
        assertEquals(0.0, TapArea.insideShare(Box(200, 200, 300, 300), Box(0, 0, 100, 100)))
    }
}
