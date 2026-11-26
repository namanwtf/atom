package atom.automation.task

import atom.automation.Capability
import atom.automation.schedule.Schedule
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.DayOfWeek

/**
 * Ready-made routines: one tap opens the ordinary scheduled-task editor already filled in, so the person only checks
 * it and taps Save. The wording lives in string resources; this holds the schedule and settings of each (pure,
 * unit-tested).
 */
enum class Routine(val id: String) {
    /** Weekdays 07:45: calendar, weather and notifications that need action. */
    MorningBrief("morning-brief"),

    /** Asks what and where first, then checks once a day and notifies only on a drop. */
    PriceWatch("price-watch"),

    /** Sunday evening: large files, unused apps and Downloads, as a report; it never deletes anything. */
    WeeklyCleanup("weekly-cleanup"),

    /** Every evening: the day's missed notifications and tomorrow's first event. */
    EveningWrapUp("evening-wrap-up"),
    ;

    val schedule: Schedule
        get() = when (this) {
            MorningBrief -> Schedule.Weekdays(7 * 60 + 45)
            PriceWatch -> Schedule.Daily(10 * 60)
            WeeklyCleanup -> Schedule.Weekly(setOf(DayOfWeek.SUNDAY), 19 * 60 + 30)
            EveningWrapUp -> Schedule.Daily(20 * 60 + 30)
        }

    /**
     * What each run may use. None of them may change the phone: no device controls, app UI or terminal, so the cleanup
     * report cannot delete a file even if asked to in its prompt.
     */
    val capabilities: Set<Capability>
        get() = when (this) {
            MorningBrief -> setOf(Capability.DeviceRead, Capability.Web)
            PriceWatch -> setOf(Capability.Web)
            WeeklyCleanup -> setOf(Capability.DeviceRead)
            EveningWrapUp -> setOf(Capability.DeviceRead)
        }

    /** Price watch reports only a lower price; the others report every run. */
    val notify: NotifyMode get() = if (this == PriceWatch) NotifyMode.OnChange else NotifyMode.EveryRun

    val maxMinutes: Int get() = if (this == WeeklyCleanup) 10 else 5

    /** True when the editor needs answers first (what and where to watch). */
    val asksFirst: Boolean get() = this == PriceWatch

    /** The editor route (under `automation/task/`) for this routine, with the answers for [PriceWatch]. */
    fun route(what: String = "", where: String = ""): String = buildString {
        append(ROUTE_PREFIX).append(id)
        if (asksFirst) append("?what=").append(encode(what)).append("&where=").append(encode(where))
    }

    /** A new, unsaved task for the editor, from the resolved [name] and [prompt]. */
    fun draft(name: String, prompt: String, now: Long): ScheduledTask = ScheduledTask(
        id = "",
        name = name,
        prompt = prompt,
        schedule = schedule,
        notify = notify,
        capabilities = capabilities,
        maxMinutes = maxMinutes,
        createdAt = now,
    )

    companion object {
        /** After `automation/task/`. */
        const val ROUTE_PREFIX = "routine/"

        fun byId(id: String): Routine? = entries.firstOrNull { it.id == id }

        /** The routine and its answers from a route tail like `routine/price-watch?what=..&where=..`. */
        fun parse(rest: String): Triple<Routine, String, String>? {
            if (!rest.startsWith(ROUTE_PREFIX)) return null
            val tail = rest.removePrefix(ROUTE_PREFIX)
            val routine = byId(tail.substringBefore('?')) ?: return null
            val query = tail.substringAfter('?', "").split('&').filter { '=' in it }
                .associate { it.substringBefore('=') to decode(it.substringAfter('=')) }
            return Triple(routine, query["what"].orEmpty(), query["where"].orEmpty())
        }

        private fun encode(s: String) = URLEncoder.encode(s.trim(), "UTF-8")
        private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
    }
}
