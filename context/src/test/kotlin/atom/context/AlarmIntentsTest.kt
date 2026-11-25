package atom.context

import android.provider.AlarmClock
import atom.context.sources.AlarmIntents
import atom.context.sources.AlarmRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

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
        assertEquals(false, d.extras[AlarmClock.EXTRA_IS_PM])
        assertEquals(true, AlarmIntents.dismissAt(19, 0).extras[AlarmClock.EXTRA_IS_PM])
        val byLabel = AlarmIntents.plan("disable", null, null, "Gym").single()
        assertEquals(AlarmClock.ALARM_SEARCH_MODE_LABEL, byLabel.extras[AlarmClock.EXTRA_ALARM_SEARCH_MODE])
        assertEquals("Gym", byLabel.extras[AlarmClock.EXTRA_MESSAGE])
    }

    @Test
    fun changeIsOffThenSet() {
        val steps = AlarmIntents.plan("change", seven, seven.copy(hour = 8, minute = 5), null)
        assertEquals(listOf(AlarmClock.ACTION_DISMISS_ALARM, AlarmClock.ACTION_SET_ALARM), steps.map { it.action })
        assertEquals(7, steps[0].extras[AlarmClock.EXTRA_HOUR])
        assertEquals(8, steps[1].extras[AlarmClock.EXTRA_HOUR])
        assertEquals(5, steps[1].extras[AlarmClock.EXTRA_MINUTES])
        assertEquals(listOf(2, 3), steps[1].extras[AlarmClock.EXTRA_DAYS])
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
