package atom.overlay.hey

/**
 * The listening light's motion, as plain numbers so it is unit-tested: two bright comets that chase each other round
 * the screen edge, and waves that ripple inward from the edge with the voice. Main thread only.
 */
internal object HeyMotion {
    /** One lap of the comets round the whole edge. */
    const val LAP_MS = 2_800L

    /** A comet's length as a share of the edge. */
    const val COMET_SHARE = 0.2f

    /** How long a wave takes to ripple inward and fade. */
    const val WAVE_MS = 900L

    /** How far inward a wave travels, in dp. */
    const val WAVE_DP = 72f

    /** Comet [index]'s head position round the edge (0..1) at [elapsedMs] since listening started. */
    fun cometHead(elapsedMs: Long, index: Int, count: Int = 2): Float {
        val lap = (elapsedMs % LAP_MS).toFloat() / LAP_MS
        return frac(lap + index.toFloat() / count)
    }

    /** A wave's progress (0 at the edge .. 1 fully inward and faded), or null once it is over. */
    fun waveProgress(wave: Wave, nowMs: Long): Float? {
        val age = nowMs - wave.startMs
        if (age < 0) return 0f
        if (age >= WAVE_MS) return null
        return age.toFloat() / WAVE_MS
    }

    fun frac(x: Float): Float = x - kotlin.math.floor(x)
}

/** A wave sent inward from the edge: when it started and how strong it is (0..1). */
internal data class Wave(val startMs: Long, val strength: Float)

/**
 * Turns the voice into waves: a rise in loudness (a syllable) or new recognised words each send one, at most every
 * [MIN_GAP_MS] and never more than [MAX] at once. Silence sends nothing.
 */
internal class HeyWaves {
    private val live = ArrayList<Wave>(MAX)
    private var trough = 0f
    private var lastSpawn = Long.MIN_VALUE / 2

    val waves: List<Wave> get() = live

    /** The recognizer's loudness (0..1). A clear rise from the last quiet point sends a wave. */
    fun onLevel(level: Float, nowMs: Long) {
        if (level < trough) trough = level
        if (level >= LOUD && level - trough >= RISE) {
            spawn(nowMs, level)
            trough = level
        }
        // Falling back towards quiet re-arms the next syllable.
        if (level < trough + FALL) trough = minOf(trough, level)
    }

    /** New words from the recognizer: also a wave, for recognizers that report no loudness. */
    fun onWords(nowMs: Long) = spawn(nowMs, WORDS_STRENGTH)

    /** Drops finished waves; true while any remains. */
    fun prune(nowMs: Long): Boolean {
        live.removeAll { HeyMotion.waveProgress(it, nowMs) == null }
        return live.isNotEmpty()
    }

    fun clear() {
        live.clear()
        trough = 0f
    }

    private fun spawn(nowMs: Long, strength: Float) {
        if (nowMs - lastSpawn < MIN_GAP_MS) return
        lastSpawn = nowMs
        if (live.size >= MAX) live.removeAt(0)
        live += Wave(nowMs, strength.coerceIn(0.3f, 1f))
    }

    companion object {
        const val MIN_GAP_MS = 160L
        const val MAX = 6
        const val LOUD = 0.35f
        const val RISE = 0.12f
        const val FALL = 0.05f
        const val WORDS_STRENGTH = 0.6f
    }
}
