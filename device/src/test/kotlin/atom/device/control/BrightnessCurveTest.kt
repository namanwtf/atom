package atom.device.control

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrightnessCurveTest {
    @Test
    fun endpointsMapToRange() {
        assertEquals(1, BrightnessCurve.percentToLinear(0, 1, 255))
        assertEquals(255, BrightnessCurve.percentToLinear(100, 1, 255))
    }

    @Test
    fun halfSliderIsFarBelowHalfBacklight() {
        val v = BrightnessCurve.percentToLinear(50, 1, 255)
        assertTrue(v in 15..30, "50% slider should be ~1/12 of the backlight range, was $v")
    }

    @Test
    fun roundTripsWithinOnePercent() {
        for (p in 0..100 step 5) {
            val back = BrightnessCurve.linearToPercent(BrightnessCurve.percentToLinear(p, 1, 4095), 1, 4095)
            assertTrue(kotlin.math.abs(back - p) <= 1, "$p% came back as $back%")
        }
    }

    @Test
    fun monotonic() {
        var last = -1
        for (p in 0..100) {
            val v = BrightnessCurve.percentToLinear(p, 1, 255)
            assertTrue(v >= last)
            last = v
        }
    }
}
