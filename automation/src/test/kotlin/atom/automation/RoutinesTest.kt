package atom.automation

import atom.automation.schedule.Schedule
import atom.automation.task.NotifyMode
import atom.automation.task.Routine
import java.time.DayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutinesTest {
    @Test fun schedulesAreTheOnesShown() {
        assertEquals(Schedule.Weekdays(7 * 60 + 45), Routine.MorningBrief.schedule)
        assertEquals(Schedule.Daily(10 * 60), Routine.PriceWatch.schedule)
        assertEquals(Schedule.Weekly(setOf(DayOfWeek.SUNDAY), 19 * 60 + 30), Routine.WeeklyCleanup.schedule)
        assertEquals(Schedule.Daily(20 * 60 + 30), Routine.EveningWrapUp.schedule)
    }

    @Test fun noRoutineCanChangeThePhone() {
        for (r in Routine.entries) {
            for (c in listOf(Capability.DeviceControl, Capability.AppUi, Capability.Terminal)) {
                assertFalse(c in r.capabilities, "${r.id} may use ${c.tag}")
            }
            assertTrue(r.capabilities.isNotEmpty(), "${r.id} has no tools")
        }
    }

    @Test fun priceWatchReportsOnlyChanges() {
        assertEquals(NotifyMode.OnChange, Routine.PriceWatch.notify)
        assertTrue(Routine.PriceWatch.asksFirst)
        Routine.entries.filter { it != Routine.PriceWatch }.forEach {
            assertEquals(NotifyMode.EveryRun, it.notify)
            assertFalse(it.asksFirst)
        }
    }

    @Test fun draftIsANewEnabledTask() {
        val t = Routine.WeeklyCleanup.draft("Weekly phone cleanup", "Make a report", 1_000)
        assertEquals("", t.id)
        assertTrue(t.enabled)
        assertEquals(Routine.WeeklyCleanup.schedule, t.schedule)
        assertEquals(Routine.WeeklyCleanup.capabilities, t.capabilities)
        assertEquals(10, t.maxMinutes)
        assertEquals(1_000, t.createdAt)
    }

    @Test fun routesRoundTrip() {
        assertEquals("routine/morning-brief", Routine.MorningBrief.route())
        assertEquals(Triple(Routine.MorningBrief, "", ""), Routine.parse("routine/morning-brief"))
        val what = "Sony WH-1000XM6 & case"
        val where = "https://shop.example.com/p?id=12&ref=a b"
        val route = Routine.PriceWatch.route(what, where)
        assertFalse(' ' in route)
        assertEquals(Triple(Routine.PriceWatch, what, where), Routine.parse(route))
        assertNull(Routine.parse("routine/unknown"))
        assertNull(Routine.parse("new/2"))
    }
}
