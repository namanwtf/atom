package atom.automation.task

import atom.automation.Capability
import atom.automation.schedule.QuietHours
import atom.automation.schedule.Schedule
import java.time.DayOfWeek

data class Preset(val title: String, val blurb: String, val build: (now: Long) -> ScheduledTask)

object Presets {
    val all: List<Preset> = listOf(
        Preset("Daily evening briefing", "Every day at 21:00") { now ->
            ScheduledTask(
                id = "",
                name = "Evening briefing",
                prompt = "Give me a short evening briefing: what happened today in my notifications and calendar, anything " +
                    "I still need to do tonight, and tomorrow's first events and weather. Keep it under 12 lines.",
                schedule = Schedule.Daily(21 * 60),
                capabilities = setOf(Capability.DeviceRead, Capability.Web),
                createdAt = now,
            )
        },
        Preset("Weekday morning digest", "Mon-Fri at 07:45") { now ->
            ScheduledTask(
                id = "",
                name = "Morning digest",
                prompt = "Morning digest: today's calendar, important messages and notifications since last night, the " +
                    "weather, and one headline worth knowing. Lead with anything that needs action.",
                schedule = Schedule.Weekdays(7 * 60 + 45),
                capabilities = setOf(Capability.DeviceRead, Capability.Web),
                createdAt = now,
            )
        },
        Preset("Web change radar", "Every 6 hours, only on change") { now ->
            ScheduledTask(
                id = "",
                name = "Web change radar",
                prompt = "Check https://example.com for anything new since the previous result and summarise only what " +
                    "changed. Replace the address with the page you want to watch.",
                schedule = Schedule.Interval(6 * 60, now),
                notify = NotifyMode.OnChange,
                capabilities = setOf(Capability.Web),
                quietHours = QuietHours(enabled = true),
                createdAt = now,
            )
        },
        Preset("Weekly phone & habit review", "Sundays at 19:00") { now ->
            ScheduledTask(
                id = "",
                name = "Weekly phone review",
                prompt = "Review my week on this phone: screen time and the apps I used most, notification load, battery " +
                    "and storage health. End with one or two specific habit changes worth trying.",
                schedule = Schedule.Weekly(setOf(DayOfWeek.SUNDAY), 19 * 60),
                capabilities = setOf(Capability.DeviceRead),
                maxMinutes = 10,
                createdAt = now,
            )
        },
    )
}
