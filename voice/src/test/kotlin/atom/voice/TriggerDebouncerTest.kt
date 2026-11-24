package atom.voice

import atom.voice.audio.TriggerDebouncer
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TriggerDebouncerTest {
    @Test
    fun singleFrameAboveThresholdDoesNotTrigger() {
        val d = TriggerDebouncer(threshold = 0.5f)
        assertFalse(d.onScore(0.9f, 0))
        assertFalse(d.onScore(0.1f, 80))
        assertFalse(d.onScore(0.9f, 160))
        assertFalse(d.onScore(0.4f, 240))
    }

    @Test
    fun twoConsecutiveFramesTriggerOnce() {
        val d = TriggerDebouncer(threshold = 0.5f)
        assertFalse(d.onScore(0.6f, 0))
        assertTrue(d.onScore(0.5f, 80))
        assertFalse(d.onScore(0.9f, 160))
    }

    @Test
    fun notReadyScoreBreaksStreak() {
        val d = TriggerDebouncer(threshold = 0.5f)
        assertFalse(d.onScore(0.9f, 0))
        assertFalse(d.onScore(-1f, 80))
        assertFalse(d.onScore(0.9f, 160))
        assertTrue(d.onScore(0.9f, 240))
    }

    @Test
    fun cooldownSuppressesForTwoSeconds() {
        val d = TriggerDebouncer(threshold = 0.5f)
        d.onScore(0.9f, 0)
        assertTrue(d.onScore(0.9f, 80))
        var t = 160L
        while (t < 2080) {
            assertFalse(d.onScore(0.99f, t), "triggered during cooldown at $t")
            t += 80
        }
        // After the cooldown a fresh pair of frames is needed.
        assertFalse(d.onScore(0.9f, 2080))
        assertTrue(d.onScore(0.9f, 2160))
    }

    @Test
    fun thresholdIsConfigurableAndResetClearsStreak() {
        val d = TriggerDebouncer(threshold = 0.8f)
        assertFalse(d.onScore(0.7f, 0))
        assertFalse(d.onScore(0.7f, 80))
        assertFalse(d.onScore(0.85f, 160))
        d.reset()
        assertFalse(d.onScore(0.85f, 240))
        assertTrue(d.onScore(0.85f, 320))
        d.threshold = 0.3f
        assertFalse(d.onScore(0.35f, 5000))
        assertTrue(d.onScore(0.35f, 5080))
    }
}
