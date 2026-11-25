package atom.context.notifications

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import atom.context.search.likeArg

/** Notification history on disk, in Atom's private storage. Excluded from backups by the app (allowBackup=false). */
class SqliteNotificationStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "notification_history.db", null, 1), NotificationStore {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE n (id INTEGER PRIMARY KEY AUTOINCREMENT, posted INTEGER NOT NULL, pkg TEXT NOT NULL, " +
                "app TEXT NOT NULL, nkey TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, convo TEXT, category TEXT)",
        )
        db.execSQL("CREATE INDEX n_posted ON n(posted)")
        db.execSQL("CREATE INDEX n_key ON n(nkey, id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    private fun row(c: Cursor) = NotificationRecord(
        id = c.getLong(0), postedAt = c.getLong(1), packageName = c.getString(2), appLabel = c.getString(3), key = c.getString(4),
        title = c.getString(5), text = c.getString(6), conversation = c.getString(7), category = c.getString(8),
    )

    private val cols = "id, posted, pkg, app, nkey, title, body, convo, category"

    override fun insert(r: NotificationRecord): Long = writableDatabase.insert(
        "n", null,
        ContentValues().apply {
            put("posted", r.postedAt); put("pkg", r.packageName); put("app", r.appLabel); put("nkey", r.key)
            put("title", r.title); put("body", r.text); put("convo", r.conversation); put("category", r.category)
        },
    )

    override fun lastForKey(key: String): NotificationRecord? =
        readableDatabase.rawQuery("SELECT $cols FROM n WHERE nkey = ? ORDER BY id DESC LIMIT 1", arrayOf(key)).use { c ->
            if (c.moveToFirst()) row(c) else null
        }

    override fun deleteOlderThan(cutoffMillis: Long): Int = writableDatabase.delete("n", "posted < ?", arrayOf(cutoffMillis.toString()))

    override fun trimTo(max: Int): Int =
        writableDatabase.delete("n", "id NOT IN (SELECT id FROM n ORDER BY posted DESC, id DESC LIMIT $max)", null)

    override fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM n", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    override fun search(query: String?, packageName: String?, sinceMillis: Long?, offset: Int, limit: Int): Pair<List<NotificationRecord>, Int> {
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (query != null) {
            where += "(title LIKE ? ESCAPE '\\' OR body LIKE ? ESCAPE '\\' OR app LIKE ? ESCAPE '\\' OR convo LIKE ? ESCAPE '\\')"
            repeat(4) { args += likeArg(query) }
        }
        if (packageName != null) {
            where += "pkg = ?"
            args += packageName
        }
        if (sinceMillis != null) {
            where += "posted >= ?"
            args += sinceMillis.toString()
        }
        val clause = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")
        val db = readableDatabase
        val total = db.rawQuery("SELECT COUNT(*) FROM n $clause", args.toTypedArray()).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val rows = db.rawQuery("SELECT $cols FROM n $clause ORDER BY posted DESC, id DESC LIMIT $limit OFFSET $offset", args.toTypedArray()).use { c ->
            val out = ArrayList<NotificationRecord>()
            while (c.moveToNext()) out += row(c)
            out
        }
        return rows to total
    }

    override fun byId(id: Long): NotificationRecord? =
        readableDatabase.rawQuery("SELECT $cols FROM n WHERE id = ?", arrayOf(id.toString())).use { if (it.moveToFirst()) row(it) else null }

    override fun clear() {
        writableDatabase.delete("n", null, null)
    }
}
