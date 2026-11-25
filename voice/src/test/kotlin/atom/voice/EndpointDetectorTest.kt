package atom.voice

import atom.voice.asr.Proofreading
import atom.voice.chat.BargeInDetector
import atom.voice.speech.AutoSendPause
import atom.voice.speech.EndpointDetector
import atom.voice.speech.EndpointDetector.Decision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EndpointDetectorTest {
    private val frame = 80L

    /** Feeds [ms] of frames, returns the first non-Continue decision (or Continue) and the time it happened. */
    private fun EndpointDetector.feed(startMs: Long, ms: Long, voiced: Boolean): Pair<Decision, Long> {
        var t = startMs
        while (t < startMs + ms) {
            t += frame
            val d = onFrame(voiced, t, frame)
            if (d != Decision.Continue) return d to t
        }
        return Decision.Continue to t
    }

    @Test
    fun sendsAfterTheConfiguredPause() {
        for (setting in listOf(AutoSendPause.OneSecond, AutoSendPause.OneAndHalf, AutoSendPause.Two, AutoSendPause.Three)) {
            val d = EndpointDetector(setting.millis, noSpeechTimeoutMs = 10_000, maxMs = 60_000)
            d.start(0)
            val (talk, t1) = d.feed(0, 1_000, voiced = true)
            assertEquals(Decision.Continue, talk)
            val (end, t2) = d.feed(t1, 5_000, voiced = false)
            assertEquals(Decision.EndOfSpeech, end, "setting $setting")
            val pause = t2 - t1
            assertTrue(pause >= setting.millis!! && pause < setting.millis!! + frame, "pause $pause for $setting")
        }
    }

    @Test
    fun neverMeansOnlyTheUserEnds() {
        val d = EndpointDetector(AutoSendPause.Never.millis, noSpeechTimeoutMs = null, maxMs = 60_000)
        d.start(0)
        d.feed(0, 1_000, voiced = true)
        assertEquals(Decision.Continue, d.feed(1_000, 20_000, voiced = false).first)
    }

    @Test
    fun handsFreeNeverFallsBackToTwoSeconds() {
        assertEquals(2_000L, EndpointDetector.pauseFor(AutoSendPause.Never, handsFree = true))
        assertNull(EndpointDetector.pauseFor(AutoSendPause.Never, handsFree = false))
        assertEquals(1_500L, EndpointDetector.pauseFor(AutoSendPause.OneAndHalf, handsFree = true))
    }

    @Test
    fun shortNoiseDoesNotArmThePause() {
        val d = EndpointDetector(1_000, noSpeechTimeoutMs = 5_000, maxMs = 60_000)
        d.start(0)
        d.feed(0, 160, voiced = true) // a cough: below the 240 ms minimum
        val (decision, t) = d.feed(160, 10_000, voiced = false)
        assertEquals(Decision.NoSpeech, decision)
        assertTrue(t >= 5_000)
        assertFalse(d.heardSpeech)
    }

    @Test
    fun liveTextKeepsTheTurnOpen() {
        val d = EndpointDetector(1_000, noSpeechTimeoutMs = 5_000, maxMs = 60_000)
        d.start(0)
        d.feed(0, 400, voiced = true)
        // VAD hears nothing, but the service keeps updating text.
        var t = 400L
        repeat(10) {
            t += 500
            d.onText(t)
            assertEquals(Decision.Continue, d.onFrame(false, t, frame))
        }
        assertEquals(Decision.EndOfSpeech, d.feed(t, 2_000, voiced = false).first)
    }

    @Test
    fun maxDurationAlwaysEnds() {
        val d = EndpointDetector(null, noSpeechTimeoutMs = null, maxMs = 60_000)
        d.start(0)
        val (decision, t) = d.feed(0, 70_000, voiced = true)
        assertEquals(Decision.MaxDuration, decision)
        assertTrue(t in 60_000..60_080)
    }

    @Test
    fun bargeInNeedsSustainedLoudSpeech() {
        val b = BargeInDetector(requiredMs = 400, minLevel = 0.4f, settleMs = 400)
        // Settling: even loud speech is ignored.
        repeat(5) { assertFalse(b.onFrame(true, 0.9f, frame)) }
        // Quiet echo never triggers.
        repeat(20) { assertFalse(b.onFrame(true, 0.2f, frame)) }
        // Interrupted loud bursts do not add up.
        repeat(3) { assertFalse(b.onFrame(true, 0.8f, frame)) }
        assertFalse(b.onFrame(false, 0.8f, frame))
        // 400 ms continuous: triggers on the 5th frame.
        val hits = (1..5).map { b.onFrame(true, 0.8f, frame) }
        assertEquals(listOf(false, false, false, false, true), hits)
    }

    @Test
    fun proofreadingKeepsRawTextUnlessTheFixIsLight() {
        val raw = "open the settings for blue tooth"
        assertEquals("open the settings for Bluetooth", Proofreading.accept(raw, "open the settings for Bluetooth"))
        assertEquals(raw, Proofreading.accept(raw, null))
        assertEquals(raw, Proofreading.accept(raw, ""))
        assertEquals(raw, Proofreading.accept(raw, "Sure! Here is the corrected text: open the settings for Bluetooth, which lets you pair devices."))
        assertEquals(raw, Proofreading.accept(raw, "open"))
        assertEquals("open the settings for Bluetooth", Proofreading.accept(raw, "\"open the settings for Bluetooth\""))
    }
}
