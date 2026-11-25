package atom.characters

import atom.characters.png.Png
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

object TestPngs {
    fun deflate(bytes: ByteArray): ByteArray {
        val d = Deflater()
        d.setInput(bytes)
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    private fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    /** A valid 1x1 RGB PNG plus any [extra] chunks before IDAT. */
    fun tiny(extra: List<Png.Chunk> = emptyList()): ByteArray {
        val ihdr = int(1) + int(1) + byteArrayOf(8, 2, 0, 0, 0)
        val idat = deflate(byteArrayOf(0, 255.toByte(), 255.toByte(), 255.toByte()))
        return Png.write(listOf(Png.Chunk("IHDR", ihdr)) + extra + listOf(Png.Chunk("IDAT", idat), Png.Chunk("IEND", ByteArray(0))))
    }
}
