package atom.characters.png

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

class PngException(message: String) : Exception(message)

/** Minimal PNG container reader/writer: chunk framing, CRC-32 and the three text chunk kinds. Pixels are never decoded. */
object Png {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private const val MAX_TEXT_BYTES = 32 * 1024 * 1024

    class Chunk(val type: String, val data: ByteArray) {
        val isCritical: Boolean get() = type[0].isUpperCase()
    }

    fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= SIGNATURE.size && SIGNATURE.indices.all { bytes[it] == SIGNATURE[it] }

    /**
     * Splits [bytes] into chunks, stopping after IEND. With [verifyCrc] a corrupt chunk fails the read; without it,
     * corrupt ancillary chunks are dropped and corrupt critical chunks are kept as they are.
     */
    fun readChunks(bytes: ByteArray, verifyCrc: Boolean = true): List<Chunk> {
        if (!isPng(bytes)) throw PngException("Not a PNG file")
        val out = ArrayList<Chunk>()
        var at = SIGNATURE.size
        while (at < bytes.size) {
            if (at + 8 > bytes.size) throw PngException("Truncated chunk header")
            val length = readInt(bytes, at)
            if (length < 0 || at.toLong() + 12 + length > bytes.size) throw PngException("Truncated chunk")
            val type = String(bytes, at + 4, 4, Charsets.ISO_8859_1)
            if (!type.all { it in 'a'..'z' || it in 'A'..'Z' }) throw PngException("Invalid chunk type")
            val data = bytes.copyOfRange(at + 8, at + 8 + length)
            val stored = readInt(bytes, at + 8 + length)
            val ok = crc(bytes, at + 4, length + 4) == stored
            if (!ok && verifyCrc) throw PngException("CRC mismatch in $type chunk")
            if (ok || type[0].isUpperCase()) out += Chunk(type, data)
            at += 12 + length
            if (type == "IEND") break
        }
        if (out.firstOrNull()?.type != "IHDR") throw PngException("Missing IHDR")
        if (out.lastOrNull()?.type != "IEND") throw PngException("Missing IEND")
        return out
    }

    fun write(chunks: List<Chunk>): ByteArray {
        val out = ByteArrayOutputStream(chunks.sumOf { it.data.size + 12 } + SIGNATURE.size)
        out.write(SIGNATURE)
        for (c in chunks) {
            val type = c.type.toByteArray(Charsets.ISO_8859_1)
            require(type.size == 4) { "Chunk type must be 4 bytes" }
            writeInt(out, c.data.size)
            val body = type + c.data
            out.write(body)
            writeInt(out, crc(body, 0, body.size))
        }
        return out.toByteArray()
    }

    /** Keyword to text for every tEXt, zTXt and iTXt chunk; later chunks win on duplicate keywords. */
    fun texts(chunks: List<Chunk>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (c in chunks) {
            val parsed = when (c.type) {
                "tEXt" -> parseText(c.data)
                "zTXt" -> parseZText(c.data)
                "iTXt" -> parseIText(c.data)
                else -> null
            } ?: continue
            out[parsed.first] = parsed.second
        }
        return out
    }

    fun textChunk(keyword: String, text: String): Chunk {
        require(keyword.length in 1..79) { "Keyword must be 1-79 characters" }
        val k = keyword.toByteArray(Charsets.ISO_8859_1)
        val t = text.toByteArray(Charsets.ISO_8859_1)
        return Chunk("tEXt", k + byteArrayOf(0) + t)
    }

    /**
     * Returns [png] with every text chunk whose keyword is in [remove] (case-insensitive) dropped and [add] written
     * as tEXt chunks just before IEND.
     */
    fun replaceTexts(png: ByteArray, remove: Set<String>, add: List<Pair<String, String>>): ByteArray {
        val drop = remove.map { it.lowercase() }.toSet()
        val kept = readChunks(png, verifyCrc = false).filterNot { c ->
            c.type in TEXT_TYPES && keywordOf(c.data)?.lowercase() in drop
        }
        val end = kept.indexOfLast { it.type == "IEND" }
        return write(kept.subList(0, end) + add.map { textChunk(it.first, it.second) } + kept.subList(end, kept.size))
    }

    /** Removes the text chunks named in [keywords]; the image itself is untouched. */
    fun stripTexts(png: ByteArray, keywords: Set<String>): ByteArray = replaceTexts(png, keywords, emptyList())

    fun dimensions(chunks: List<Chunk>): Pair<Int, Int>? {
        val header = chunks.firstOrNull { it.type == "IHDR" }?.data ?: return null
        if (header.size < 8) return null
        return readInt(header, 0) to readInt(header, 4)
    }

    private val TEXT_TYPES = setOf("tEXt", "zTXt", "iTXt")

    private fun keywordOf(data: ByteArray): String? {
        val nul = data.indexOf(0)
        return if (nul <= 0) null else String(data, 0, nul, Charsets.ISO_8859_1)
    }

    private fun parseText(data: ByteArray): Pair<String, String>? {
        val nul = data.indexOf(0)
        if (nul <= 0) return null
        return String(data, 0, nul, Charsets.ISO_8859_1) to String(data, nul + 1, data.size - nul - 1, Charsets.ISO_8859_1)
    }

    private fun parseZText(data: ByteArray): Pair<String, String>? {
        val nul = data.indexOf(0)
        if (nul <= 0 || nul + 2 > data.size || data[nul + 1].toInt() != 0) return null
        val text = inflate(data.copyOfRange(nul + 2, data.size)) ?: return null
        return String(data, 0, nul, Charsets.ISO_8859_1) to String(text, Charsets.ISO_8859_1)
    }

    private fun parseIText(data: ByteArray): Pair<String, String>? {
        val nul = data.indexOf(0)
        if (nul <= 0 || nul + 3 > data.size) return null
        val compressed = data[nul + 1].toInt() == 1
        val langEnd = indexOf(data, 0, nul + 3).takeIf { it >= 0 } ?: return null
        val transEnd = indexOf(data, 0, langEnd + 1).takeIf { it >= 0 } ?: return null
        val raw = data.copyOfRange(transEnd + 1, data.size)
        val text = if (compressed) inflate(raw) ?: return null else raw
        return String(data, 0, nul, Charsets.ISO_8859_1) to String(text, Charsets.UTF_8)
    }

    private fun inflate(data: ByteArray): ByteArray? {
        val inflater = Inflater()
        return try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream(data.size * 3)
            val buf = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, n)
                if (out.size() > MAX_TEXT_BYTES) return null
            }
            out.toByteArray()
        } catch (_: DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun indexOf(data: ByteArray, value: Byte, from: Int): Int {
        for (i in from until data.size) if (data[i] == value) return i
        return -1
    }

    private fun readInt(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF shl 24) or (b[at + 1].toInt() and 0xFF shl 16) or
            (b[at + 2].toInt() and 0xFF shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun writeInt(out: ByteArrayOutputStream, v: Int) {
        out.write(v ushr 24 and 0xFF)
        out.write(v ushr 16 and 0xFF)
        out.write(v ushr 8 and 0xFF)
        out.write(v and 0xFF)
    }

    private val CRC_TABLE = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) 0xEDB88320.toInt() xor (c ushr 1) else c ushr 1 }
        c
    }

    fun crc(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Int {
        var c = -1
        for (i in offset until offset + length) c = CRC_TABLE[(c xor bytes[i].toInt()) and 0xFF] xor (c ushr 8)
        return c.inv()
    }
}
