package atom.core.legacy

/**
 * A minimal reader for Jetpack DataStore's preferences file (`*.preferences_pb`), a protobuf `PreferenceMap`:
 * field 1 is a repeated map entry with key (1, string) and value (2, message `Value`). `Value` is a oneof of
 * boolean (1), float (2), integer (3, int32), long (4), string (5), string set (6), double (7) and bytes (8).
 */
object PreferencesProto {
    sealed interface Value {
        data class Bool(val value: Boolean) : Value
        data class Float32(val value: Float) : Value
        data class Int32(val value: Int) : Value
        data class Int64(val value: Long) : Value
        data class Str(val value: String) : Value
        data class StrSet(val value: Set<String>) : Value
        data class Float64(val value: Double) : Value
        class Bytes(val value: ByteArray) : Value
    }

    class MalformedException(message: String) : Exception(message)

    /** Every entry of the file. Throws [MalformedException] for bytes that are not a valid preferences map. */
    fun decode(bytes: ByteArray): Map<String, Value> {
        val out = LinkedHashMap<String, Value>()
        val reader = Reader(bytes, 0, bytes.size)
        while (!reader.done) {
            val tag = reader.varint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7).toInt()
            if (field == 1 && wire == WIRE_LEN) {
                val pair = entry(reader.sub())
                if (pair != null) out[pair.first] = pair.second
            } else {
                reader.skip(wire)
            }
        }
        return out
    }

    private fun entry(r: Reader): Pair<String, Value>? {
        var key: String? = null
        var value: Value? = null
        while (!r.done) {
            val tag = r.varint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7).toInt()
            when {
                field == 1 && wire == WIRE_LEN -> key = r.string()
                field == 2 && wire == WIRE_LEN -> value = value(r.sub())
                else -> r.skip(wire)
            }
        }
        return if (key != null && value != null) key to value else null
    }

    private fun value(r: Reader): Value? {
        var value: Value? = null
        while (!r.done) {
            val tag = r.varint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7).toInt()
            value = when {
                field == 1 && wire == WIRE_VARINT -> Value.Bool(r.varint() != 0L)
                field == 2 && wire == WIRE_FIXED32 -> Value.Float32(Float.fromBits(r.fixed32()))
                field == 3 && wire == WIRE_VARINT -> Value.Int32(r.varint().toInt())
                field == 4 && wire == WIRE_VARINT -> Value.Int64(r.varint())
                field == 5 && wire == WIRE_LEN -> Value.Str(r.string())
                field == 6 && wire == WIRE_LEN -> Value.StrSet(stringSet(r.sub()))
                field == 7 && wire == WIRE_FIXED64 -> Value.Float64(Double.fromBits(r.fixed64()))
                field == 8 && wire == WIRE_LEN -> Value.Bytes(r.bytes())
                else -> {
                    r.skip(wire)
                    value
                }
            }
        }
        return value
    }

    private fun stringSet(r: Reader): Set<String> {
        val out = LinkedHashSet<String>()
        while (!r.done) {
            val tag = r.varint()
            if ((tag ushr 3).toInt() == 1 && (tag and 7).toInt() == WIRE_LEN) out += r.string() else r.skip((tag and 7).toInt())
        }
        return out
    }

    private class Reader(private val buf: ByteArray, private var pos: Int, private val end: Int) {
        val done: Boolean get() = pos >= end

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                if (pos >= end) throw MalformedException("truncated varint")
                val b = buf[pos++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            throw MalformedException("varint too long")
        }

        fun fixed32(): Int {
            need(4)
            var v = 0
            for (i in 0 until 4) v = v or ((buf[pos + i].toInt() and 0xFF) shl (8 * i))
            pos += 4
            return v
        }

        fun fixed64(): Long {
            need(8)
            var v = 0L
            for (i in 0 until 8) v = v or ((buf[pos + i].toLong() and 0xFF) shl (8 * i))
            pos += 8
            return v
        }

        private fun length(): Int {
            val len = varint()
            if (len < 0 || len > end - pos) throw MalformedException("length out of range")
            return len.toInt()
        }

        fun sub(): Reader {
            val len = length()
            val r = Reader(buf, pos, pos + len)
            pos += len
            return r
        }

        fun bytes(): ByteArray {
            val len = length()
            val out = buf.copyOfRange(pos, pos + len)
            pos += len
            return out
        }

        fun string(): String = String(bytes(), Charsets.UTF_8)

        fun skip(wire: Int) {
            when (wire) {
                WIRE_VARINT -> varint()
                WIRE_FIXED64 -> { need(8); pos += 8 }
                WIRE_LEN -> pos += length()
                WIRE_FIXED32 -> { need(4); pos += 4 }
                else -> throw MalformedException("unsupported wire type $wire")
            }
        }

        private fun need(n: Int) {
            if (end - pos < n) throw MalformedException("truncated field")
        }
    }

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LEN = 2
    private const val WIRE_FIXED32 = 5
}
