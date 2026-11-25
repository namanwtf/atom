package atom.vdisplay

import atom.vdisplay.geom.Coords
import atom.vdisplay.geom.Point
import atom.vdisplay.geom.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoordsTest {
    private val display = Size(720, 1600)

    @Test fun fitLetterboxesSideways() {
        val fit = Coords.fit(display, Size(400, 400)) // display is taller: bars left and right
        assertEquals(0.25f, fit.scale)
        assertEquals(110f, fit.offsetX)
        assertEquals(0f, fit.offsetY)
    }

    @Test fun viewToDisplayRoundTrip() {
        val fit = Coords.fit(display, Size(400, 400))
        assertEquals(Point(360, 800), Coords.viewToContent(fit, 200f, 200f))
        assertEquals(Point(0, 0), Coords.viewToContent(fit, 110f, 0f))
        val (vx, vy) = Coords.contentToView(fit, 360, 800)
        assertEquals(200f, vx)
        assertEquals(200f, vy)
    }

    @Test fun touchesOnBarsAreIgnored() {
        val fit = Coords.fit(display, Size(400, 400))
        assertNull(Coords.viewToContent(fit, 50f, 200f))
        assertNull(Coords.viewToContent(fit, 350f, 200f))
    }

    @Test fun rescaleScreenshotToDisplay() {
        val shot = Coords.screenshotSize(Size(1080, 2400), 1080)
        assertEquals(Size(486, 1080), shot)
        assertEquals(Point(540, 1200), Coords.rescale(243, 540, shot, Size(1080, 2400)))
        assertEquals(Point(1079, 2399), Coords.rescale(10_000, 10_000, shot, Size(1080, 2400)), "clamped")
    }

    @Test fun screenshotNeverUpscales() = assertEquals(Size(720, 1000), Coords.screenshotSize(Size(720, 1000), 1080))

    @Test fun mainToHiddenScaling() =
        assertEquals(Point(360, 800), Coords.rescale(540, 1200, Size(1080, 2400), display))
}
