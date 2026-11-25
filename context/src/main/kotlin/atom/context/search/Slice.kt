package atom.context.search

import android.database.Cursor

/** One bounded page of results plus how to ask for the next one. */
data class Slice<T>(val items: List<T>, val offset: Int, val total: Int?, val limit: Int) {
    val hasMore: Boolean get() = total?.let { offset + items.size < it } ?: (items.size == limit)

    /** "Showing 21–40 of 134. More: offset=40." */
    fun header(noun: String): String {
        if (items.isEmpty()) {
            return if (offset > 0) "No more $noun after offset $offset." else "No $noun found."
        }
        val range = "${offset + 1}–${offset + items.size}"
        val of = total?.let { " of $it" }.orEmpty()
        val more = if (hasMore) " More: call again with offset=${offset + items.size}." else ""
        return "Showing $noun $range$of.$more"
    }

    fun render(noun: String, line: (T) -> String): String =
        (listOf(header(noun)) + items.map(line)).joinToString("\n")

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 50

        fun clampLimit(limit: Int?): Int = (limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        fun clampOffset(offset: Int?): Int = (offset ?: 0).coerceAtLeast(0)

        fun <T> of(all: List<T>, offset: Int, limit: Int): Slice<T> =
            Slice(all.drop(offset).take(limit), offset, all.size, limit)

        /** Reads rows [offset, offset+limit) of [c] (already sorted and filtered) and closes it. */
        fun <T> fromCursor(c: Cursor?, offset: Int, limit: Int, row: (Cursor) -> T?): Slice<T> {
            if (c == null) return Slice(emptyList(), offset, 0, limit)
            c.use {
                val total = it.count
                val out = ArrayList<T>(limit)
                if (offset < total && it.moveToPosition(offset)) {
                    do {
                        row(it)?.let(out::add)
                    } while (out.size < limit && it.moveToNext())
                }
                return Slice(out, offset, total, limit)
            }
        }

        /** Like [fromCursor] for rows filtered in Kotlin: scans at most [scanCap] rows, so the total is unknown. */
        fun <T> filtered(c: Cursor?, offset: Int, limit: Int, scanCap: Int = 5_000, row: (Cursor) -> T?): Slice<T> {
            if (c == null) return Slice(emptyList(), offset, 0, limit)
            c.use {
                var matched = 0
                var scanned = 0
                val out = ArrayList<T>(limit)
                while (it.moveToNext() && scanned++ < scanCap) {
                    val v = row(it) ?: continue
                    if (matched++ < offset) continue
                    out += v
                    if (out.size >= limit) {
                        val more = it.moveToNext()
                        return Slice(out, offset, if (more) null else offset + out.size, limit)
                    }
                }
                return Slice(out, offset, offset + out.size, limit)
            }
        }
    }
}

/** SQL LIKE escaping for user text, with '\' as the escape character. */
fun likeArg(text: String): String = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

fun one(s: String?, max: Int = 160): String {
    if (s.isNullOrBlank()) return ""
    val flat = s.replace(Regex("\\s+"), " ").trim()
    return if (flat.length > max) flat.take(max - 1) + "…" else flat
}
