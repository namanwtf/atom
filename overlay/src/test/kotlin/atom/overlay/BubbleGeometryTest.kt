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
    fun proximityGrowsSmoothlyTowardTheTarget() {
        val target = Pt(500f, 2000f)
        assertEquals(0f, DismissMath.proximity(Pt(500f, 1000f), target, reach = 600f))
        assertEquals(1f, DismissMath.proximity(target, target, reach = 600f))
        val far = DismissMath.proximity(Pt(500f, 1600f), target, reach = 600f)
        val near = DismissMath.proximity(Pt(500f, 1800f), target, reach = 600f)
        assertTrue(far in 0f..1f && near in 0f..1f)
        assertTrue(near > far)
        assertEquals(0.5f, DismissMath.proximity(Pt(500f, 1700f), target, reach = 600f), 1e-4f)
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
    fun holdMenuFansInwardFromTheRightEdge() {
        val center = Pt(1010f, 1200f)
        val points = HoldMenuLayout.place(center, Side.Right, 3, radius = 230f, button = 140f, area = area)
        assertEquals(3, points.size)
        assertTrue(points.all { it.x < center.x })
        assertTrue(points[0].y < points[1].y && points[1].y < points[2].y, "index 0 is the top button")
        points.forEach { assertEquals(230f, hypot(it.x - center.x, it.y - center.y), 0.5f) }
        assertMenuFits(points, 140f, area)
    }

    @Test
    fun holdMenuFansInwardFromTheLeftEdge() {
        val center = Pt(70f, 1200f)
        val points = HoldMenuLayout.place(center, Side.Left, 3, radius = 230f, button = 140f, area = area)
        assertTrue(points.all { it.x > center.x })
        assertTrue(points[0].y < points[2].y)
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
