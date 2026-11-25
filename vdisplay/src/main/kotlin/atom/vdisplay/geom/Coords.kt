package atom.vdisplay.geom

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class Size(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "Size must be positive: ${width}x$height" }
    }
}

data class Point(val x: Int, val y: Int)

/** How content of [content] size is drawn inside a [viewport]: uniformly scaled by [scale] and centred. */
data class Fit(val content: Size, val viewport: Size, val scale: Float, val offsetX: Float, val offsetY: Float) {
    val drawnWidth: Float get() = content.width * scale
    val drawnHeight: Float get() = content.height * scale
}

/** Coordinate conversions between the hidden display, screenshots of it and the views that preview it. */
object Coords {
    /** Aspect-fit [content] into [viewport] (letterboxed, never cropped). */
    fun fit(content: Size, viewport: Size): Fit {
        val scale = min(viewport.width.toFloat() / content.width, viewport.height.toFloat() / content.height)
        return Fit(
            content, viewport, scale,
            offsetX = (viewport.width - content.width * scale) / 2f,
            offsetY = (viewport.height - content.height * scale) / 2f,
        )
    }

    /** A point in the preview view → display pixels; null when it falls on the letterbox bars. */
    fun viewToContent(fit: Fit, x: Float, y: Float): Point? {
        val cx = (x - fit.offsetX) / fit.scale
        val cy = (y - fit.offsetY) / fit.scale
        if (cx < 0f || cy < 0f || cx > fit.content.width || cy > fit.content.height) return null
        return Point(clamp(cx.roundToInt(), fit.content.width - 1), clamp(cy.roundToInt(), fit.content.height - 1))
    }

    fun contentToView(fit: Fit, x: Int, y: Int): Pair<Float, Float> = (fit.offsetX + x * fit.scale) to (fit.offsetY + y * fit.scale)

    /**
     * Rescales a point between two pixel spaces of the same picture (a downscaled screenshot → the display it was
     * taken from), clamped into [to].
     */
    fun rescale(x: Int, y: Int, from: Size, to: Size): Point = Point(
        clamp((x.toLong() * to.width / from.width).toInt(), to.width - 1),
        clamp((y.toLong() * to.height / from.height).toInt(), to.height - 1),
    )

    /** Screenshot size for [display] with its longest side at most [maxSide] (never upscaled). */
    fun screenshotSize(display: Size, maxSide: Int): Size {
        val longest = max(display.width, display.height)
        if (longest <= maxSide) return display
        val s = maxSide.toFloat() / longest
        return Size(max(1, (display.width * s).roundToInt()), max(1, (display.height * s).roundToInt()))
    }

    private fun clamp(v: Int, maxInclusive: Int) = v.coerceIn(0, max(0, maxInclusive))
}
