package atom.linux.install

import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission

/** File-system operations the extractor needs; on Android these use android.system.Os for full mode bits. */
internal interface FsOps {
    fun symlink(target: String, link: File)

    /** Returns false when the file system refuses hard links (Android app sandboxes often do). */
    fun hardlink(existing: File, link: File): Boolean

    fun chmod(file: File, mode: Int)

    fun isSymlink(file: File): Boolean = Files.isSymbolicLink(file.toPath())

    /** True if anything (including a dangling symlink) exists at [file]. */
    fun exists(file: File): Boolean = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
}

/** Portable implementation (JVM tests; also works on Android, minus setuid/setgid/sticky bits). */
internal object NioFsOps : FsOps {
    override fun symlink(target: String, link: File) {
        Files.createSymbolicLink(link.toPath(), Paths.get(target))
    }

    override fun hardlink(existing: File, link: File): Boolean = try {
        Files.createLink(link.toPath(), existing.toPath())
        true
    } catch (e: Exception) {
        false
    }

    override fun chmod(file: File, mode: Int) {
        val perms = HashSet<PosixFilePermission>()
        val bits = PosixFilePermission.entries // OWNER_READ .. OTHERS_EXECUTE, high bit first
        for ((i, p) in bits.withIndex()) if (mode and (1 shl (8 - i)) != 0) perms += p
        try {
            Files.setPosixFilePermissions(file.toPath(), perms)
        } catch (_: UnsupportedOperationException) {
            file.setReadable(mode and 0b100_000_000 != 0)
            file.setWritable(mode and 0b010_000_000 != 0)
            file.setExecutable(mode and 0b001_000_000 != 0)
        }
    }
}

/**
 * A small streaming tar reader/extractor: ustar, pax (path/linkpath/size) and GNU long names; regular
 * files, directories, symlinks and hard links. Device nodes and FIFOs are skipped (the host's /dev is
 * bound in at run time).
 *
 * Safety: entries are confined to [root]. Absolute paths are made relative, `..` entries are skipped, and
 * nothing is ever written through a symlink, so a hostile archive cannot escape the target directory.
 * Owners always keep read/write (and search on directories) so the tree stays manageable without root.
 */
internal class TarExtractor(private val root: File, private val fs: FsOps = NioFsOps) {

    data class Stats(
        var files: Int = 0,
        var directories: Int = 0,
        var symlinks: Int = 0,
        var hardlinks: Int = 0,
        /** Hard links stored as copies because the file system refused link(2). */
        var hardlinkCopies: Int = 0,
        var skipped: Int = 0,
        var bytes: Long = 0,
    )

    private val stats = Stats()
    private val safeDirs = HashSet<String>()
    private val dirModes = ArrayList<Pair<File, Int>>()
    private val canonicalRoot = root.absoluteFile

    /** Extracts the (already decompressed) tar [input]. Call [onProgress] hooks via the input stream. */
    fun extract(input: InputStream, isCancelled: () -> Boolean = { false }): Stats {
        canonicalRoot.mkdirs()
        safeDirs += canonicalRoot.path
        val header = ByteArray(BLOCK)
        var paxPath: String? = null
        var paxLink: String? = null
        var paxSize: Long? = null
        var longName: String? = null
        var longLink: String? = null
        var zeroBlocks = 0

        while (true) {
            if (isCancelled()) throw Cancelled()
            if (!readBlock(input, header)) break
            if (header.all { it.toInt() == 0 }) {
                if (++zeroBlocks == 2) break
                continue
            }
            zeroBlocks = 0
            if (!checksumOk(header)) throw IOException("Corrupt archive (bad tar header checksum).")

            val type = header[156].toInt().toChar()
            val headerSize = parseNumber(header, 124, 12)
            if (headerSize < 0) throw IOException("Corrupt archive (bad entry size).")

            when (type) {
                'x', 'g' -> {
                    val records = parsePax(readString(input, headerSize))
                    if (type == 'x') { // global ('g') records carry nothing we use
                        records["path"]?.let { paxPath = it }
                        records["linkpath"]?.let { paxLink = it }
                        records["size"]?.toLongOrNull()?.let { paxSize = it }
                    }
                    continue
                }
                'L' -> {
                    longName = readString(input, headerSize).trimEnd('\u0000')
                    continue
                }
                'K' -> {
                    longLink = readString(input, headerSize).trimEnd('\u0000')
                    continue
                }
            }

            val size = paxSize ?: headerSize
            val name = longName ?: paxPath ?: headerName(header)
            val link = longLink ?: paxLink ?: cString(header, 157, 100)
            val mode = parseNumber(header, 100, 8).toInt() and 0xFFF
            longName = null; longLink = null; paxPath = null; paxLink = null; paxSize = null

            val target = resolve(name)
            if (target == null) {
                if (!isRootName(name)) stats.skipped++ // "./" itself is expected, not a skip
                skip(input, size)
                continue
            }
            when (type) {
                '0', '\u0000', '7' -> writeFile(target, input, size, mode)
                '5' -> {
                    makeDir(target, mode)
                    skip(input, size)
                }
                '2' -> {
                    skip(input, size)
                    makeSymlink(target, link)
                }
                '1' -> {
                    skip(input, size)
                    makeHardlink(target, link, mode)
                }
                else -> { // char/block devices, FIFOs, unknown types
                    stats.skipped++
                    skip(input, size)
                }
            }
        }
        // Directory modes last (deepest first) so restrictive modes cannot block extraction.
        for ((dir, mode) in dirModes.asReversed()) chmodQuietly(dir, mode or 0b111_000_000)
        return stats
    }

    class Cancelled : IOException("Extraction cancelled")

    // ------------------------------------------------------------------ entries

    private fun writeFile(target: File, input: InputStream, size: Long, mode: Int) {
        if (!prepareParent(target) || !clearForNonDirectory(target)) {
            stats.skipped++
            skip(input, size)
            return
        }
        FileOutputStream(target).use { out ->
            var left = size
            val buf = ByteArray(64 * 1024)
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) throw EOFException("Archive ended inside a file")
                out.write(buf, 0, n)
                left -= n
            }
        }
        skipPadding(input, size)
        chmodQuietly(target, mode or 0b110_000_000)
        stats.files++
        stats.bytes += size
    }

    private fun makeDir(target: File, mode: Int) {
        if (!prepareParent(target)) {
            stats.skipped++
            return
        }
        if (fs.isSymlink(target) || (fs.exists(target) && !target.isDirectory)) target.delete()
        if (!target.isDirectory && !target.mkdir()) throw IOException("Cannot create ${relative(target)}")
        safeDirs += target.path
        dirModes += target to mode
        stats.directories++
    }

    private fun makeSymlink(target: File, link: String) {
        if (link.isEmpty() || !prepareParent(target) || !clearForNonDirectory(target)) {
            stats.skipped++
            return
        }
        // The link text is stored verbatim: it is resolved inside the guest root at run time, never by us.
        fs.symlink(link, target)
        stats.symlinks++
    }

    private fun makeHardlink(target: File, link: String, mode: Int) {
        val existing = resolve(link)
        if (existing == null || !existing.isFile || fs.isSymlink(existing) || !insideSafeDirs(existing) ||
            !prepareParent(target) || !clearForNonDirectory(target)
        ) {
            stats.skipped++
            return
        }
        if (fs.hardlink(existing, target)) {
            stats.hardlinks++
        } else {
            existing.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
            chmodQuietly(target, mode or 0b110_000_000)
            stats.hardlinkCopies++
        }
    }

    // ------------------------------------------------------------------ path safety

    /** Maps an archive path into [root], or null if it is unsafe or names the root itself. */
    private fun resolve(name: String): File? {
        val parts = name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return File(canonicalRoot, parts.joinToString(File.separator))
    }

    private fun isRootName(name: String) = name.replace('\\', '/').split('/').all { it.isEmpty() || it == "." }

    /** Ensures every parent of [target] is a real directory inside root (creating missing ones). */
    private fun prepareParent(target: File): Boolean {
        val parent = target.parentFile ?: return false
        if (parent.path in safeDirs) return true
        val chain = ArrayList<File>()
        var p: File? = parent
        while (p != null && p.path !in safeDirs) {
            chain += p
            p = p.parentFile
        }
        if (p == null) return false // escaped the root
        for (dir in chain.asReversed()) {
            if (fs.isSymlink(dir)) return false // never write through a link
            if (!dir.exists() && !dir.mkdir()) return false
            if (!dir.isDirectory) return false
            safeDirs += dir.path
        }
        return true
    }

    private fun insideSafeDirs(file: File): Boolean = file.parentFile?.path?.let { it in safeDirs } == true

    /** Removes whatever is at [target] so a file/link can take its place; refuses to replace a non-empty dir. */
    private fun clearForNonDirectory(target: File): Boolean {
        if (!fs.exists(target)) return true
        if (!fs.isSymlink(target) && target.isDirectory) {
            if (target.list()?.isNotEmpty() == true) return false
            safeDirs.remove(target.path)
        }
        return target.delete()
    }

    private fun relative(f: File) = f.path.removePrefix(canonicalRoot.path)

    private fun chmodQuietly(file: File, mode: Int) {
        try {
            fs.chmod(file, mode)
        } catch (_: Exception) {
            // best effort; the default mode is still usable
        }
    }

    // ------------------------------------------------------------------ tar format

    private fun headerName(h: ByteArray): String {
        val name = cString(h, 0, 100)
        val ustar = h[257] == 'u'.code.toByte() && h[258] == 's'.code.toByte() && h[259] == 't'.code.toByte()
        val prefix = if (ustar) cString(h, 345, 155) else ""
        return if (prefix.isNotEmpty()) "$prefix/$name" else name
    }

    private fun checksumOk(h: ByteArray): Boolean {
        val stored = parseNumber(h, 148, 8)
        var unsigned = 0L
        var signed = 0L
        for (i in 0 until BLOCK) {
            val b = if (i in 148 until 156) ' '.code.toByte() else h[i]
            unsigned += b.toInt() and 0xFF
            signed += b.toInt()
        }
        return stored == unsigned || stored == signed
    }

    private fun readBlock(input: InputStream, block: ByteArray): Boolean {
        var off = 0
        while (off < BLOCK) {
            val n = input.read(block, off, BLOCK - off)
            if (n < 0) {
                if (off == 0) return false
                throw EOFException("Archive ended inside a header")
            }
            off += n
        }
        return true
    }

    private fun readString(input: InputStream, size: Long): String {
        if (size > MAX_META) throw IOException("Archive metadata entry too large")
        val bytes = ByteArray(size.toInt())
        var off = 0
        while (off < bytes.size) {
            val n = input.read(bytes, off, bytes.size - off)
            if (n < 0) throw EOFException("Archive ended inside a metadata entry")
            off += n
        }
        skipPadding(input, size)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun skip(input: InputStream, size: Long) {
        skipExactly(input, size)
        skipPadding(input, size)
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val pad = (BLOCK - (size % BLOCK)) % BLOCK
        skipExactly(input, pad)
    }

    private fun skipExactly(input: InputStream, count: Long) {
        var left = count
        val buf = ByteArray(8192)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw EOFException("Archive ended early")
            left -= n
        }
    }

    companion object {
        const val BLOCK = 512
        private const val MAX_META = 1L shl 20

        fun cString(h: ByteArray, off: Int, len: Int): String {
            var end = off
            while (end < off + len && h[end].toInt() != 0) end++
            return String(h, off, end - off, Charsets.UTF_8)
        }

        /** Octal (NUL/space padded) or GNU base-256 (high bit set) numeric field. */
        fun parseNumber(h: ByteArray, off: Int, len: Int): Long {
            if (h[off].toInt() and 0x80 != 0) {
                var v = (h[off].toLong() and 0x7F)
                for (i in off + 1 until off + len) v = (v shl 8) or (h[i].toLong() and 0xFF)
                return v
            }
            var v = 0L
            var i = off
            while (i < off + len && (h[i] == ' '.code.toByte() || h[i].toInt() == 0)) i++
            while (i < off + len) {
                val c = h[i].toInt()
                if (c < '0'.code || c > '7'.code) break
                v = v * 8 + (c - '0'.code)
                i++
            }
            return v
        }

        /** Parses pax records: "LEN key=value\n" repeated. */
        fun parsePax(text: String): Map<String, String> {
            val out = HashMap<String, String>()
            val bytes = text.toByteArray(Charsets.UTF_8)
            var pos = 0
            while (pos < bytes.size) {
                var sp = pos
                while (sp < bytes.size && bytes[sp] != ' '.code.toByte()) sp++
                val len = String(bytes, pos, sp - pos, Charsets.US_ASCII).toIntOrNull() ?: break
                if (len <= 0 || pos + len > bytes.size) break
                val record = String(bytes, sp + 1, pos + len - sp - 2, Charsets.UTF_8) // drop trailing \n
                val eq = record.indexOf('=')
                if (eq > 0) out[record.substring(0, eq)] = record.substring(eq + 1)
                pos += len
            }
            return out
        }
    }
}
