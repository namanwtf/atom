package atom.automation.schedule

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/** When something repeats. Times are wall-clock in the device's current zone, so travel and DST follow the user. */
@Serializable
sealed interface Schedule {
    /** Next fire strictly after [after], or null when it will never fire again. */
    fun next(after: Instant, zone: ZoneId): Instant?

    @Serializable
    @SerialName("daily")
    data class Daily(val minuteOfDay: Int) : Schedule {
        override fun next(after: Instant, zone: ZoneId) = nextOnDays(after, zone, minuteOfDay, DayOfWeek.entries.toSet())
    }

    @Serializable
    @SerialName("weekdays")
    data class Weekdays(val minuteOfDay: Int) : Schedule {
        override fun next(after: Instant, zone: ZoneId) = nextOnDays(after, zone, minuteOfDay, WORKDAYS)
    }

    @Serializable
    @SerialName("weekly")
    data class Weekly(val days: Set<DayOfWeek>, val minuteOfDay: Int) : Schedule {
        override fun next(after: Instant, zone: ZoneId) =
            if (days.isEmpty()) null else nextOnDays(after, zone, minuteOfDay, days)
    }

    /** Every [minutes], counted from [anchorMillis] so restarts and reboots never drift the cadence. */
    @Serializable
    @SerialName("interval")
    data class Interval(val minutes: Int, val anchorMillis: Long) : Schedule {
        override fun next(after: Instant, zone: ZoneId): Instant {
            val period = minutes.coerceAtLeast(MIN_INTERVAL_MINUTES) * 60_000L
            val t = after.toEpochMilli()
            if (t < anchorMillis) return Instant.ofEpochMilli(anchorMillis)
            val k = (t - anchorMillis) / period + 1
            return Instant.ofEpochMilli(anchorMillis + k * period)
        }
    }

    @Serializable
    @SerialName("once")
    data class Once(val atMillis: Long) : Schedule {
        override fun next(after: Instant, zone: ZoneId): Instant? =
            Instant.ofEpochMilli(atMillis).takeIf { it.isAfter(after) }
    }

    @Serializable
    @SerialName("cron")
    data class CronSchedule(val expression: String) : Schedule {
        override fun next(after: Instant, zone: ZoneId) = Cron.parseOrNull(expression)?.next(after, zone)
    }

    companion object {
        const val MIN_INTERVAL_MINUTES = 15
        const val MAX_INTERVAL_MINUTES = 7 * 24 * 60
        val WORKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

        /** Nonexistent local times (spring forward) shift forward by the gap; repeated ones (fall back) run once. */
        internal fun nextOnDays(after: Instant, zone: ZoneId, minuteOfDay: Int, days: Set<DayOfWeek>): Instant? {
            val time = LocalTime.of((minuteOfDay / 60).coerceIn(0, 23), minuteOfDay % 60)
            var date: LocalDate = after.atZone(zone).toLocalDate()
            repeat(15) {
                if (date.dayOfWeek in days) {
                    val candidate = ZonedDateTime.of(date, time, zone).toInstant()
                    if (candidate.isAfter(after)) return candidate
                }
                date = date.plusDays(1)
            }
            return null
        }
    }
}

fun Schedule.nextRuns(after: Instant, zone: ZoneId, count: Int): List<Instant> {
    val out = ArrayList<Instant>(count)
    var cursor = after
    repeat(count) {
        val n = next(cursor, zone) ?: return out
        out += n
        cursor = n
    }
    return out
}

/** Validation for the editor and for tool input; null when the schedule can run. */
fun Schedule.problem(now: Instant = Instant.now()): String? = when (this) {
    is Schedule.Weekly -> if (days.isEmpty()) "Pick at least one day" else null
    is Schedule.Interval -> when {
        minutes < Schedule.MIN_INTERVAL_MINUTES -> "Shortest interval is ${Schedule.MIN_INTERVAL_MINUTES} minutes"
        minutes > Schedule.MAX_INTERVAL_MINUTES -> "Longest interval is 7 days"
        else -> null
    }
    is Schedule.Once -> if (atMillis <= now.toEpochMilli()) "That time has passed" else null
    is Schedule.CronSchedule -> runCatching { Cron.parse(expression); null }.getOrElse { it.message ?: "Invalid cron" }
    else -> null
}

object ScheduleText {
    private val DAY_TOKENS = mapOf(
        "mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "wed" to DayOfWeek.WEDNESDAY,
        "thu" to DayOfWeek.THURSDAY, "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY, "sun" to DayOfWeek.SUNDAY,
    )

    fun clock(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

    fun duration(minutes: Int): String = when {
        minutes % (24 * 60) == 0 -> (minutes / (24 * 60)).let { if (it == 1) "1 day" else "$it days" }
        minutes % 60 == 0 -> (minutes / 60).let { if (it == 1) "1 hour" else "$it hours" }
        minutes > 60 -> "${minutes / 60} h ${minutes % 60} min"
        else -> "$minutes min"
    }

    fun dayShort(day: DayOfWeek): String = day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

    /** Human summary, e.g. "Weekdays at 08:00". */
    fun describe(schedule: Schedule, zone: ZoneId = ZoneId.systemDefault()): String = when (schedule) {
        is Schedule.Daily -> "Daily at ${clock(schedule.minuteOfDay)}"
        is Schedule.Weekdays -> "Weekdays at ${clock(schedule.minuteOfDay)}"
        is Schedule.Weekly -> {
            val days = DayOfWeek.entries.filter { it in schedule.days }
            val label = when (days.toSet()) {
                DayOfWeek.entries.toSet() -> "Every day"
                setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> "Weekends"
                else -> days.joinToString(", ") { dayShort(it) }
            }
            "$label at ${clock(schedule.minuteOfDay)}"
        }
        is Schedule.Interval -> "Every ${duration(schedule.minutes)}"
        is Schedule.Once -> "Once at " + Instant.ofEpochMilli(schedule.atMillis).atZone(zone).let {
            "%s %02d:%02d".format(dayShort(it.dayOfWeek), it.hour, it.minute)
        }
        is Schedule.CronSchedule -> "Cron ${schedule.expression}"
    }

    /** Compact machine form accepted by [parse]; what tools and the rule compiler exchange. */
    fun encode(schedule: Schedule): String = when (schedule) {
        is Schedule.Daily -> "daily ${clock(schedule.minuteOfDay)}"
        is Schedule.Weekdays -> "weekdays ${clock(schedule.minuteOfDay)}"
        is Schedule.Weekly -> "weekly " + DayOfWeek.entries.filter { it in schedule.days }
            .joinToString(",") { dayShort(it).lowercase() } + " " + clock(schedule.minuteOfDay)
        is Schedule.Interval -> "every ${schedule.minutes}m"
        is Schedule.Once -> "at ${Instant.ofEpochMilli(schedule.atMillis)}"
        is Schedule.CronSchedule -> "cron ${schedule.expression}"
    }

    /**
     * Parses "daily 21:00", "weekdays 8am", "weekly mon,wed 09:30", "weekends 10:00", "every 30m", "every 2h",
     * "every 1d", "once in 45m", "in 2h", "at 2026-12-01T09:00:00Z", "cron 0 9 * * 1-5" or a bare cron expression.
     */
    fun parse(text: String, now: Instant = Instant.now()): Schedule {
        val t = text.trim().lowercase().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) throw IllegalArgumentException("Empty schedule")
        fun rest(prefix: String) = t.removePrefix(prefix).trim()
        return when {
            t.startsWith("cron ") -> Schedule.CronSchedule(Cron.parse(rest("cron")).expression)
            t.startsWith("@") -> Schedule.CronSchedule(Cron.parse(t).expression)
            t.startsWith("daily") || t.startsWith("every day") ->
                Schedule.Daily(timeOfDay(t.removePrefix("daily").removePrefix("every day").trim().removePrefix("at").trim()))
            t.startsWith("weekdays") -> Schedule.Weekdays(timeOfDay(rest("weekdays").removePrefix("at").trim()))
            t.startsWith("weekends") ->
                Schedule.Weekly(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), timeOfDay(rest("weekends").removePrefix("at").trim()))
            t.startsWith("weekly") -> {
                val body = rest("weekly")
                val timeStart = body.indexOfFirst { it.isDigit() }
                if (timeStart < 0) throw IllegalArgumentException("Weekly schedule needs a time")
                Schedule.Weekly(days(body.substring(0, timeStart)), timeOfDay(body.substring(timeStart)))
            }
            t.startsWith("every ") -> Schedule.Interval(durationMinutes(rest("every")), now.toEpochMilli())
            t.startsWith("once in ") || t.startsWith("in ") -> {
                val d = durationMinutes(t.removePrefix("once in ").removePrefix("in ").trim())
                Schedule.Once(now.toEpochMilli() + d * 60_000L)
            }
            t.startsWith("at ") || t.startsWith("once at ") ->
                Schedule.Once(Instant.parse(text.trim().substringAfter("at ").trim().uppercase()).toEpochMilli())
            t.split(' ').size == 5 -> Schedule.CronSchedule(Cron.parse(t).expression)
            else -> throw IllegalArgumentException("Unrecognised schedule '$text'")
        }
    }

    fun parseOrNull(text: String, now: Instant = Instant.now()): Schedule? = runCatching { parse(text, now) }.getOrNull()

    fun days(text: String): Set<DayOfWeek> {
        val out = mutableSetOf<DayOfWeek>()
        for (token in text.lowercase().split(',', ' ', '/').map { it.trim() }.filter { it.isNotEmpty() && it != "and" && it != "on" }) {
            when (token) {
                "weekdays" -> out += Schedule.WORKDAYS
                "weekends", "weekend" -> out += setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
                "daily", "everyday" -> out += DayOfWeek.entries
                else -> {
                    if ('-' in token) {
                        val (a, b) = token.split('-', limit = 2).map { day(it) }
                        var d = a
                        while (true) {
                            out += d
                            if (d == b) break
                            d = d.plus(1)
                        }
                    } else {
                        out += day(token)
                    }
                }
            }
        }
        if (out.isEmpty()) throw IllegalArgumentException("No days in '$text'")
        return out
    }

    private fun day(token: String): DayOfWeek =
        DAY_TOKENS[token.take(3)] ?: token.toIntOrNull()?.let { DayOfWeek.of(if (it == 0) 7 else it.coerceIn(1, 7)) }
            ?: throw IllegalArgumentException("Unknown day '$token'")

    /** "21:00", "9", "9am", "9:30 pm", "2130", "noon", "midnight" → minute of day. */
    fun timeOfDay(text: String): Int {
        val t = text.trim().lowercase().replace(".", "")
        when (t) {
            "noon" -> return 12 * 60
            "midnight" -> return 0
        }
        val m = Regex("^(\\d{1,2})(?::?(\\d{2}))?\\s*(am|pm|a|p)?$").find(t)
            ?: throw IllegalArgumentException("Unrecognised time '$text'")
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        when (m.groupValues[3]) {
            "am", "a" -> if (h == 12) h = 0
            "pm", "p" -> if (h < 12) h += 12
        }
        if (h !in 0..23 || min !in 0..59) throw IllegalArgumentException("Time out of range '$text'")
        return h * 60 + min
    }

    /** "30m", "30 min", "2h", "1.5h", "1d", "1 week", "90" (minutes). */
    fun durationMinutes(text: String): Int {
        val t = text.trim().lowercase()
        val m = Regex("^(\\d+(?:\\.\\d+)?)\\s*(m|min|mins|minute|minutes|h|hr|hrs|hour|hours|d|day|days|w|week|weeks)?$").find(t)
            ?: throw IllegalArgumentException("Unrecognised duration '$text'")
        val n = m.groupValues[1].toDouble()
        val factor = when (m.groupValues[2]) {
            "", "m", "min", "mins", "minute", "minutes" -> 1.0
            "h", "hr", "hrs", "hour", "hours" -> 60.0
            "d", "day", "days" -> 1440.0
            else -> 10080.0
        }
        return (n * factor).toInt().also { if (it <= 0) throw IllegalArgumentException("Duration must be positive") }
    }

    fun relative(instant: Instant, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
        val z = instant.atZone(zone)
        val today = now.atZone(zone).toLocalDate()
        val clock = "%02d:%02d".format(z.hour, z.minute)
        val day = when (z.toLocalDate()) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            today.minusDays(1) -> "Yesterday"
            else -> if (Duration.between(now, instant).abs().toDays() < 6) dayShort(z.dayOfWeek)
            else "%s %d %s".format(dayShort(z.dayOfWeek), z.dayOfMonth, z.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH))
        }
        return "$day $clock"
    }
}
