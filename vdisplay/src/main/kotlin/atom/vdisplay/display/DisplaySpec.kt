package atom.vdisplay.display

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Size of the hidden display: the phone's aspect and dp layout (apps look exactly as on the phone, so what the agent
 * learns transfers), at [scale] of its pixels and density. Fewer pixels mean cheaper composition, smaller frames for
 * the viewfinder and smaller screenshots.
 */
data class DisplaySpec(val width: Int, val height: Int, val densityDpi: Int) {
    companion object {
        const val DEFAULT_SCALE = 2f / 3f
        private const val MIN_DPI = 120
        private const val MIN_SIDE = 320

        /** [realWidth]x[realHeight] at [realDpi] in portrait or landscape; the result keeps the orientation. */
        fun forPhone(realWidth: Int, realHeight: Int, realDpi: Int, scale: Float = DEFAULT_SCALE): DisplaySpec {
            require(realWidth > 0 && realHeight > 0 && realDpi > 0)
            val s = scale.coerceIn(0.25f, 1f)
            val minScale = max(MIN_SIDE.toFloat() / minOf(realWidth, realHeight), MIN_DPI.toFloat() / realDpi).coerceAtMost(1f)
            val k = max(s, minScale)
            return DisplaySpec(even(realWidth * k), even(realHeight * k), max(MIN_DPI, (realDpi * k).roundToInt()))
        }

        /** Video/graphics buffers want even dimensions. */
        private fun even(v: Float): Int = (v.roundToInt() / 2 * 2).coerceAtLeast(2)
    }
}
