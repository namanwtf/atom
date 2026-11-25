package atom.voice.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16-bit PCM WAV encoding and incremental decoding. */
object Wav {
    fun encode(pcm: ByteArray, sampleRate: Int, channels: Int = 1): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        val byteRate = sampleRate * channels * 2
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort((channels * 2).toShort())
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)
        return header.array() + pcm
    }

    fun shortsToBytes(samples: ShortArray, count: Int = samples.size): ByteArray {
        val out = ByteArray(count * 2)
        for (i in 0 until count) {
            val s = samples[i].toInt()
            out[2 * i] = (s and 0xFF).toByte()
            out[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    /**
     * Incremental reader: feed bytes of a WAV stream (or raw PCM) and get PCM bytes back once the header has been
     * skipped. Streams that do not start with "RIFF" are treated as raw PCM at [fallbackRate].
     */
    class StreamDecoder(private val fallbackRate: Int) {
        var sampleRate: Int = 0
            private set
        var channels: Int = 1
            private set
        private var header = ByteArray(0)
        private var done = false
        private var raw = false

        /** Returns the PCM payload contained in [chunk] (may be empty while the header is still arriving). */
        fun feed(chunk: ByteArray): ByteArray {
            if (done) return chunk
            header += chunk
            if (header.size < 12) return ByteArray(0)
            if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF") {
                raw = true
                done = true
                sampleRate = fallbackRate
                return header.also { header = ByteArray(0) }
            }
            var pos = 12
            while (pos + 8 <= header.size) {
                val id = String(header, pos, 4, Charsets.US_ASCII)
                val size = ByteBuffer.wrap(header, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (id == "fmt ") {
                    if (pos + 8 + 16 > header.size) return ByteArray(0)
                    val b = ByteBuffer.wrap(header, pos + 8, 16).order(ByteOrder.LITTLE_ENDIAN)
                    b.short // format
                    channels = b.short.toInt().coerceAtLeast(1)
                    sampleRate = b.int
                }
                if (id == "data") {
                    done = true
                    if (sampleRate == 0) sampleRate = fallbackRate
                    val start = pos + 8
                    return header.copyOfRange(start, header.size).also { header = ByteArray(0) }
                }
                // Streaming WAVs may declare a 0 / 0xFFFFFFFF size for non-data chunks; trust it only if sane.
                if (size < 0 || size > 1 shl 20) return ByteArray(0)
                pos += 8 + size + (size and 1)
            }
            return ByteArray(0)
        }

        val isRaw: Boolean get() = raw
    }
}
