package atom.overlay

import atom.overlay.hey.HeyMotion
import atom.overlay.hey.HeyWaves
import atom.overlay.hey.Wave
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeyMotionTest {
    @Test
    fun cometsLapTheEdgeHalfATurnApart() {
        assertEquals(0f, HeyMotion.cometHead(0, 0))
        assertEquals(0.5f, HeyMotion.cometHead(0, 1))
        assertEquals(0.25f, HeyMotion.cometHead(HeyMotion.LAP_MS / 4, 0), 1e-4f)
        // A whole lap brings them back.
        assertEquals(HeyMotion.cometHead(123, 0), HeyMotion.cometHead(123 + HeyMotion.LAP_MS, 0), 1e-4f)
        assertEquals(0.75f, HeyMotion.cometHead(HeyMotion.LAP_MS / 4, 1), 1e-4f)
    }

    @Test
    fun aWaveRipplesInwardThenEnds() {
        val wave = Wave(startMs = 1_000, strength = 0.8f)
        assertEquals(0f, HeyMotion.waveProgress(wave, 1_000))
        assertEquals(0.5f, HeyMotion.waveProgress(wave, 1_000 + HeyMotion.WAVE_MS / 2)!!, 1e-4f)
        assertNull(HeyMotion.waveProgress(wave, 1_000 + HeyMotion.WAVE_MS))
    }

    @Test
    fun eachLoudSyllableSendsAWaveAndSilenceNone() {
        val waves = HeyWaves()
        var t = 0L
        // Silence and murmur: nothing.
        for (level in listOf(0f, 0.05f, 0.1f, 0.2f)) waves.onLevel(level, t.also { t += 100 })
        assertTrue(waves.waves.isEmpty())
        // A syllable: loud, then quiet, then loud again.
        waves.onLevel(0.7f, t.also { t += 100 })
        assertEquals(1, waves.waves.size)
        waves.onLevel(0.72f, t.also { t += 100 }) // still the same syllable
        assertEquals(1, waves.waves.size)
        waves.onLevel(0.1f, t.also { t += 100 })
        waves.onLevel(0.8f, t.also { t += 100 })
        assertEquals(2, waves.waves.size)
    }

    @Test
    fun wavesAreSpacedCappedAndPruned() {
        val waves = HeyWaves()
        waves.onWords(0)
        waves.onWords(50) // too soon after the last
        assertEquals(1, waves.waves.size)
        for (i in 1..10) waves.onWords(i * HeyWaves.MIN_GAP_MS)
        assertEquals(HeyWaves.MAX, waves.waves.size)
        assertTrue(waves.prune(10 * HeyWaves.MIN_GAP_MS))
        assertEquals(false, waves.prune(100_000))
        assertTrue(waves.waves.isEmpty())
    }
}
