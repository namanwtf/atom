package atom.automation.schedule

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * A nightly window with no runs. A run due inside it is deferred to the window's end, so a burst of interval runs
 * overnight collapses into one in the morning instead of waking the phone repeatedly.
 */
@Serializable
data class QuietHours(
    val enabled: Boolean = false,
    val startMinute: Int = 23 * 60,
    val endMinute: Int = 7 * 60 + 30,
) {
    fun contains(instant: Instant, zone: ZoneId): Boolean {
        if (!enabled || startMinute == endMinute) return false
        val z = instant.atZone(zone)
        val m = z.hour * 60 + z.minute
        return if (startMinute < endMinute) m in startMinute until endMinute else m >= startMinute || m < endMinute
    }

    /** [instant] itself when outside the window, otherwise the moment the window ends. */
    fun adjust(instant: Instant, zone: ZoneId): Instant {
        if (!contains(instant, zone)) return instant
        val z = instant.atZone(zone)
        val m = z.hour * 60 + z.minute
        val endDate = if (startMinute > endMinute && m >= startMinute) z.toLocalDate().plusDays(1) else z.toLocalDate()
        return ZonedDateTime.of(endDate, LocalTime.of(endMinute / 60, endMinute % 60), zone).toInstant()
    }

    fun label(): String = if (!enabled) "Off" else "${ScheduleText.clock(startMinute)}–${ScheduleText.clock(endMinute)}"
}

enum class MissedRunPolicy {
    /** Run once on wake-up when the missed slot is less than [CATCH_UP_WINDOW_MS] old. */
    CatchUp,
    Skip,
    ;

    companion object {
        const val CATCH_UP_WINDOW_MS = 3 * 60 * 60 * 1000L

        /** Alarms can arrive a little late under Doze; that is not a missed run. */
        const val ON_TIME_GRACE_MS = 10 * 60 * 1000L
    }
}

object Planner {
    /** The next time to fire after [after], with quiet hours applied. */
    fun next(schedule: Schedule, quiet: QuietHours, after: Instant, zone: ZoneId): Instant? =
        schedule.next(after, zone)?.let { quiet.adjust(it, zone) }

    fun preview(schedule: Schedule, quiet: QuietHours, after: Instant, zone: ZoneId, count: Int = 5): List<Instant> {
        val out = ArrayList<Instant>(count)
        var cursor = after
        while (out.size < count) {
            val n = next(schedule, quiet, cursor, zone) ?: break
            if (out.lastOrNull() != n) out += n
            cursor = n
        }
        return out
    }

    enum class Decision { RunNow, Skip }

    /** What to do with a slot that was due at [dueAt] when we are only now at [now]. */
    fun missed(policy: MissedRunPolicy, dueAt: Instant, now: Instant): Decision {
        val late = now.toEpochMilli() - dueAt.toEpochMilli()
        return when {
            late <= MissedRunPolicy.ON_TIME_GRACE_MS -> Decision.RunNow
            policy == MissedRunPolicy.CatchUp && late <= MissedRunPolicy.CATCH_UP_WINDOW_MS -> Decision.RunNow
            else -> Decision.Skip
        }
    }
}
