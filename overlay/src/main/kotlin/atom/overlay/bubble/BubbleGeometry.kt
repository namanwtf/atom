package atom.overlay.bubble

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class Side { Left, Right }

/** A resting place: the screen edge plus how far down the movable range it sits (0 top .. 1 bottom). */
data class Docked(val side: Side, val fraction: Float)

data class Pt(val x: Float, val y: Float)

/** Display size and the system-bar / cutout insets, all in px. */
data class Area(
    val width: Float,
    val height: Float,
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
)

/** Allowed positions of the bubble window's top-left corner. */
data class Range(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)

object BubbleGeometry {
    /** Seconds of travel a vertical fling adds before the bubble comes to rest. */
    private const val GLIDE_S = 0.12f

    /** Where a window of [size] may sit inside [area], [margin] off each inset edge (negative lets the padding overhang). */
    fun range(area: Area, size: Float, margin: Float): Range {
        val minX = area.left + margin
        val minY = area.top + margin
        return Range(
            minX = minX,
            minY = minY,
            maxX = max(minX, area.width - area.right - margin - size),
            maxY = max(minY, area.height - area.bottom - margin - size),
        )
    }

    fun clamp(p: Pt, r: Range): Pt = Pt(p.x.coerceIn(r.minX, r.maxX), p.y.coerceIn(r.minY, r.maxY))

    fun place(d: Docked, r: Range): Pt =
        Pt(if (d.side == Side.Left) r.minX else r.maxX, r.minY + d.fraction.coerceIn(0f, 1f) * (r.maxY - r.minY))

    /** The edge and height a drag released at [p] settles on; a fast sideways fling picks the edge it was thrown at. */
    fun snap(p: Pt, velocityX: Float, velocityY: Float, r: Range, flingVelocity: Float): Docked {
        val side = when {
            velocityX > flingVelocity -> Side.Right
            velocityX < -flingVelocity -> Side.Left
            p.x <= (r.minX + r.maxX) / 2f -> Side.Left
            else -> Side.Right
        }
        val y = (p.y + velocityY * GLIDE_S).coerceIn(r.minY, r.maxY)
        val span = r.maxY - r.minY
        return Docked(side, if (span > 0f) (y - r.minY) / span else 0f)
    }
}

object DismissMath {
    /** Leaving the target needs this much more distance than entering it, so the bubble does not flicker on the rim. */
    const val RELEASE_FACTOR = 1.3f

    fun distance(a: Pt, b: Pt): Float = hypot(a.x - b.x, a.y - b.y)

    /** 0 at [reach] or farther, 1 on the target, smoothstep in between so the beam gathers strength near the end. */
    fun proximity(bubble: Pt, target: Pt, reach: Float): Float {
        if (reach <= 0f) return 0f
        val t = (1f - distance(bubble, target) / reach).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun captured(bubble: Pt, target: Pt, radius: Float, wasCaptured: Boolean): Boolean =
        distance(bubble, target) <= if (wasCaptured) radius * RELEASE_FACTOR else radius

    /** Centre of the X target: horizontally centred, [lift] above the navigation bar. */
    fun target(area: Area, lift: Float): Pt = Pt(area.width / 2f, area.height - area.bottom - lift)
}

object HoldMenuLayout {
    private const val ROTATION_STEP = 5.0
    private const val MAX_ROTATION_STEPS = 24

    /**
     * Centres for [count] round buttons of diameter [button] on an arc of [radius] around the bubble [center],
     * fanning toward the screen interior (away from [side]). Index 0 is the top-most button. Near the top or bottom
     * edge the fan turns until every button fits inside [area]'s insets.
     */
    fun place(center: Pt, side: Side, count: Int, radius: Float, button: Float, area: Area, spreadDeg: Float = 52f): List<Pt> {
        if (count <= 0) return emptyList()
        val half = button / 2f
        val minX = area.left + half
        val maxX = area.width - area.right - half
        val minY = area.top + half
        val maxY = area.height - area.bottom - half
        val base = if (side == Side.Left) 0.0 else 180.0
        val dir = if (side == Side.Left) 1.0 else -1.0
        val mid = (count - 1) / 2.0

        fun layout(rotation: Double): List<Pt> = (0 until count).map { i ->
            val a = Math.toRadians(base + dir * (i - mid) * spreadDeg + rotation)
            Pt(center.x + radius * cos(a).toFloat(), center.y + radius * sin(a).toFloat())
        }

        fun slack(points: List<Pt>): Float = points.minOf { min(min(it.x - minX, maxX - it.x), min(it.y - minY, maxY - it.y)) }

        var best = layout(0.0)
        var bestSlack = slack(best)
        if (bestSlack >= 0f) return best
        for (step in 1..MAX_ROTATION_STEPS) {
            for (sign in intArrayOf(1, -1)) {
                val candidate = layout(sign * step * ROTATION_STEP)
                val s = slack(candidate)
                if (s >= 0f) return candidate
                if (s > bestSlack) {
                    best = candidate
                    bestSlack = s
                }
            }
        }
        return best.map { Pt(it.x.coerceIn(minX, max(minX, maxX)), it.y.coerceIn(minY, max(minY, maxY))) }
    }
}
