package atom.models

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Android-free pieces of the download pipeline: resume planning, copying and verification. */
internal object DownloadIo {

    const val BUFFER_SIZE = 256 * 1024
    const val PROGRESS_INTERVAL_MS = 500L

    /** Byte offset to resume from given an existing `.part` file of [partLength] bytes. */
    fun resumeOffset(partLength: Long, expectedTotal: Long): Long = when {
        partLength <= 0L -> 0L
        expectedTotal > 0L && partLength > expectedTotal -> 0L // stale or different file: start over
        else -> partLength
    }

    /** `Range` header value for [offset], or null when downloading from the start. */
    fun rangeHeader(offset: Long): String? = if (offset > 0L) "bytes=$offset-" else null

    data class ContentRange(val start: Long, val end: Long, val total: Long)

    /** Parses `bytes 100-199/200`, `bytes 100-199/ *` (total = -1) or `bytes * /200` (start = end = -1). */
    fun parseContentRange(value: String?): ContentRange? {
        val v = value?.trim() ?: return null
        val m = Regex("""^bytes\s+(?:(\d+)-(\d+)|\*)/(\d+|\*)$""", RegexOption.IGNORE_CASE).find(v) ?: return null
        val (s, e, t) = m.destructured
        val total = if (t == "*") -1L else t.toLongOrNull() ?: return null
        return if (s.isEmpty()) {
            ContentRange(-1, -1, total)
        } else {
            val start = s.toLongOrNull() ?: return null
            val end = e.toLongOrNull() ?: return null
            if (end < start) null else ContentRange(start, end, total)
        }
    }

    /** What to do with an HTTP response to a (possibly ranged) GET. */
    sealed interface Plan {
        /** Write the body starting at [offset]; [total] is the full file size or -1 if unknown. */
        data class Write(val offset: Long, val total: Long) : Plan

        /** The `.part` file already holds the whole file. */
        data class AlreadyComplete(val total: Long) : Plan

        /** Throw the `.part` away and try again from zero. */
        data object RestartFromZero : Plan

        /** Needs a Hugging Face token (or a different one). */
        data class Unauthorized(val code: Int) : Plan

        /** Temporary server problem; retry later. */
        data class Transient(val code: Int) : Plan

        /** Permanent failure. */
        data class Fatal(val code: Int, val message: String) : Plan
    }

    fun planFor(
        requestedOffset: Long,
        code: Int,
        contentRange: String?,
        contentLength: Long,
    ): Plan = when (code) {
        200 -> Plan.Write(offset = 0L, total = contentLength) // server ignored Range: overwrite from start
        206 -> {
            val cr = parseContentRange(contentRange)
            when {
                cr == null || cr.start != requestedOffset -> Plan.RestartFromZero
                else -> Plan.Write(offset = requestedOffset, total = if (cr.total > 0) cr.total else -1L)
            }
        }
        416 -> {
            val total = parseContentRange(contentRange)?.total ?: -1L
            if (total > 0 && total == requestedOffset) Plan.AlreadyComplete(total) else Plan.RestartFromZero
        }
        401, 403 -> Plan.Unauthorized(code)
        404, 410 -> Plan.Fatal(code, "File not found at this link (HTTP $code).")
        408, 425, 429 -> Plan.Transient(code)
        in 500..599 -> Plan.Transient(code)
        else -> Plan.Fatal(code, "Server answered HTTP $code.")
    }

    /** Free space needed before starting: 1.1x the file, minus what is already on disk. */
    fun requiredFreeBytes(totalBytes: Long, alreadyHave: Long): Long {
        if (totalBytes <= 0L) return 0L
        val withMargin = totalBytes + totalBytes / 10
        return (withMargin - alreadyHave).coerceAtLeast(0L)
    }

    /** Lets an event through at most once per [intervalMs]. */
    class Throttle(private val intervalMs: Long, private val clock: () -> Long = System::currentTimeMillis) {
        private var last = Long.MIN_VALUE
        fun ready(): Boolean {
            val now = clock()
            if (last == Long.MIN_VALUE || now - last >= intervalMs) {
                last = now
                return true
            }
            return false
        }
    }

    /**
     * Copies [input] to [output] with a single reusable buffer, reporting the running total
     * (starting at [startBytes]) through [onProgress] at most as often as [throttle] allows.
     * Checks for coroutine cancellation between chunks. Returns the final running total.
     */
    suspend fun copyWithProgress(
        input: InputStream,
        output: OutputStream,
        startBytes: Long,
        throttle: Throttle,
        bufferSize: Int = BUFFER_SIZE,
        onProgress: suspend (Long) -> Unit,
    ): Long {
        val buffer = ByteArray(bufferSize)
        var written = startBytes
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buffer)
            if (n < 0) break
            if (n == 0) continue
            output.write(buffer, 0, n)
            written += n
            if (throttle.ready()) onProgress(written)
        }
        output.flush()
        onProgress(written)
        return written
    }

    fun sha256Hex(input: InputStream, bufferSize: Int = BUFFER_SIZE): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(bufferSize)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            md.update(buffer, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(file: File): String = file.inputStream().use { sha256Hex(it) }

    /** True when [expected] is null/blank (nothing to check) or matches the file's SHA-256. */
    fun verifySha256(file: File, expected: String?): Boolean {
        val want = expected?.trim()?.removeSurrounding("\"")?.lowercase()
        if (want.isNullOrEmpty()) return true
        return sha256Hex(file) == want
    }

    /** Moves the finished `.part` into place, replacing any older copy. */
    fun promote(part: File, target: File) {
        // rename(2) within one directory is atomic and replaces an existing target.
        Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
