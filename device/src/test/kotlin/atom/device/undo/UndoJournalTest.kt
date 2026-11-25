package atom.device.undo

import kotlinx.coroutines.test.runTest
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UndoJournalTest {
    private class MemStore : JournalStore {
        var saved: List<JournalEntry> = emptyList()
        override fun load() = saved
        override fun save(entries: List<JournalEntry>) {
            saved = entries
        }
    }

    private var clock = 1_700_000_000_000L
    private val store = MemStore()
    private fun journal(max: Int = 500) = UndoJournal(store, now = { clock }, zone = { ZoneOffset.UTC }, maxEntries = max)

    @Test
    fun recordsAndPersists() = runTest {
        val j = journal()
        val e = j.record("set_toggle", "Wi-Fi", "off", "on", "control:wifi", "false")
        assertEquals(1, e.id)
        assertEquals("Wi-Fi: off → on", e.summary)
        assertEquals(1, store.saved.size)
        assertEquals(1, UndoJournal(store).entries.value.size)
    }

    @Test
    fun undoRunsRestorerWithBeforeStateAndMarksEntry() = runTest {
        val j = journal()
        var restored: String? = null
        j.register("control:") { restored = it.undoData; UndoResult(true, "Wi-Fi: on → off (verified)") }
        val e = j.record("set_toggle", "Wi-Fi", "off", "on", "control:wifi", "false")
        val r = j.undo(e.id)
        assertTrue(r.ok)
        assertEquals("false", restored)
        assertNotNull(j.entries.value.single().undoneAtMillis)
        assertFalse(j.undo(e.id).ok, "an undone entry cannot be undone twice")
        assertNull(j.lastUndoable())
    }

    @Test
    fun failedUndoLeavesEntryUndoable() = runTest {
        val j = journal()
        j.register("control:") { UndoResult(false, "needs root") }
        val e = j.record("set_toggle", "Mobile data", "on", "off", "control:mobile_data", "true")
        assertFalse(j.undo(e.id).ok)
        assertEquals(e.id, j.lastUndoable()?.id)
    }

    @Test
    fun entriesWithoutUndoDataAreNotUndoable() = runTest {
        val j = journal()
        val e = j.record("force_stop_app", "Maps", "running", "stopped", "app:stop", null)
        assertNull(j.lastUndoable())
        assertFalse(j.undo(e.id).ok)
    }

    @Test
    fun longestPrefixRestorerWins() = runTest {
        val j = journal()
        var used = ""
        j.register("calendar:") { used = "generic"; UndoResult(true, "") }
        j.register("calendar:create") { used = "create"; UndoResult(true, "") }
        j.undo(j.record("calendar_create", "Calendar", "absent", "created", "calendar:create", "5").id)
        assertEquals("create", used)
    }

    @Test
    fun undoTodayGoesNewestFirstAndSkipsYesterday() = runTest {
        val j = journal()
        val order = mutableListOf<Long>()
        j.register("control:") { order += it.id; UndoResult(true, "") }
        clock -= 86_400_000L
        j.record("t", "Old", "a", "b", "control:x", "a")
        clock += 86_400_000L
        val a = j.record("t", "Brightness", "40%", "70%", "control:brightness", "40")
        val b = j.record("t", "Brightness", "70%", "90%", "control:brightness", "70")
        val results = j.undoToday()
        assertEquals(listOf(b.id, a.id), order)
        assertEquals(2, results.size)
    }

    @Test
    fun prunesOnInsertToMaxEntriesAndAge() = runTest {
        val j = journal(max = 3)
        repeat(5) { j.record("t", "L$it", "a", "b", "control:x", "a") }
        assertEquals(listOf("L2", "L3", "L4"), j.entries.value.map { it.label })
        clock += 31L * 86_400_000L
        j.record("t", "New", "a", "b", "control:x", "a")
        assertEquals(listOf("New"), j.entries.value.map { it.label })
    }

    @Test
    fun idsKeepIncreasingAfterPruning() = runTest {
        val j = journal(max = 2)
        repeat(4) { j.record("t", "L$it", "a", "b", "control:x", "a") }
        assertEquals(listOf(3L, 4L), j.entries.value.map { it.id })
    }
}
