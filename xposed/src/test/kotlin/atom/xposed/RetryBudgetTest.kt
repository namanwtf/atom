package atom.xposed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RetryBudgetTest {
    @Test fun backoffGrowsThenStopsThenRecovers() {
        val b = RetryBudget(maxAttempts = 3, windowMs = 1_000, delaysMs = listOf(10, 100))
        assertEquals(10, b.nextDelay(0))
        b.consume(0)
        assertEquals(100, b.nextDelay(1))
        b.consume(1)
        assertEquals(100, b.nextDelay(2), "last delay repeats")
        b.consume(2)
        assertNull(b.nextDelay(3), "budget spent")
        assertEquals(100, b.nextDelay(1_000), "first attempt aged out")
        assertEquals(10, b.nextDelay(1_002), "whole window passed")
    }

    @Test fun windowSlides() {
        val b = RetryBudget(maxAttempts = 2, windowMs = 100, delaysMs = listOf(1, 2))
        b.consume(0)
        b.consume(50)
        assertNull(b.nextDelay(99))
        assertEquals(2, b.nextDelay(100), "first attempt aged out; one left in the window")
        assertEquals(1, b.used)
    }
}
