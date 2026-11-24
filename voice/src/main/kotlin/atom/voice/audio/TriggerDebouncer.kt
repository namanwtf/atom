package atom.voice.audio

/**
 * Turns per-frame classifier scores into discrete triggers: [consecutive] frames at or above [threshold] fire
 * once, then everything is ignored for [cooldownMs].
 */
class TriggerDebouncer(
    @Volatile var threshold: Float = 0.5f,
    private val consecutive: Int = 2,
    private val cooldownMs: Long = 2_000,
) {
    private var streak = 0
    private var cooldownUntil = Long.MIN_VALUE

    fun reset() {
        streak = 0
    }

    /** Returns true exactly when a trigger fires. A negative score means "not ready" and breaks the streak. */
    fun onScore(score: Float, nowMs: Long): Boolean {
        if (nowMs < cooldownUntil) {
            streak = 0
            return false
        }
        if (score < 0f || score < threshold) {
            streak = 0
            return false
        }
        streak++
        if (streak >= consecutive) {
            streak = 0
            cooldownUntil = nowMs + cooldownMs
            return true
        }
        return false
    }
}
