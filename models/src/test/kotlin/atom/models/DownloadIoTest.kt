package atom.models

import atom.core.io.deleteTreeNoFollow
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadIoTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "atom-models-test-" + System.nanoTime()).apply { mkdirs() }

    @AfterTest
    fun cleanup() {
        dir.deleteTreeNoFollow()
    }

    private fun hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // ---- Range resume planning ----

    @Test
    fun resumeOffsetRules() {
        assertEquals(0L, DownloadIo.resumeOffset(0, 1000))
        assertEquals(400L, DownloadIo.resumeOffset(400, 1000))
        assertEquals(400L, DownloadIo.resumeOffset(400, 0)) // unknown total: still resume
        assertEquals(1000L, DownloadIo.resumeOffset(1000, 1000)) // complete part -> server answers 416
        assertEquals(0L, DownloadIo.resumeOffset(1200, 1000)) // larger than file: stale, restart
        assertNull(DownloadIo.rangeHeader(0))
        assertEquals("bytes=400-", DownloadIo.rangeHeader(400))
    }

    @Test
    fun contentRangeParsing() {
        assertEquals(DownloadIo.ContentRange(100, 199, 200), DownloadIo.parseContentRange("bytes 100-199/200"))
        assertEquals(DownloadIo.ContentRange(5, 9, -1), DownloadIo.parseContentRange("bytes 5-9/*"))
        assertEquals(DownloadIo.ContentRange(-1, -1, 200), DownloadIo.parseContentRange("bytes */200"))
        assertNull(DownloadIo.parseContentRange(null))
        assertNull(DownloadIo.parseContentRange("garbage"))
        assertNull(DownloadIo.parseContentRange("bytes 9-5/10"))
    }

    @Test
    fun responsePlans() {
        // Fresh download
        assertEquals(DownloadIo.Plan.Write(0, 1000), DownloadIo.planFor(0, 200, null, 1000))
        // Server ignored our Range: start over from zero (truncate)
        assertEquals(DownloadIo.Plan.Write(0, 1000), DownloadIo.planFor(400, 200, null, 1000))
        // Proper partial content
        assertEquals(DownloadIo.Plan.Write(400, 1000), DownloadIo.planFor(400, 206, "bytes 400-999/1000", 600))
        assertEquals(DownloadIo.Plan.Write(400, -1), DownloadIo.planFor(400, 206, "bytes 400-999/*", 600))
        // Partial content for the wrong offset or without a header -> restart
        assertEquals(DownloadIo.Plan.RestartFromZero, DownloadIo.planFor(400, 206, "bytes 0-999/1000", 1000))
        assertEquals(DownloadIo.Plan.RestartFromZero, DownloadIo.planFor(400, 206, null, 600))
        // Already have everything
        assertEquals(DownloadIo.Plan.AlreadyComplete(1000), DownloadIo.planFor(1000, 416, "bytes */1000", 0))
        assertEquals(DownloadIo.Plan.RestartFromZero, DownloadIo.planFor(1200, 416, "bytes */1000", 0))
        // Errors
        assertEquals(DownloadIo.Plan.Unauthorized(401), DownloadIo.planFor(0, 401, null, 0))
        assertEquals(DownloadIo.Plan.Unauthorized(403), DownloadIo.planFor(0, 403, null, 0))
        assertIs<DownloadIo.Plan.Fatal>(DownloadIo.planFor(0, 404, null, 0))
        assertEquals(DownloadIo.Plan.Transient(503), DownloadIo.planFor(0, 503, null, 0))
        assertEquals(DownloadIo.Plan.Transient(429), DownloadIo.planFor(0, 429, null, 0))
    }

    @Test
    fun freeSpaceNeedsTenPercentMargin() {
        assertEquals(1100L, DownloadIo.requiredFreeBytes(1000, 0))
        assertEquals(700L, DownloadIo.requiredFreeBytes(1000, 400))
        assertEquals(0L, DownloadIo.requiredFreeBytes(1000, 2000))
        assertEquals(0L, DownloadIo.requiredFreeBytes(0, 0)) // unknown size: checked later
    }

    /** Simulates an interrupted transfer followed by a ranged resume and checks the result byte-for-byte. */
    @Test
    fun interruptedDownloadResumesToIdenticalFile() = runBlocking {
        val source = Random(42).nextBytes(3 * DownloadIo.BUFFER_SIZE + 12_345)
        val part = File(dir, "m.gguf.part")
        val cut = 700_001

        // First attempt dies mid-stream.
        val dying = object : InputStream() {
            var pos = 0
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= cut) throw IOException("connection reset")
                val n = minOf(len, cut - pos)
                System.arraycopy(source, pos, b, off, n)
                pos += n
                return n
            }
        }
        assertFailsWith<IOException> {
            FileOutputStream(part, false).use { out ->
                DownloadIo.copyWithProgress(dying, out, 0, DownloadIo.Throttle(0)) {}
            }
        }
        assertEquals(cut.toLong(), part.length())

        // Retry: plan the range, "server" answers 206 from the offset.
        val offset = DownloadIo.resumeOffset(part.length(), source.size.toLong())
        assertEquals("bytes=$cut-", DownloadIo.rangeHeader(offset))
        val plan = DownloadIo.planFor(offset, 206, "bytes $offset-${source.size - 1}/${source.size}", source.size - offset)
        assertEquals(DownloadIo.Plan.Write(offset, source.size.toLong()), plan)

        val progress = mutableListOf<Long>()
        val total = FileOutputStream(part, true).use { out ->
            DownloadIo.copyWithProgress(
                ByteArrayInputStream(source, offset.toInt(), source.size - offset.toInt()),
                out,
                offset,
                DownloadIo.Throttle(0),
            ) { progress += it }
        }
        assertEquals(source.size.toLong(), total)
        assertContentEquals(source, part.readBytes())
        assertEquals(source.size.toLong(), progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(DownloadIo.verifySha256(part, hex(source)))
    }

    @Test
    fun progressIsThrottled() = runBlocking {
        var now = 0L
        val throttle = DownloadIo.Throttle(500) { now }
        val calls = mutableListOf<Long>()
        // Deliver 10 chunks "instantly", then advance the clock.
        val chunks = ArrayDeque(List(10) { ByteArray(10) })
        val input = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val c = chunks.removeFirstOrNull() ?: return -1
                now += 100 // 100 ms per chunk -> at most one update per 5 chunks
                System.arraycopy(c, 0, b, off, c.size)
                return c.size
            }
        }
        DownloadIo.copyWithProgress(input, java.io.OutputStream.nullOutputStream(), 0, throttle) { calls += it }
        // first chunk, every 500 ms after, plus the final total
        assertEquals(listOf(10L, 60L, 100L), calls)
    }

    @Test
    fun sha256Verification() {
        val data = "hello atom".toByteArray()
        val f = File(dir, "x.bin").apply { writeBytes(data) }
        val good = hex(data)
        assertEquals(good, DownloadIo.sha256Hex(f))
        assertEquals(good, DownloadIo.sha256Hex(ByteArrayInputStream(data), bufferSize = 3))
        assertTrue(DownloadIo.verifySha256(f, good))
        assertTrue(DownloadIo.verifySha256(f, good.uppercase()))
        assertTrue(DownloadIo.verifySha256(f, "\"$good\"")) // raw ETag form
        assertTrue(DownloadIo.verifySha256(f, null)) // nothing to check
        assertFalse(DownloadIo.verifySha256(f, "0".repeat(64)))
        // Known vector: SHA-256 of empty input
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            DownloadIo.sha256Hex(ByteArrayInputStream(ByteArray(0))),
        )
    }

    @Test
    fun promoteReplacesAtomically() {
        val part = File(dir, "a.gguf.part").apply { writeText("new") }
        val target = File(dir, "a.gguf").apply { writeText("old") }
        DownloadIo.promote(part, target)
        assertFalse(part.exists())
        assertEquals("new", target.readText())
    }

    @Test
    fun manifestRoundTripAndPruning() {
        val m = InstalledManifest(dir)
        assertTrue(m.read().isEmpty())
        File(dir, "a.gguf").writeBytes(ByteArray(10))
        val e = ManifestEntry("a", "A", ModelFormat.Gguf, "a.gguf", 10, false, null, "https://x/a.gguf", 1)
        m.upsert(e)
        assertEquals(listOf(e), InstalledManifest(dir).read())
        assertEquals(File(dir, "a.gguf").absolutePath, m.toInstalled(e).path)
        // A model file that vanished is not reported as installed.
        File(dir, "a.gguf").delete()
        assertTrue(InstalledManifest(dir).read().isEmpty())
        // Corrupt manifest -> empty, never a crash.
        File(dir, "manifest.json").writeText("{not json")
        assertTrue(InstalledManifest(dir).read().isEmpty())
    }
}
