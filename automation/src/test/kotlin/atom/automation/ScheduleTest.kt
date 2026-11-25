package atom.automation

import atom.automation.schedule.Cron
import atom.automation.schedule.CronException
import atom.automation.schedule.MissedRunPolicy
import atom.automation.schedule.Planner
import atom.automation.schedule.QuietHours
import atom.automation.schedule.Schedule
import atom.automation.schedule.ScheduleText
import atom.automation.schedule.nextRuns
import atom.automation.schedule.problem
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScheduleTest {
    private val ny = ZoneId.of("America/New_York")
    private val utc = ZoneOffset.UTC

    private fun at(s: String, zone: ZoneId = ny): Instant = LocalDateTime.parse(s).atZone(zone).toInstant()
    private fun local(i: Instant, zone: ZoneId = ny) = i.atZone(zone).toLocalDateTime().toString()

    @Test fun cronParsesListsRangesStepsAndNames() {
        val c = Cron.parse("*/15 9-17 * jan-mar mon-fri")
        assertEquals("2026-01-05T09:00", local(c.next(at("2026-01-03T12:00"), ny)!!))
        assertEquals("2026-01-05T09:15", local(c.next(at("2026-01-05T09:00"), ny)!!))
        assertEquals("2026-01-05T17:45", local(c.next(at("2026-01-05T17:30"), ny)!!))
        assertEquals("2026-01-06T09:00", local(c.next(at("2026-01-05T17:45"), ny)!!))
    }

    @Test fun cronMacrosAndSundayAsSeven() {
        assertEquals("2026-11-26T00:00", local(Cron.parse("@daily").next(at("2026-11-25T10:00"), ny)!!))
        assertEquals("2026-11-29T08:00", local(Cron.parse("0 8 * * 7").next(at("2026-11-25T10:00"), ny)!!))
        assertEquals("2026-11-29T08:00", local(Cron.parse("0 8 * * SUN").next(at("2026-11-25T10:00"), ny)!!))
    }

    @Test fun cronDayOfMonthOrDayOfWeek() {
        val c = Cron.parse("0 12 1 * fri")
        val runs = c.nextRuns(at("2026-11-25T00:00"), ny, 3).map { local(it) }
        assertEquals(listOf("2026-11-27T12:00", "2026-12-01T12:00", "2026-12-04T12:00"), runs)
    }

    @Test fun cronRejectsBadInput() {
        assertFailsWith<CronException> { Cron.parse("* * * *") }
        assertFailsWith<CronException> { Cron.parse("60 * * * *") }
        assertFailsWith<CronException> { Cron.parse("0 0 * * funday") }
        assertFailsWith<CronException> { Cron.parse("*/0 * * * *") }
        assertNull(Cron.parseOrNull("5-1 * * * *"))
    }

    @Test fun cronFixedTimeInSpringGapRunsShifted() {
        val c = Cron.parse("30 2 * * *")
        val runs = c.nextRuns(at("2026-03-07T03:00"), ny, 3).map { local(it) }
        assertEquals(listOf("2026-03-08T03:30", "2026-03-09T02:30", "2026-03-10T02:30"), runs)
    }

    @Test fun cronFixedTimeInFallOverlapRunsOnce() {
        val c = Cron.parse("30 1 * * *")
        val runs = c.nextRuns(at("2026-10-31T12:00"), ny, 3)
        assertEquals(listOf("2026-11-01T01:30", "2026-11-02T01:30", "2026-11-03T01:30"), runs.map { local(it) })
        assertEquals(ZoneOffset.ofHours(-4), runs[0].atZone(ny).offset)
    }

    @Test fun cronHourlyJobRunsThroughBothHalvesOfOverlapAndSkipsGap() {
        val hourly = Cron.parse("0 * * * *")
        val fall = hourly.nextRuns(at("2026-11-01T00:30"), ny, 3)
        assertEquals(listOf(3600L, 3600L), fall.zipWithNext { a, b -> b.epochSecond - a.epochSecond })
        assertEquals(listOf("2026-11-01T01:00", "2026-11-01T01:00", "2026-11-01T02:00"), fall.map { local(it) })
        val spring = hourly.nextRuns(at("2026-03-08T00:30"), ny, 3).map { local(it) }
        assertEquals(listOf("2026-03-08T01:00", "2026-03-08T03:00", "2026-03-08T04:00"), spring)
    }

    @Test fun dailyAcrossDst() {
        val s = Schedule.Daily(2 * 60 + 30)
        assertEquals("2026-03-08T03:30", local(s.next(at("2026-03-07T12:00"), ny)!!))
        val fall = Schedule.Daily(90).nextRuns(at("2026-10-31T12:00"), ny, 2).map { local(it) }
        assertEquals(listOf("2026-11-01T01:30", "2026-11-02T01:30"), fall)
    }

    @Test fun weekdaysAndWeekly() {
        assertEquals("2026-11-30T08:00", local(Schedule.Weekdays(8 * 60).next(at("2026-11-27T09:00"), ny)!!))
        val weekly = Schedule.Weekly(setOf(DayOfWeek.TUESDAY, DayOfWeek.SATURDAY), 10 * 60)
        assertEquals(listOf("2026-11-28T10:00", "2026-12-01T10:00"), weekly.nextRuns(at("2026-11-25T10:00"), ny, 2).map { local(it) })
        assertNull(Schedule.Weekly(emptySet(), 600).next(Instant.now(), ny))
        assertNotNull(Schedule.Weekly(emptySet(), 600).problem())
    }

    @Test fun intervalStaysAnchored() {
        val anchor = at("2026-11-25T10:07", utc).toEpochMilli()
        val s = Schedule.Interval(30, anchor)
        assertEquals("2026-11-25T11:07", local(s.next(at("2026-11-25T10:50", utc), utc), utc))
        assertEquals("2026-11-25T10:37", local(s.next(Instant.ofEpochMilli(anchor), utc), utc))
        assertNotNull(Schedule.Interval(5, anchor).problem())
    }

    @Test fun onceFiresOnlyInFuture() {
        val t = at("2026-11-25T10:00", utc)
        assertEquals(t, Schedule.Once(t.toEpochMilli()).next(t.minusSeconds(1), utc))
        assertNull(Schedule.Once(t.toEpochMilli()).next(t, utc))
    }

    @Test fun scheduleTextRoundTrips() {
        val now = at("2026-11-25T10:00", utc)
        for (text in listOf("daily 21:00", "weekdays 07:45", "weekly mon,thu 09:30", "every 120m", "cron 0 9 * * 1-5")) {
            assertEquals(text, ScheduleText.encode(ScheduleText.parse(text, now)))
        }
        assertEquals(Schedule.Daily(23 * 60), ScheduleText.parse("daily 11pm", now))
        assertEquals(Schedule.Weekly(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), 600), ScheduleText.parse("weekends 10am", now))
        assertEquals(Schedule.Interval(90, now.toEpochMilli()), ScheduleText.parse("every 1.5h", now))
        assertEquals(Schedule.Once(now.toEpochMilli() + 45 * 60_000), ScheduleText.parse("in 45m", now))
        assertEquals(Schedule.CronSchedule("*/5 * * * *"), ScheduleText.parse("*/5 * * * *", now))
        assertNull(ScheduleText.parseOrNull("sometimes", now))
    }

    @Test fun previewAppliesQuietHours() {
        val quiet = QuietHours(enabled = true)
        val anchor = at("2026-11-25T20:00").toEpochMilli()
        val preview = Planner.preview(Schedule.Interval(60, anchor), quiet, at("2026-11-25T21:30"), ny, 5).map { local(it) }
        assertEquals(listOf("2026-11-25T22:00", "2026-11-26T07:30", "2026-11-26T08:00", "2026-11-26T09:00", "2026-11-26T10:00"), preview)
    }

    @Test fun quietHoursWindow() {
        val q = QuietHours(enabled = true, startMinute = 23 * 60, endMinute = 7 * 60 + 30)
        assertTrue(q.contains(at("2026-11-25T23:00"), ny))
        assertTrue(q.contains(at("2026-11-26T03:00"), ny))
        assertTrue(!q.contains(at("2026-11-26T07:30"), ny))
        assertEquals("2026-11-26T07:30", local(q.adjust(at("2026-11-25T23:45"), ny)))
        assertEquals("2026-11-26T07:30", local(q.adjust(at("2026-11-26T01:00"), ny)))
        val day = QuietHours(enabled = true, startMinute = 12 * 60, endMinute = 14 * 60)
        assertEquals("2026-11-26T14:00", local(day.adjust(at("2026-11-26T13:10"), ny)))
        assertEquals(at("2026-11-26T13:10"), QuietHours(enabled = false).adjust(at("2026-11-26T13:10"), ny))
    }

    @Test fun missedRunPolicy() {
        val due = at("2026-11-25T08:00")
        assertEquals(Planner.Decision.RunNow, Planner.missed(MissedRunPolicy.Skip, due, due.plusSeconds(5 * 60)))
        assertEquals(Planner.Decision.Skip, Planner.missed(MissedRunPolicy.Skip, due, due.plusSeconds(30 * 60)))
        assertEquals(Planner.Decision.RunNow, Planner.missed(MissedRunPolicy.CatchUp, due, due.plusSeconds(2 * 3600)))
        assertEquals(Planner.Decision.Skip, Planner.missed(MissedRunPolicy.CatchUp, due, due.plusSeconds(4 * 3600)))
    }
}
