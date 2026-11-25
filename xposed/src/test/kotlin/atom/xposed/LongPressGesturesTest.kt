package atom.xposed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LongPressGesturesTest {
    private val area = LongPressGestures.Area(width = 1000f, height = 2000f, barHeightPx = 60f, slopPx = 20f)

    @Test fun holdOnGestureBarFires() {
        val g = LongPressGestures()
        val at = g.onDown(500f, 1980f, 1_000, area, bar = true, twoFinger = false)
        assertEquals(1_500, at)
        g.onMove(505f, 1975f)
        assertNull(g.check(1_400), "too early")
        assertEquals(SearchGesture.GestureBar, g.check(1_500))
        assertNull(g.check(1_600), "fires once")
    }

    @Test fun swipeFromBarDoesNotFire() {
        val g = LongPressGestures()
        g.onDown(500f, 1980f, 0, area, bar = true, twoFinger = false)
        g.onMove(500f, 1800f)
        assertNull(g.check(600))
    }

    @Test fun outsideBarOrDisabledDoesNotStart() {
        val g = LongPressGestures()
        assertNull(g.onDown(500f, 1000f, 0, area, bar = true, twoFinger = false))
        assertNull(g.onDown(100f, 1990f, 0, area, bar = true, twoFinger = false), "corner = back/edge gesture")
        assertNull(g.onDown(500f, 1990f, 0, area, bar = false, twoFinger = false))
        assertNull(g.check(1_000))
    }

    @Test fun twoFingerHoldFires() {
        val g = LongPressGestures()
        assertNull(g.onDown(300f, 900f, 0, area, bar = false, twoFinger = true))
        assertEquals(650, g.onPointerDown(2, 600f, 900f, 150))
        g.onMove(302f, 901f, 598f, 903f)
        assertNull(g.check(600))
        assertEquals(SearchGesture.TwoFinger, g.check(650))
    }

    @Test fun pinchOrLateSecondFingerOrThirdFingerCancels() {
        val pinch = LongPressGestures()
        pinch.onDown(300f, 900f, 0, area, bar = false, twoFinger = true)
        pinch.onPointerDown(2, 600f, 900f, 100)
        pinch.onMove(300f, 900f, 700f, 900f)
        assertNull(pinch.check(700))

        val late = LongPressGestures()
        late.onDown(300f, 900f, 0, area, bar = false, twoFinger = true)
        assertNull(late.onPointerDown(2, 600f, 900f, 400))

        val three = LongPressGestures()
        three.onDown(300f, 900f, 0, area, bar = false, twoFinger = true)
        three.onPointerDown(2, 600f, 900f, 50)
        assertNull(three.onPointerDown(3, 700f, 900f, 80))
        assertNull(three.check(600))
    }

    @Test fun liftingCancels() {
        val g = LongPressGestures()
        g.onDown(300f, 900f, 0, area, bar = false, twoFinger = true)
        g.onPointerDown(2, 600f, 900f, 50)
        g.onPointerUp()
        assertNull(g.check(600))
    }
}
