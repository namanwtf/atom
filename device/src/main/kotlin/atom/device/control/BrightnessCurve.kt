package atom.device.control

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Android's brightness slider is perceptual (a hybrid log-gamma curve over the linear backlight setting), so "50%"
 * means half-way along the slider, not half the backlight. These convert between the two.
 */
object BrightnessCurve {
    private const val R = 0.5f
    private const val A = 0.17883277f
    private const val B = 0.28466892f
    private const val C = 0.55991073f

    fun percentToLinear(percent: Int, min: Int, max: Int): Int {
        val g = percent.coerceIn(0, 100) / 100f
        val ret = if (g <= R) (g / R) * (g / R) else exp((g - C) / A) + B
        val linear = min + (max - min) * (ret / 12f)
        return linear.roundToInt().coerceIn(min, max)
    }

    fun linearToPercent(value: Int, min: Int, max: Int): Int {
        if (max <= min) return 0
        val norm = ((value - min).toFloat() / (max - min)).coerceIn(0f, 1f) * 12f
        val ret = if (norm <= 1f) sqrt(norm) * R else A * ln(norm - B) + C
        return (ret * 100).roundToInt().coerceIn(0, 100)
    }
}
