package atom.voice

import atom.core.ondevice.WakeModels
import atom.voice.engine.WakePipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WakePipelineTest {
    /** Fake models that make the data flow observable. */
    private class FakeModels : WakeModels {
        override val classifierFrames = 16
        var lastMelInput = FloatArray(0)
        var lastMelWindow = FloatArray(0)
        var lastEmbeddings = FloatArray(0)

        override fun melspectrogram(audio: FloatArray, out: FloatArray) {
            assertEquals(1760, audio.size)
            assertEquals(256, out.size)
            lastMelInput = audio.copyOf()
            out.fill(audio.last()) // every mel value = newest sample
        }

        override fun embedding(melWindow: FloatArray, out: FloatArray) {
            assertEquals(76 * 32, melWindow.size)
            lastMelWindow = melWindow.copyOf()
            out.fill(melWindow.last())
        }

        override fun classify(embeddings: FloatArray): Float {
            assertEquals(16 * 96, embeddings.size)
            lastEmbeddings = embeddings.copyOf()
            return 0.75f
        }

        override fun close() {}
    }

    private fun frame(v: Int) = ShortArray(1280) { v.toShort() }

    @Test
    fun notReadyUntilSixteenEmbeddingsThenScores() {
        val models = FakeModels()
        val p = WakePipeline(models)
        for (i in 1..15) assertEquals(WakePipeline.NOT_READY, p.process(frame(i)))
        assertEquals(0.75f, p.process(frame(16)))
        // Classifier sees embeddings oldest-first: frame k -> mel k/10+2 -> embedding value k/10+2.
        for (k in 1..16) assertEquals(k / 10f + 2f, models.lastEmbeddings[(k - 1) * 96], 1e-5f)
    }

    @Test
    fun melInputCarries480SamplesOfContextAndWindowSeededWithOnes() {
        val models = FakeModels()
        val p = WakePipeline(models)
        p.process(frame(7))
        assertTrue(models.lastMelInput.take(480).all { it == 0f })
        assertTrue(models.lastMelInput.drop(480).all { it == 7f })
        assertTrue(models.lastMelWindow.take(68 * 32).all { it == 1f }) // openWakeWord seeds with ones
        assertTrue(models.lastMelWindow.drop(68 * 32).all { it == 7f / 10f + 2f }) // x/10 + 2
        p.process(frame(9))
        assertTrue(models.lastMelInput.take(480).all { it == 7f })
        assertTrue(models.lastMelInput.drop(480).all { it == 9f })
    }

    @Test
    fun resetForgetsContext() {
        val models = FakeModels()
        val p = WakePipeline(models)
        repeat(20) { p.process(frame(3)) }
        p.reset()
        assertEquals(WakePipeline.NOT_READY, p.process(frame(4)))
        assertTrue(models.lastMelInput.take(480).all { it == 0f })
    }
}
