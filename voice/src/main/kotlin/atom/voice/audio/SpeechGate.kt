package atom.voice.audio

import kotlin.math.sqrt

/**
 * Cheap gate in front of the neural models: a noise-floor-adaptive energy detector plus a zero-crossing-rate
 * check. One pass over the frame for the mean (DC offset) and one for energy/ZCR; no allocation per frame.
 *
 * A frame is "speech-like" when its RMS is [openRatio] times above the tracked noise floor (and above
 * [minRms]) and its zero-crossing rate lies in the voiced-speech band ([minZcr]..[maxZcr]); broadband hiss has
 * a ZCR near 0.5 and hum/rumble near 0. The gate stays open for [hangoverFrames] frames after the last
 * speech-like frame (13 x 80 ms ~ 1 s).
 */
class SpeechGate(
    private val openRatio: Float = 3.0f,
    private val minRms: Float = 150f,
    private val minZcr: Float = 0.01f,
    private val maxZcr: Float = 0.30f,
    private val hangoverFrames: Int = 13,
    private val floorFallAlpha: Float = 0.25f,
    private val floorRiseAlpha: Float = 0.02f,
    private val floorRiseDuringSpeechAlpha: Float = 0.002f,
) {
    var noiseFloor: Float = -1f
        private set
    var isOpen: Boolean = false
        private set
    var lastRms: Float = 0f
        private set
    var lastZcr: Float = 0f
        private set

    private var hangover = 0

    fun reset() {
        noiseFloor = -1f
        isOpen = false
        hangover = 0
    }

    /** Feeds one frame; returns whether the gate is open after it. */
    fun process(frame: ShortArray, offset: Int = 0, length: Int = frame.size - offset): Boolean {
        if (length <= 0) return isOpen
        var sum = 0L
        for (i in offset until offset + length) sum += frame[i]
        val mean = sum.toDouble() / length

        var energy = 0.0
        var crossings = 0
        var prevPositive = frame[offset] - mean >= 0.0
        for (i in offset until offset + length) {
            val x = frame[i] - mean
            energy += x * x
            val positive = x >= 0.0
            if (positive != prevPositive) crossings++
            prevPositive = positive
        }
        val rms = sqrt(energy / length).toFloat()
        val zcr = crossings.toFloat() / length
        lastRms = rms
        lastZcr = zcr

        if (noiseFloor < 0f) noiseFloor = rms // first frame seeds the floor

        val speechLike = rms > minRms && rms > noiseFloor * openRatio && zcr >= minZcr && zcr <= maxZcr

        val alpha = when {
            speechLike -> floorRiseDuringSpeechAlpha // lets a sustained loud noise eventually close the gate
            rms < noiseFloor -> floorFallAlpha
            else -> floorRiseAlpha
        }
        noiseFloor += alpha * (rms - noiseFloor)

        if (speechLike) {
            hangover = hangoverFrames
            isOpen = true
        } else if (hangover > 0) {
            hangover--
            isOpen = hangover > 0
        } else {
            isOpen = false
        }
        return isOpen
    }
}
