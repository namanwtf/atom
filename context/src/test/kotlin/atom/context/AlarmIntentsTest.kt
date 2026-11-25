package atom.context

import android.provider.AlarmClock
import atom.context.sources.AlarmIntents
import atom.context.sources.AlarmRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlarmIntentsTest {
    private val seven = AlarmRecord(7, 30, listOf(2, 3), "Gym")

    @Test
    fun setCarriesTimeLabelAndDays() {
        val s = AlarmIntents.set(7, 30, "Gym", listOf(2, 3))
        assertEquals(AlarmClock.ACTION_SET_ALARM, s.action)
        assertEquals(7, s.extras[AlarmClock.EXTRA_HOUR])
        assertEquals(30, s.extras[AlarmClock.EXTRA_MINUTES])
        assertEquals(true, s.extras[AlarmClock.EXTRA_SKIP_UI])
        assertEquals("Gym", s.extras[AlarmClock.EXTRA_MESSAGE])
        assertEquals(listOf(2, 3), s.extras[AlarmClock.EXTRA_DAYS])
        val bare = AlarmIntents.set(6, 0, " ", emptyList())
        assertNull(bare.extras[AlarmClock.EXTRA_MESSAGE])
        assertNull(bare.extras[AlarmClock.EXTRA_DAYS])
    }

    @Test
    fun disableSearchesByTime() {
        val d = AlarmIntents.plan("disable", seven, null, null).single()
        assertEquals(AlarmClock.ACTION_DISMISS_ALARM, d.action)
        assertEquals(AlarmClock.ALARM_SEARCH_MODE_TIME, d.extras[AlarmClock.EXTRA_ALARM_SEARCH_MODE])
        assertEquals(7, d.extras[AlarmClock.EXTRA_HOUR])
        // 24-hour hour, never paired with EXTRA_IS_PM (clock apps reject hour > 12 with it).
        val evening = AlarmIntents.dismissAt(19, 45)
        assertEquals(19, evening.extras[AlarmClock.EXTRA_HOUR])
        assertEquals(45, evening.extras[AlarmClock.EXTRA_MINUTES])
        assertFalse(AlarmClock.EXTRA_IS_PM in evening.extras)
        assertFalse(AlarmClock.EXTRA_IS_PM in d.extras)
        val byLabel = AlarmIntents.plan("disable", null, null, "Gym").single()
        assertEquals(AlarmClock.ALARM_SEARCH_MODE_LABEL, byLabel.extras[AlarmClock.EXTRA_ALARM_SEARCH_MODE])
        assertEquals("Gym", byLabel.extras[AlarmClock.EXTRA_MESSAGE])
    }

    @Test
    fun changeOnlyDismissesUntilTheOldAlarmIsConfirmedOff() {
        // The new time is never sent in the same plan: it waits for offConfirmed, so no duplicate is left behind.
        val steps = AlarmIntents.plan("change", seven, seven.copy(hour = 8, minute = 5), null)
        assertEquals(listOf(AlarmClock.ACTION_DISMISS_ALARM), steps.map { it.action })
        assertEquals(7, steps[0].extras[AlarmClock.EXTRA_HOUR])
    }

    @Test
    fun confirmation() {
        assertTrue(AlarmIntents.offConfirmed(7, 30, nextBefore = 7 to 30, nextAfter = 9 to 0))
        assertTrue(AlarmIntents.offConfirmed(7, 30, nextBefore = 7 to 30, nextAfter = null))
        // Not the next alarm: nothing to confirm with.
        assertFalse(AlarmIntents.offConfirmed(7, 30, nextBefore = 6 to 0, nextAfter = 6 to 0))
        // A repeating alarm whose next ring was skipped keeps its time.
        assertFalse(AlarmIntents.offConfirmed(7, 30, nextBefore = 7 to 30, nextAfter = 7 to 30))
        assertTrue(AlarmIntents.setConfirmed(8, 5, 7 to 30, 8 to 5, 1L, 2L))
        assertTrue(AlarmIntents.setConfirmed(8, 5, 7 to 30, 7 to 0, 1L, 2L))
        assertFalse(AlarmIntents.setConfirmed(8, 5, 7 to 30, 7 to 30, 1L, 1L))
    }

    @Test
    fun deleteTurnsOffAndShowsTheList() {
        val steps = AlarmIntents.plan("delete", seven, null, null)
        assertEquals(listOf(AlarmClock.ACTION_DISMISS_ALARM, AlarmClock.ACTION_SHOW_ALARMS), steps.map { it.action })
    }

    @Test
    fun enableResendsTheSameAlarm() {
        val s = AlarmIntents.plan("enable", seven, null, null).single()
        assertEquals(AlarmClock.ACTION_SET_ALARM, s.action)
        assertEquals(30, s.extras[AlarmClock.EXTRA_MINUTES])
    }

    @Test
    fun dismissals() {
        assertEquals(AlarmClock.ALARM_SEARCH_MODE_NEXT, AlarmIntents.plan("dismiss_next", null, null, null).single().extras[AlarmClock.EXTRA_ALARM_SEARCH_MODE])
        assertEquals(AlarmClock.ALARM_SEARCH_MODE_ALL, AlarmIntents.plan("dismiss_all", null, null, null).single().extras[AlarmClock.EXTRA_ALARM_SEARCH_MODE])
        assertEquals(AlarmClock.ACTION_DISMISS_TIMER, AlarmIntents.plan("dismiss_timer", null, null, null).single().action)
        assertFailsWith<IllegalArgumentException> { AlarmIntents.plan("explode", null, null, null) }
    }

    @Test
    fun recordsRoundTrip() {
        val list = listOf(seven, AlarmRecord(23, 0, emptyList(), "a|b\nc", enabled = false))
        val back = AlarmRecord.decodeAll(AlarmRecord.encodeAll(list))
        assertEquals(seven, back[0])
        assertEquals(AlarmRecord(23, 0, emptyList(), "a/b c", enabled = false), back[1])
        assertNull(AlarmRecord.decode("99|0||1|x"))
        assertEquals(emptyList(), AlarmRecord.decodeAll(null))
    }
}
