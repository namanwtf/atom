package atom.overlay

import atom.overlay.bubble.Area
import atom.overlay.bubble.BubbleGeometry
import atom.overlay.bubble.DismissMath
import atom.overlay.bubble.Docked
import atom.overlay.bubble.HoldMenuLayout
import atom.overlay.bubble.Pt
import atom.overlay.bubble.Range
import atom.overlay.bubble.Side
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BubbleGeometryTest {
    private val area = Area(width = 1080f, height = 2400f, top = 100f, bottom = 60f)
    private val size = 140f

    @Test
    fun rangeKeepsBubbleOffSystemBars() {
        val r = BubbleGeometry.range(area, size, margin = 10f)
        assertEquals(Range(10f, 110f, 1080f - 10f - size, 2400f - 60f - 10f - size), r)
    }

    @Test
    fun rangeNeverInvertsOnTinyScreens() {
        val r = BubbleGeometry.range(Area(100f, 100f), size, margin = 0f)
        assertTrue(r.maxX >= r.minX && r.maxY >= r.minY)
    }

    @Test
    fun clampPullsPointsInside() {
        val r = Range(0f, 100f, 900f, 2000f)
        assertEquals(Pt(0f, 100f), BubbleGeometry.clamp(Pt(-50f, -10f), r))
        assertEquals(Pt(900f, 2000f), BubbleGeometry.clamp(Pt(5000f, 9000f), r))
        assertEquals(Pt(400f, 700f), BubbleGeometry.clamp(Pt(400f, 700f), r))
    }

    @Test
    fun snapKeepsTheEdgeMarginAndSavedSpotRestoresExactly() {
        val density = 2.625f
        val window = 82f * density
        val pad = (window - 74f * density) / 2f
        val r = BubbleGeometry.range(area, window, -pad + 6f * density)
        // The 74dp orbit box ends up 6dp from the screen edge on either side.
        assertEquals(6f * density, r.minX + pad, 1e-3f)
        assertEquals(1080f - 6f * density, r.maxX + window - pad, 1e-3f)
        val docked = BubbleGeometry.snap(Pt(800f, 1500f), 0f, 0f, r, flingVelocity = 2000f)
        val placed = BubbleGeometry.place(docked, r)
        // Saving and restoring the dock puts the bubble back on exactly the same pixel.
        assertEquals(placed, BubbleGeometry.place(Docked(docked.side, docked.fraction), r))
        assertEquals(1500f, placed.y, 1e-3f)
    }

    @Test
    fun snapPicksNearestEdgeWithoutFling() {
        val r = Range(0f, 0f, 1000f, 2000f)
        assertEquals(Side.Left, BubbleGeometry.snap(Pt(300f, 500f), 0f, 0f, r, flingVelocity = 2000f).side)
        assertEquals(Side.Right, BubbleGeometry.snap(Pt(700f, 500f), 0f, 0f, r, flingVelocity = 2000f).side)
    }

    @Test
    fun snapFollowsAFastFlingAgainstTheNearestEdge() {
        val r = Range(0f, 0f, 1000f, 2000f)
        assertEquals(Side.Right, BubbleGeometry.snap(Pt(100f, 500f), 5000f, 0f, r, flingVelocity = 2000f).side)
        assertEquals(Side.Left, BubbleGeometry.snap(Pt(900f, 500f), -5000f, 0f, r, flingVelocity = 2000f).side)
    }

    @Test
    fun snapFractionIsClampedAndRoundTripsThroughPlace() {
        val r = Range(0f, 100f, 1000f, 2100f)
        val docked = BubbleGeometry.snap(Pt(950f, 600f), 0f, 0f, r, flingVelocity = 2000f)
        assertEquals(0.25f, docked.fraction, 1e-4f)
        assertEquals(Pt(1000f, 600f), BubbleGeometry.place(docked, r))
        val high = BubbleGeometry.snap(Pt(950f, -900f), 0f, -20000f, r, flingVelocity = 2000f)
        assertEquals(0f, high.fraction)
    }

    @Test
    fun placeSurvivesARotation() {
        val docked = Docked(Side.Left, 0.5f)
        val portrait = BubbleGeometry.place(docked, Range(0f, 0f, 900f, 2000f))
        val landscape = BubbleGeometry.place(docked, Range(0f, 0f, 2000f, 900f))
        assertEquals(Pt(0f, 1000f), portrait)
        assertEquals(Pt(0f, 450f), landscape)
    }

    @Test
    fun closenessIsLinearAndClamped() {
        val target = Pt(500f, 2000f)
        assertEquals(0f, DismissMath.closeness(Pt(500f, 1000f), target, reach = 460f))
        assertEquals(1f, DismissMath.closeness(target, target, reach = 460f))
        assertEquals(0.5f, DismissMath.closeness(Pt(500f, 1770f), target, reach = 460f), 1e-4f)
        assertEquals(0f, DismissMath.closeness(target, target, reach = 0f))
    }

    @Test
    fun dropInsideTheHitRadiusIsCaptured() {
        val target = Pt(540f, 2000f)
        val radius = 96f * 2.625f
        assertTrue(DismissMath.captured(Pt(540f, 2000f - radius + 1f), target, radius, wasCaptured = false))
        assertFalse(DismissMath.captured(Pt(540f, 2000f - radius - 1f), target, radius, wasCaptured = false))
    }

    @Test
    fun captureHasHysteresis() {
        val target = Pt(0f, 0f)
        val radius = 100f
        assertTrue(DismissMath.captured(Pt(90f, 0f), target, radius, wasCaptured = false))
        assertFalse(DismissMath.captured(Pt(120f, 0f), target, radius, wasCaptured = false))
        assertTrue(DismissMath.captured(Pt(120f, 0f), target, radius, wasCaptured = true))
        assertFalse(DismissMath.captured(Pt(140f, 0f), target, radius, wasCaptured = true))
    }

    @Test
    fun targetSitsAboveTheNavigationBar() {
        assertEquals(Pt(540f, 2400f - 60f - 200f), DismissMath.target(area, lift = 200f))
    }

    @Test
    fun targetFallsBackWhenNoNavigationInsetIsReported() {
        val bare = Area(1080f, 2400f)
        assertEquals(Pt(540f, 2400f - 126f - 315f), DismissMath.target(bare, lift = 315f, fallbackInset = 126f))
        // A reported inset wins over the fallback.
        assertEquals(Pt(540f, 2400f - 60f - 315f), DismissMath.target(area, lift = 315f, fallbackInset = 126f))
    }

    private fun assertMenuFits(points: List<Pt>, button: Float, area: Area) {
        val half = button / 2f
        for (p in points) {
            assertTrue(p.x - half >= area.left - 0.01f && p.x + half <= area.width - area.right + 0.01f, "x out of bounds: $p")
            assertTrue(p.y - half >= area.top - 0.01f && p.y + half <= area.height - area.bottom + 0.01f, "y out of bounds: $p")
        }
        for (i in points.indices) for (j in i + 1 until points.size) {
            val d = hypot(points[i].x - points[j].x, points[i].y - points[j].y)
            assertTrue(d >= button, "buttons $i and $j overlap: $d")
        }
    }

    @Test
    fun menuAnglesAreMinus52ZeroPlus52AroundTheMid() {
        assertEquals(listOf(-52.0, 0.0, 52.0), HoldMenuLayout.angles(Side.Left, 3))
        assertEquals(listOf(128.0, 180.0, 232.0), HoldMenuLayout.angles(Side.Right, 3))
    }

    @Test
    fun menuSpanNeverExceeds170Degrees() {
        val five = HoldMenuLayout.angles(Side.Left, 5)
        assertEquals(170.0, five.last() - five.first(), 1e-9)
        assertEquals(listOf(0.0), HoldMenuLayout.angles(Side.Left, 1))
        assertTrue(HoldMenuLayout.angles(Side.Left, 0).isEmpty())
    }

    @Test
    fun menuMirrorsOnTheRightEdge() {
        // Left edge: Hide up-right, Open chat straight right, Talk down-right.
        val left = HoldMenuLayout.place(Pt(100f, 1200f), Side.Left, 3, radius = 346f, button = 136f, area = area)
        assertTrue(left[0].x > 100f && left[0].y < 1200f, "Hide up-right: ${left[0]}")
        assertEquals(1200f, left[1].y, 0.5f)
        assertTrue(left[1].x > 100f)
        assertTrue(left[2].x > 100f && left[2].y > 1200f, "Talk down-right: ${left[2]}")
        // Right edge: Hide down-left, Open chat straight left, Talk up-left.
        val right = HoldMenuLayout.place(Pt(980f, 1200f), Side.Right, 3, radius = 346f, button = 136f, area = area)
        assertTrue(right[0].x < 980f && right[0].y > 1200f, "Hide down-left: ${right[0]}")
        assertEquals(1200f, right[1].y, 0.5f)
        assertTrue(right[1].x < 980f)
        assertTrue(right[2].x < 980f && right[2].y < 1200f, "Talk up-left: ${right[2]}")
    }

    @Test
    fun menuWindowIsCentredOnTheBubbleAndClampedOnScreen() {
        assertEquals(Pt(540f - 451f, 1200f - 451f), HoldMenuLayout.window(Pt(540f, 1200f), 902f, 1080f, 2400f))
        // At the right edge the window is pulled in; the bubble then sits off-centre inside it.
        val w = HoldMenuLayout.window(Pt(1000f, 100f), 902f, 1080f, 2400f)
        assertEquals(Pt(1080f - 902f, 0f), w)
        val local = Pt(1000f - w.x, 100f - w.y)
        assertTrue(local.x > 451f && local.y < 451f)
    }

    @Test
    fun menuButtonsStayInsideTheMenuWindow() {
        val density = 2.625f
        val window = 344f * density
        for (center in listOf(Pt(990f, 1890f), Pt(90f, 1890f), Pt(990f, 300f), Pt(90f, 300f))) {
            val side = if (center.x < 540f) Side.Left else Side.Right
            val origin = HoldMenuLayout.window(center, window, 1080f, 2400f)
            val points = HoldMenuLayout.place(center, side, 3, radius = 132f * density, button = 52f * density, area = area)
            val half = 26f * density
            for (p in points) {
                assertTrue(p.x - half >= origin.x - 0.5f && p.x + half <= origin.x + window + 0.5f, "x outside the window: $p for $center")
                assertTrue(p.y - half >= origin.y - 0.5f && p.y + half <= origin.y + window + 0.5f, "y outside the window: $p for $center")
            }
        }
    }

    @Test
    fun holdMenuFansInwardFromTheRightEdge() {
        val center = Pt(1010f, 1200f)
        val points = HoldMenuLayout.place(center, Side.Right, 3, radius = 230f, button = 140f, area = area)
        assertEquals(3, points.size)
        assertTrue(points.all { it.x < center.x })
        assertTrue(points[0].y > points[1].y && points[1].y > points[2].y, "Hide is the bottom button on the right edge")
        points.forEach { assertEquals(230f, hypot(it.x - center.x, it.y - center.y), 0.5f) }
        assertMenuFits(points, 140f, area)
    }

    @Test
    fun holdMenuFansInwardFromTheLeftEdge() {
        val center = Pt(70f, 1200f)
        val points = HoldMenuLayout.place(center, Side.Left, 3, radius = 230f, button = 140f, area = area)
        assertTrue(points.all { it.x > center.x })
        assertTrue(points[0].y < points[2].y, "Hide is the top button on the left edge")
        assertMenuFits(points, 140f, area)
    }

    @Test
    fun holdMenuTurnsAwayFromCorners() {
        for ((center, side) in listOf(
            Pt(70f, 180f) to Side.Left,
            Pt(1010f, 180f) to Side.Right,
            Pt(70f, 2270f) to Side.Left,
            Pt(1010f, 2270f) to Side.Right,
        )) {
            val points = HoldMenuLayout.place(center, side, 3, radius = 230f, button = 140f, area = area)
            assertMenuFits(points, 140f, area)
        }
    }

    @Test
    fun holdMenuHandlesEmptyAndSingle() {
        assertTrue(HoldMenuLayout.place(Pt(0f, 0f), Side.Left, 0, 100f, 50f, area).isEmpty())
        val one = HoldMenuLayout.place(Pt(70f, 1200f), Side.Left, 1, radius = 200f, button = 100f, area = area)
        assertEquals(Pt(270f, 1200f), one.single())
    }
}
