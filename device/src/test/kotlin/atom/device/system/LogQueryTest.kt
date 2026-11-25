package atom.device.system

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogQueryTest {
    @Test
    fun defaults() {
        assertEquals("logcat -d -v threadtime | tail -n 200", LogQuery().command())
    }

    @Test
    fun allFilters() {
        val c = LogQuery(pid = 4321, tag = "ActivityManager", level = 'W', sinceEpochMs = 1_700_000_000_123, text = "it's", maxLines = 50).command()
        assertEquals(
            "logcat -d -v threadtime -T '1700000000.123' --pid=4321 'ActivityManager:W' '*:S' | grep -i -F -e 'it'\\''s' | tail -n 50",
            c,
        )
    }

    @Test
    fun levelWithoutTagFiltersEverything() {
        assertTrue(LogQuery(level = 'E').command().contains(" '*:E'"))
    }

    @Test
    fun levelNames() {
        assertEquals('W', LogQuery.level("warning"))
        assertEquals('E', LogQuery.level("E"))
        assertEquals(null, LogQuery.level("loud"))
    }

    @Test
    fun lineCapIsBounded() {
        assertTrue(LogQuery(maxLines = 10_000).command().endsWith("tail -n 500"))
    }
}
