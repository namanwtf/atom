package atom.vdisplay

import atom.vdisplay.display.DisplaySpec
import atom.vdisplay.display.FrameGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DisplaySpecTest {
    @Test fun twoThirdsKeepsDpLayout() {
        val s = DisplaySpec.forPhone(1080, 2400, 480)
        assertEquals(DisplaySpec(720, 1600, 320), s)
        // same width in dp as the phone: 1080/480 == 720/320
        assertEquals(1080f / 480, s.width.toFloat() / s.densityDpi)
    }

    @Test fun dimensionsAreEvenAndBounded() {
        val s = DisplaySpec.forPhone(1260, 2800, 560)
        assertEquals(0, s.width % 2)
        assertEquals(0, s.height % 2)
        val tiny = DisplaySpec.forPhone(480, 800, 160, scale = 0.25f)
        assertEquals(true, tiny.width >= 320 && tiny.densityDpi >= 120)
    }

    @Test fun frameGateCapsRateWithoutLosingLastFrame() {
        val g = FrameGate(10) // 100 ms
        assertIs<FrameGate.Decision.ShowNow>(g.offer(1_000))
        val later = assertIs<FrameGate.Decision.ShowAt>(g.offer(1_030))
        assertEquals(1_100, later.time)
        assertIs<FrameGate.Decision.AlreadyScheduled>(g.offer(1_060))
        g.shown(1_100)
        assertIs<FrameGate.Decision.ShowAt>(g.offer(1_150))
        g.shown(1_200)
        assertIs<FrameGate.Decision.ShowNow>(g.offer(1_400))
    }

    @Test fun frameGateNeverAboveSixty() {
        val g = FrameGate(1_000)
        g.offer(0)
        assertEquals(FrameGate.Decision.ShowAt(16), g.offer(1))
    }
}
