package atom.core.home

import java.time.Instant
import java.time.ZoneId

/** Sidebar sections, in display order. */
enum class ConversationGroup { Pinned, Today, Yesterday, Recent }

/**
 * Groups sidebar conversations: pinned first, then today (or running now), yesterday and everything older.
 * Day boundaries follow the phone's calendar, so a day with a clock change is still one section.
 */
object ConversationGroups {
    data class Bounds(val startOfToday: Long, val startOfYesterday: Long)

    fun bounds(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Bounds {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return Bounds(
            startOfToday = today.atStartOfDay(zone).toInstant().toEpochMilli(),
            startOfYesterday = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        )
    }

    fun groupOf(pinned: Boolean, running: Boolean, updatedAt: Long, bounds: Bounds): ConversationGroup = when {
        pinned -> ConversationGroup.Pinned
        running || updatedAt >= bounds.startOfToday -> ConversationGroup.Today
        updatedAt >= bounds.startOfYesterday -> ConversationGroup.Yesterday
        else -> ConversationGroup.Recent
    }

    /** Non-empty groups in display order; items keep their incoming order inside a group. */
    fun <T> group(
        items: List<T>,
        bounds: Bounds,
        pinned: (T) -> Boolean,
        running: (T) -> Boolean,
        updatedAt: (T) -> Long,
    ): List<Pair<ConversationGroup, List<T>>> {
        val byGroup = items.groupBy { groupOf(pinned(it), running(it), updatedAt(it), bounds) }
        return ConversationGroup.entries.mapNotNull { g -> byGroup[g]?.takeIf { it.isNotEmpty() }?.let { g to it } }
    }
}
