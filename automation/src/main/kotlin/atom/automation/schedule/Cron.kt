package atom.automation.schedule

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class CronException(message: String) : IllegalArgumentException(message)

/**
 * Standard 5-field cron (minute hour day-of-month month day-of-week) with lists, ranges, steps, month and day
 * names, `@daily`-style macros, and Vixie semantics: when both day fields are restricted a day matches either.
 *
 * Daylight saving: a fixed-time job whose local time falls in a spring-forward gap runs at the shifted time that
 * day; in a fall-back overlap it runs once (first occurrence). Jobs with a wildcard hour run through both halves of
 * an overlap and simply have no runs inside a gap.
 */
class Cron private constructor(
    val expression: String,
    private val minutes: BooleanArray,
    private val hours: BooleanArray,
    private val daysOfMonth: BooleanArray,
    private val months: BooleanArray,
    private val daysOfWeek: BooleanArray,
    private val domRestricted: Boolean,
    private val dowRestricted: Boolean,
    private val everyHour: Boolean,
) {
    fun next(after: Instant, zone: ZoneId): Instant? {
        val start = after.atZone(zone).toLocalDateTime()
        var date = start.toLocalDate()
        val lastDate = date.plusYears(5)
        while (!date.isAfter(lastDate)) {
            if (months[date.monthValue] && dayMatches(date)) {
                for (h in 0..23) {
                    if (!hours[h]) continue
                    for (m in 0..59) {
                        if (!minutes[m]) continue
                        val local = LocalDateTime.of(date, LocalTime.of(h, m))
                        if (local.isBefore(start.withSecond(0).withNano(0))) continue
                        candidates(local, zone).firstOrNull { it.isAfter(after) }?.let { return it }
                    }
                }
            }
            date = date.plusDays(1)
        }
        return null
    }

    fun nextRuns(after: Instant, zone: ZoneId, count: Int): List<Instant> {
        val out = ArrayList<Instant>(count)
        var cursor = after
        repeat(count) {
            val n = next(cursor, zone) ?: return out
            out += n
            cursor = n
        }
        return out
    }

    private fun candidates(local: LocalDateTime, zone: ZoneId): List<Instant> {
        val offsets = zone.rules.getValidOffsets(local)
        return when {
            offsets.size == 1 -> listOf(local.toInstant(offsets[0]))
            offsets.size == 2 -> {
                val both = offsets.map { local.toInstant(it) }.sorted()
                if (everyHour) both else both.take(1)
            }
            everyHour -> emptyList()
            else -> listOf(ZonedDateTime.ofLocal(local, zone, null).toInstant())
        }
    }

    private fun dayMatches(date: LocalDate): Boolean {
        val dom = daysOfMonth[date.dayOfMonth]
        val dow = daysOfWeek[date.dayOfWeek.value % 7]
        return when {
            domRestricted && dowRestricted -> dom || dow
            domRestricted -> dom
            dowRestricted -> dow
            else -> true
        }
    }

    override fun toString() = expression

    companion object {
        private val MACROS = mapOf(
            "@yearly" to "0 0 1 1 *",
            "@annually" to "0 0 1 1 *",
            "@monthly" to "0 0 1 * *",
            "@weekly" to "0 0 * * 0",
            "@daily" to "0 0 * * *",
            "@midnight" to "0 0 * * *",
            "@hourly" to "0 * * * *",
        )
        private val MONTH_NAMES = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        private val DAY_NAMES = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")

        fun parse(expression: String): Cron {
            val trimmed = expression.trim()
            val expanded = MACROS[trimmed.lowercase()] ?: trimmed
            val parts = expanded.split(Regex("\\s+"))
            if (parts.size != 5) throw CronException("Cron needs 5 fields (minute hour day month weekday), got ${parts.size}")
            val minutes = field(parts[0], 0, 59, emptyList(), "minute")
            val hours = field(parts[1], 0, 23, emptyList(), "hour")
            val dom = field(parts[2], 1, 31, emptyList(), "day of month")
            val months = field(parts[3], 1, 12, MONTH_NAMES, "month", nameOffset = 1)
            val dowRaw = field(parts[4], 0, 7, DAY_NAMES, "day of week")
            val dow = BooleanArray(7) { dowRaw[it] || (it == 0 && dowRaw[7]) }
            return Cron(
                expression = trimmed,
                minutes = minutes,
                hours = hours,
                daysOfMonth = dom,
                months = months,
                daysOfWeek = dow,
                domRestricted = !isStar(parts[2]),
                dowRestricted = !isStar(parts[4]),
                everyHour = (0..23).all { hours[it] },
            )
        }

        fun parseOrNull(expression: String): Cron? = runCatching { parse(expression) }.getOrNull()

        private fun isStar(f: String) = f == "*" || f == "?"

        private fun field(
            text: String,
            min: Int,
            max: Int,
            names: List<String>,
            label: String,
            nameOffset: Int = 0,
        ): BooleanArray {
            val out = BooleanArray(max + 1)
            for (item in text.lowercase().split(',')) {
                if (item.isEmpty()) throw CronException("Empty value in $label field")
                val (rangePart, stepPart) = item.split('/', limit = 2).let { it[0] to it.getOrNull(1) }
                val step = stepPart?.let { s -> s.toIntOrNull()?.takeIf { it > 0 } ?: throw CronException("Bad step '$s' in $label") } ?: 1
                val (lo, hi) = when {
                    rangePart == "*" || rangePart == "?" -> min to max
                    '-' in rangePart -> {
                        val (a, b) = rangePart.split('-', limit = 2)
                        value(a, names, nameOffset, label) to value(b, names, nameOffset, label)
                    }
                    else -> {
                        val v = value(rangePart, names, nameOffset, label)
                        v to if (stepPart != null) max else v
                    }
                }
                if (lo < min || hi > max || lo > hi) throw CronException("$label value out of range ($min-$max): $item")
                var v = lo
                while (v <= hi) {
                    out[v] = true
                    v += step
                }
            }
            return out
        }

        private fun value(token: String, names: List<String>, nameOffset: Int, label: String): Int {
            token.toIntOrNull()?.let { return it }
            val i = names.indexOf(token.take(3))
            if (i >= 0) return i + nameOffset
            throw CronException("Unknown $label '$token'")
        }

        internal fun dayIndex(day: DayOfWeek) = day.value % 7
    }
}
