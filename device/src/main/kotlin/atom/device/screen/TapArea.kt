package atom.device.screen

import kotlin.math.abs

/**
 * "Tap by area": the model names a screen region instead of one element or one point. If an element from the
 * latest read_screen sits mostly inside that region, the best one is tapped (clickable first, then the one nearest
 * the region's centre, then the larger one); otherwise the centre of the region is tapped. Pure, so it is unit-tested.
 */
internal object TapArea {
    class Candidate(val id: String, val box: Box, val clickable: Boolean)

    private val bracketed = Regex("""\[\s*(-?\d+)\s*,\s*(-?\d+)\s*]\s*\[\s*(-?\d+)\s*,\s*(-?\d+)\s*]""")
    private val plain = Regex("""^\s*(-?\d+)\s*[, ]\s*(-?\d+)\s*[, ]\s*(-?\d+)\s*[, ]\s*(-?\d+)\s*$""")

    /** Parses "left,top,right,bottom" or "[left,top][right,bottom]" (pixels). Null when malformed or empty. */
    fun parse(text: String): Box? {
        val m = bracketed.find(text) ?: plain.find(text) ?: return null
        val (a, b, c, d) = m.destructured
        val l = a.toInt()
        val t = b.toInt()
        val r = c.toInt()
        val btm = d.toInt()
        val box = Box(minOf(l, r), minOf(t, btm), maxOf(l, r), maxOf(t, btm))
        return box.takeUnless { it.isEmpty }
    }

    /** The element to tap inside [region], or null when none lies at least half inside it. */
    fun best(region: Box, candidates: List<Candidate>): Candidate? {
        val cx = region.centerX
        val cy = region.centerY
        return candidates
            .filter { !it.box.isEmpty && insideShare(it.box, region) >= MIN_INSIDE_SHARE }
            .minWithOrNull(
                compareBy<Candidate> { if (it.clickable) 0 else 1 }
                    .thenBy { abs(it.box.centerX - cx) + abs(it.box.centerY - cy) }
                    .thenByDescending { it.box.width.toLong() * it.box.height },
            )
    }

    /** The centre of [region] clipped to [screen]; null when the region is entirely off screen. */
    fun centre(region: Box, screen: Box): Pair<Int, Int>? {
        if (!region.intersects(screen)) return null
        val c = region.clampTo(screen)
        return c.centerX to c.centerY
    }

    /** Fraction of [box]'s own area that lies inside [region]. */
    fun insideShare(box: Box, region: Box): Double {
        if (!box.intersects(region)) return 0.0
        val i = box.clampTo(region)
        val own = box.width.toDouble() * box.height
        return if (own <= 0) 0.0 else (i.width.toDouble() * i.height) / own
    }

    private const val MIN_INSIDE_SHARE = 0.5
}
