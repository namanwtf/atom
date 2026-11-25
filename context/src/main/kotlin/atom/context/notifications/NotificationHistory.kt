package atom.context.notifications

/** One posted notification as Atom keeps it: text only, local only. */
data class NotificationRecord(
    val id: Long = 0,
    val postedAt: Long,
    val packageName: String,
    val appLabel: String,
    val key: String,
    val title: String,
    val text: String,
    val conversation: String? = null,
    val category: String? = null,
)

/** Storage behind the history; SQLite on the phone, in-memory in tests. */
interface NotificationStore {
    fun insert(r: NotificationRecord): Long
    fun lastForKey(key: String): NotificationRecord?
    fun deleteOlderThan(cutoffMillis: Long): Int
    fun trimTo(max: Int): Int
    fun count(): Int
    fun search(query: String?, packageName: String?, sinceMillis: Long?, offset: Int, limit: Int): Pair<List<NotificationRecord>, Int>
    fun byId(id: Long): NotificationRecord?
    fun clear()
}

/**
 * The notification history policy: last [maxAgeMillis] (7 days), at most [maxEntries] (1000). Pruning happens on
 * insert, so there is no timer; an identical repost of the same notification (progress updates, re-alerts) is kept
 * once.
 */
class NotificationHistory(
    private val store: NotificationStore,
    private val now: () -> Long = System::currentTimeMillis,
    val maxAgeMillis: Long = 7L * 24 * 3600 * 1000,
    val maxEntries: Int = 1_000,
) {
    /** Returns false when the record was a duplicate or empty and was not stored. */
    fun record(r: NotificationRecord): Boolean {
        if (r.title.isBlank() && r.text.isBlank()) return false
        val last = store.lastForKey(r.key)
        if (last != null && last.title == r.title && last.text == r.text) return false
        store.insert(r)
        store.deleteOlderThan(now() - maxAgeMillis)
        store.trimTo(maxEntries)
        return true
    }

    fun search(query: String?, packageName: String?, sinceMillis: Long?, offset: Int, limit: Int) =
        store.search(query?.takeIf { it.isNotBlank() }, packageName, maxOf(sinceMillis ?: 0L, now() - maxAgeMillis), offset, limit)

    fun byId(id: Long) = store.byId(id)

    fun count() = store.count()

    fun clear() = store.clear()
}
