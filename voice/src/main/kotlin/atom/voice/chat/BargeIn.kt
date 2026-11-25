package atom.voice.chat

import android.content.Context
import atom.voice.audio.MicCapture

/**
 * Decides that the user started talking over Atom's answer. Pure and unit tested. Needs [requiredMs] of
 * consecutive voiced, loud-enough frames after a short [settleMs] (lets the echo canceller converge), so Atom's own
 * voice leaking through the canceller does not interrupt itself.
 */
class BargeInDetector(
    private val requiredMs: Long = 400,
    private val minLevel: Float = 0.42f,
    private val settleMs: Long = 400,
) {
    private var elapsedMs = 0L
    private var voicedRunMs = 0L

    fun reset() {
        elapsedMs = 0
        voicedRunMs = 0
    }

    /** Returns true once, when the user is clearly speaking. */
    fun onFrame(voiced: Boolean, level: Float, frameMs: Long): Boolean {
        elapsedMs += frameMs
        if (elapsedMs <= settleMs) return false
        voicedRunMs = if (voiced && level >= minLevel) voicedRunMs + frameMs else 0
        return voicedRunMs >= requiredMs
    }
}

/** Listens through the echo canceller while Atom speaks; calls back once when the user talks over it. */
internal object BargeInMonitor {
    /** Voice barge-in is offered only where the platform echo canceller exists; otherwise tap to interrupt. */
    fun supported(): Boolean = MicCapture.echoCancellerAvailable()

    suspend fun listen(context: Context, onBargeIn: () -> Unit) {
        val detector = BargeInDetector()
        MicCapture.capture(context, echoCancel = true) { _, voiced, level ->
            if (detector.onFrame(voiced, level, MicCapture.FRAME_MS)) {
                onBargeIn()
                false
            } else {
                true
            }
        }
    }
}
