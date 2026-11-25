package atom.voice.speech

/**
 * Decides when a recording is over, from per-frame voice activity and (for streaming services) live text updates.
 * Pure and clock-injected so it is unit tested.
 *
 * - Before any speech: ends with [Decision.NoSpeech] after [noSpeechTimeoutMs] (null = wait forever).
 * - After speech: ends with [Decision.EndOfSpeech] once nothing was heard (and no text changed) for [pauseMs]
 *   (null = never; the user ends it by tapping or releasing).
 * - Always ends with [Decision.MaxDuration] at [maxMs].
 *
 * Speech counts only after [minSpeechMs] of voiced frames, so a cough or a door does not arm the pause timer.
 */
class EndpointDetector(
    private val pauseMs: Long?,
    private val noSpeechTimeoutMs: Long?,
    private val maxMs: Long,
    private val minSpeechMs: Long = 240,
) {
    enum class Decision { Continue, EndOfSpeech, NoSpeech, MaxDuration }

    private var startMs = -1L
    private var voicedMs = 0L
    private var lastActivityMs = -1L
    private var lastFrameMs = -1L

    /** True once real speech was heard. */
    var heardSpeech = false
        private set

    fun start(nowMs: Long) {
        startMs = nowMs
        voicedMs = 0
        lastActivityMs = nowMs
        lastFrameMs = nowMs
        heardSpeech = false
    }

    /** One audio frame of [frameMs] ending at [nowMs]; [voiced] is the VAD verdict. */
    fun onFrame(voiced: Boolean, nowMs: Long, frameMs: Long): Decision {
        if (startMs < 0) start(nowMs - frameMs)
        lastFrameMs = nowMs
        if (voiced) {
            voicedMs += frameMs
            if (voicedMs >= minSpeechMs) heardSpeech = true
            lastActivityMs = nowMs
        }
        return check(nowMs)
    }

    /** A streaming service changed its live text: the user is still talking even if the VAD missed it. */
    fun onText(nowMs: Long) {
        if (startMs < 0) start(nowMs)
        heardSpeech = true
        lastActivityMs = nowMs
    }

    fun check(nowMs: Long): Decision {
        if (startMs < 0) return Decision.Continue
        val elapsed = nowMs - startMs
        if (elapsed >= maxMs) return Decision.MaxDuration
        if (!heardSpeech) {
            val limit = noSpeechTimeoutMs ?: return Decision.Continue
            return if (elapsed >= limit) Decision.NoSpeech else Decision.Continue
        }
        val pause = pauseMs ?: return Decision.Continue
        return if (nowMs - lastActivityMs >= pause) Decision.EndOfSpeech else Decision.Continue
    }

    companion object {
        /**
         * The pause used for a given setting and situation. Hands-free audio chat always needs an end of turn, so
         * "Never" falls back to 2 s there; elsewhere "Never" means only the user ends a recording.
         */
        fun pauseFor(setting: AutoSendPause, handsFree: Boolean): Long? =
            setting.millis ?: if (handsFree) AutoSendPause.Two.millis else null
    }
}
