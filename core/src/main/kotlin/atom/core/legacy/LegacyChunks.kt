package atom.core.legacy

import java.io.InputStream

/**
 * Large column values of the previous Atom database. A value longer than one chunk is replaced by a reference
 * `@atom:chunks:v1:<count>:<length>` and its pieces live in `agent_text_chunks`, keyed by owner table, owner id and a
 * logical field name. Any value that does not start with [PREFIX] is the literal value.
 */
object LegacyChunks {
    const val PREFIX = "@atom:chunks:v1:"

    data class Ref(val count: Int, val length: Int)

    data class Chunk(val index: Int, val content: String)

    sealed interface Resolved {
        /** The value to import: the joined text, or empty when it could not be restored. */
        val text: String

        /** [lengthMismatch] is a warning only: the joined text is still used. [truncated]: only a prefix was kept. */
        data class Ok(override val text: String, val lengthMismatch: Boolean = false, val truncated: Boolean = false) : Resolved

        /** Missing, duplicate or out-of-range chunks, or a malformed reference. Imported as empty. */
        data class Corrupt(val reason: String) : Resolved {
            override val text: String get() = ""
        }

        /** Longer than the importer's limit; skipped without reading its chunks. */
        data class TooLarge(val length: Int) : Resolved {
            override val text: String get() = ""
        }
    }

    fun isRef(value: String): Boolean = value.startsWith(PREFIX)

    /** The reference in [value], or null when it is a literal value or a malformed reference. */
    fun parseRef(value: String): Ref? {
        if (!isRef(value)) return null
        val parts = value.substring(PREFIX.length).split(':')
        if (parts.size != 2) return null
        val count = parts[0].toIntOrNull() ?: return null
        val length = parts[1].toIntOrNull() ?: return null
        if (count < 0 || length < 0) return null
        return Ref(count, length)
    }

    /**
     * Restores [value]. [fetch] is only called for a valid reference within [maxLength] and returns the chunk rows
     * of the owning row and field in any order.
     */
    fun resolve(value: String, maxLength: Int = Int.MAX_VALUE, fetch: () -> List<Chunk>): Resolved {
        if (!isRef(value)) {
            return if (value.length > maxLength) Resolved.TooLarge(value.length) else Resolved.Ok(value)
        }
        val ref = parseRef(value) ?: return Resolved.Corrupt("malformed reference")
        if (ref.length > maxLength) return Resolved.TooLarge(ref.length)
        val chunks = fetch().sortedBy { it.index }
        if (chunks.size != ref.count) return Resolved.Corrupt("expected ${ref.count} chunks, found ${chunks.size}")
        chunks.forEachIndexed { i, chunk ->
            if (chunk.index != i) return Resolved.Corrupt("chunk $i missing")
        }
        val out = StringBuilder(minOf(ref.length, maxLength))
        for (chunk in chunks) {
            if (out.length + chunk.content.length > maxLength) return Resolved.TooLarge(out.length + chunk.content.length)
            out.append(chunk.content)
        }
        return Resolved.Ok(out.toString(), lengthMismatch = out.length != ref.length)
    }

    /**
     * Like [resolve], but a value longer than [maxLength] gives its first [maxLength] UTF-16 units (marked
     * [Resolved.Ok.truncated]) instead of nothing. Only the chunks needed for the prefix are read: [fetchFirst]`(n)`
     * returns the rows with `chunk_index < n`. Chunks hold at least [MIN_CHUNK] units except the last.
     */
    fun resolvePrefix(value: String, maxLength: Int, fetchFirst: (Int) -> List<Chunk>): Resolved {
        if (!isRef(value)) {
            return if (value.length > maxLength) Resolved.Ok(prefix(value, maxLength), truncated = true) else Resolved.Ok(value)
        }
        val ref = parseRef(value) ?: return Resolved.Corrupt("malformed reference")
        if (ref.length <= maxLength) return resolve(value, maxLength) { fetchFirst(ref.count) }
        val need = minOf(ref.count, maxLength / MIN_CHUNK + 1)
        val chunks = fetchFirst(need).sortedBy { it.index }
        if (chunks.size != need) return Resolved.Corrupt("expected $need chunks, found ${chunks.size}")
        chunks.forEachIndexed { i, chunk ->
            if (chunk.index != i) return Resolved.Corrupt("chunk $i missing")
        }
        val out = StringBuilder(maxLength + MAX_CHUNK)
        for (chunk in chunks) {
            out.append(chunk.content)
            if (out.length >= maxLength) break
        }
        return Resolved.Ok(prefix(out.toString(), maxLength), truncated = true)
    }

    /** Null when the chunk indexes of a reference run 0 to count-1 without gaps; otherwise why not. */
    fun indexProblem(ref: Ref, indexes: List<Int>): String? {
        val sorted = indexes.sorted()
        if (sorted.size != ref.count) return "expected ${ref.count} chunks, found ${sorted.size}"
        sorted.forEachIndexed { i, index -> if (index != i) return "chunk $i missing" }
        return null
    }

    /** UTF-8 bytes of [pieces] joined, read lazily one piece at a time (for streaming a large value). */
    fun utf8Stream(pieces: Iterator<String>): InputStream = object : InputStream() {
        private var current: ByteArray = ByteArray(0)
        private var pos = 0

        private fun fill(): Boolean {
            while (pos >= current.size) {
                if (!pieces.hasNext()) return false
                current = pieces.next().toByteArray(Charsets.UTF_8)
                pos = 0
            }
            return true
        }

        override fun read(): Int = if (fill()) current[pos++].toInt() and 0xFF else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val n = minOf(len, current.size - pos)
            System.arraycopy(current, pos, b, off, n)
            pos += n
            return n
        }
    }

    private fun prefix(text: String, maxLength: Int): String {
        if (text.length <= maxLength) return text
        var end = maxLength
        if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end)
    }

    /** The old app wrote chunks of 16,384 units; the schema-20 path used 8,192 code points (at least 8,192 units). */
    const val MAX_CHUNK = 16_384
    const val MIN_CHUNK = 8_192
}
