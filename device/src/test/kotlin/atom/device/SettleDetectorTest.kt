package atom.device

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettleDetectorTest {
    @Test
    fun staticScreenSettlesAfterInitialQuietWindow() = runTest {
        val d = SettleDetector()
        assertTrue(d.awaitSettle())
        assertEquals(500, currentTime)
    }

    @Test
    fun burstThenQuietSettlesShortlyAfterLastEvent() = runTest {
        val d = SettleDetector()
        launch { repeat(10) { delay(100); d.signal() } } // events at 100..1000ms
        assertTrue(d.awaitSettle())
        assertEquals(1000 + 350, currentTime)
    }

    @Test
    fun continuousChangesHitTheCap() = runTest {
        val d = SettleDetector()
        val job = launch { while (true) { delay(100); d.signal() } }
        assertFalse(d.awaitSettle())
        assertEquals(3000, currentTime)
        job.cancel()
    }

    @Test
    fun eventsBeforeTheCallDoNotCount() = runTest {
        val d = SettleDetector()
        repeat(5) { d.signal() }
        assertTrue(d.awaitSettle())
        assertEquals(500, currentTime)
    }

    @Test
    fun slowFirstReactionIsCaughtByLongerInitialWindow() = runTest {
        val d = SettleDetector()
        launch { delay(450); d.signal(); delay(200); d.signal() }
        assertTrue(d.awaitSettle())
        assertEquals(650 + 350, currentTime)
    }
}
