package atom.context

import atom.context.notifications.NotificationHistory
import atom.context.notifications.NotificationRecord
import atom.context.notifications.NotificationStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationHistoryTest {
    private class MemStore : NotificationStore {
        val rows = mutableListOf<NotificationRecord>()
        private var next = 1L
        override fun insert(r: NotificationRecord): Long { val id = next++; rows += r.copy(id = id); return id }
        override fun lastForKey(key: String) = rows.lastOrNull { it.key == key }
        override fun deleteOlderThan(cutoffMillis: Long): Int { val n = rows.count { it.postedAt < cutoffMillis }; rows.removeAll { it.postedAt < cutoffMillis }; return n }
        override fun trimTo(max: Int): Int {
            val keep = rows.sortedWith(compareByDescending<NotificationRecord> { it.postedAt }.thenByDescending { it.id }).take(max).toSet()
            val n = rows.size - keep.size
            rows.retainAll(keep)
            return n
        }
        override fun count() = rows.size
        override fun search(query: String?, packageName: String?, sinceMillis: Long?, offset: Int, limit: Int): Pair<List<NotificationRecord>, Int> {
            val hits = rows.filter { r ->
                (query == null || listOf(r.title, r.text, r.appLabel).any { it.contains(query, ignoreCase = true) }) &&
                    (packageName == null || r.packageName == packageName) && (sinceMillis == null || r.postedAt >= sinceMillis)
            }.sortedByDescending { it.postedAt }
            return hits.drop(offset).take(limit) to hits.size
        }
        override fun byId(id: Long) = rows.firstOrNull { it.id == id }
        override fun clear() = rows.clear()
    }

    private val day = 86_400_000L
    private var now = 100 * day
    private val store = MemStore()
    private val history = NotificationHistory(store, now = { now }, maxEntries = 5)

    private fun rec(key: String, text: String, at: Long = now, title: String = "Alice") =
        NotificationRecord(postedAt = at, packageName = "org.telegram.messenger", appLabel = "Telegram", key = key, title = title, text = text)

    @Test
    fun recordsAndSkipsIdenticalReposts() {
        assertTrue(history.record(rec("k1", "hi")))
        assertFalse(history.record(rec("k1", "hi")), "same key and text is a repost")
        assertTrue(history.record(rec("k1", "hi again")), "changed text is new")
        assertEquals(2, store.rows.size)
    }

    @Test
    fun emptyNotificationsAreIgnored() {
        assertFalse(history.record(rec("k", "", title = "")))
        assertEquals(0, store.rows.size)
    }

    @Test
    fun prunesOlderThanSevenDaysOnInsert() {
        history.record(rec("old", "x", at = now - 8 * day))
        history.record(rec("edge", "y", at = now - 6 * day))
        assertEquals(listOf("edge"), store.rows.map { it.key }, "the 8-day-old row goes as soon as anything is inserted")
    }

    @Test
    fun capsAtMaxEntriesKeepingNewest() {
        repeat(8) { i -> history.record(rec("k$i", "m$i", at = now - (8 - i) * 1000L)) }
        assertEquals(5, store.rows.size)
        assertEquals((3..7).map { "k$it" }, store.rows.sortedBy { it.postedAt }.map { it.key })
    }

    @Test
    fun searchNeverReachesPastTheWindow() {
        store.insert(rec("ancient", "budget", at = now - 30 * day))
        history.record(rec("new", "budget review"))
        val (rows, total) = history.search("budget", null, null, 0, 10)
        assertEquals(1, total)
        assertEquals("new", rows.single().key)
    }
}
