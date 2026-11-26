package atom.core.legacy

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

        /** [lengthMismatch] is a warning only: the joined text is still used. */
        data class Ok(override val text: String, val lengthMismatch: Boolean = false) : Resolved

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
}
