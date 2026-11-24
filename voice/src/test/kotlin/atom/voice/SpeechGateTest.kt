package atom.voice

import atom.voice.audio.SpeechGate
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeechGateTest {
    private val n = 1280
    private val sr = 16_000.0
    private var t = 0L // running sample clock so tones stay phase-continuous across frames

    private fun frame(gen: (Long) -> Double): ShortArray =
        ShortArray(n) { gen(t++).coerceIn(-32768.0, 32767.0).toInt().toShort() }

    private fun white(rng: Random, amp: Int) = { _: Long -> rng.nextInt(-amp, amp + 1).toDouble() }
    private fun tone(hz: Double, amp: Double) = { s: Long -> amp * sin(2 * PI * hz * s / sr) }

    /** Voiced-speech-like: 150 Hz fundamental with two harmonics, plus a little noise. */
    private fun voiced(rng: Random, amp: Double) = { s: Long ->
        val x = 2 * PI * 150.0 * s / sr
        amp * (sin(x) + 0.5 * sin(2 * x) + 0.25 * sin(3 * x)) + rng.nextInt(-40, 41)
    }

    @Test
    fun silenceNeverOpens() {
        val gate = SpeechGate()
        repeat(100) { assertFalse(gate.process(ShortArray(n))) }
    }

    @Test
    fun steadyNoiseFloorDoesNotOpen() {
        val rng = Random(1)
        val gate = SpeechGate()
        repeat(200) { assertFalse(gate.process(frame(white(rng, 2000))), "white noise opened the gate") }
        val hum = SpeechGate()
        repeat(200) { assertFalse(hum.process(frame(tone(100.0, 2000.0))), "steady hum opened the gate") }
    }

    @Test
    fun dcOffsetAloneDoesNotOpen() {
        val gate = SpeechGate()
        repeat(50) { assertFalse(gate.process(ShortArray(n) { 3000 })) }
    }

    @Test
    fun speechBurstOpensThenHangsOverAboutOneSecond() {
        val rng = Random(2)
        val gate = SpeechGate()
        repeat(30) { assertFalse(gate.process(frame(white(rng, 60)))) }
        var opened = false
        repeat(6) { opened = gate.process(frame(voiced(rng, 4000.0))) || opened }
        assertTrue(opened, "speech-like burst did not open the gate")
        assertTrue(gate.isOpen)
        // Hangover: stays open ~1 s (12 more frames of 80 ms), then closes.
        repeat(12) { assertTrue(gate.process(frame(white(rng, 60))), "gate closed during hangover at frame $it") }
        assertFalse(gate.process(frame(white(rng, 60))))
        repeat(20) { assertFalse(gate.process(frame(white(rng, 60)))) }
    }

    @Test
    fun quietSpeechBelowAbsoluteMinimumIsIgnored() {
        val rng = Random(3)
        val gate = SpeechGate()
        repeat(20) { gate.process(ShortArray(n)) }
        repeat(10) { assertFalse(gate.process(frame(voiced(rng, 60.0)))) }
    }

    @Test
    fun noiseFloorAdaptsSoSustainedLoudNoiseEventuallyCloses() {
        val rng = Random(4)
        val gate = SpeechGate()
        repeat(20) { gate.process(frame(white(rng, 30))) }
        assertTrue(gate.process(frame(tone(150.0, 3000.0))), "sudden tone should look like speech at first")
        repeat(600) { gate.process(frame(tone(150.0, 3000.0))) }
        assertFalse(gate.isOpen, "a constant tone for ~48 s must be absorbed into the noise floor")
        // Once adapted, a clearly louder voiced burst still opens it.
        var opened = false
        repeat(4) { opened = gate.process(frame(voiced(rng, 16000.0))) || opened }
        assertTrue(opened)
    }
}
