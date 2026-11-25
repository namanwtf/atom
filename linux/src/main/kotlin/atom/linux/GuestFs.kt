package atom.linux

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Read-only view of the Linux file system from the app side: guest paths map to the rootfs, with /workspace,
 * its shared-folder mounts, /sdcard and /tmp mapped to the host directories the launchers bind there. Symlinks
 * are resolved inside the guest root, never against the phone's own /.
 */
class GuestFs(private val rootfs: File, mounts: Map<String, File>) {
    /** Guest prefix -> host dir, longest first. */
    private val mounts: List<Pair<String, File>> = mounts.entries
        .map { (k, v) -> k.trimEnd('/').ifEmpty { "/" } to v }
        .sortedByDescending { it.first.length }

    data class Entry(
        val name: String,
        val guestPath: String,
        val isDirectory: Boolean,
        val isSymlink: Boolean,
        val linkTarget: String?,
        val sizeBytes: Long,
        val readable: Boolean,
    )

    sealed interface Preview {
        data class Text(val text: String, val truncated: Boolean, val totalBytes: Long) : Preview
        data class Binary(val totalBytes: Long) : Preview
        data class Unreadable(val reason: String) : Preview
    }

    /** Host file for a guest path, without resolving symlinks in the last component. */
    fun hostFor(guestPath: String): File {
        val path = normalize(guestPath)
        for ((prefix, dir) in mounts) {
            if (path == prefix) return dir
            if (path.startsWith("$prefix/")) return File(dir, path.removePrefix("$prefix/"))
        }
        return if (path == "/") rootfs else File(rootfs, path.removePrefix("/"))
    }

    /** Resolves symlinks component by component inside the guest; null on a loop. */
    fun resolve(guestPath: String, maxHops: Int = 32): String? {
        var pending = ArrayDeque(guestPath.split('/').filter { it.isNotEmpty() })
        val done = ArrayList<String>()
        var hops = 0
        while (pending.isNotEmpty()) {
            val part = pending.removeFirst()
            if (part == "..") {
                if (done.isNotEmpty()) done.removeAt(done.size - 1)
                continue
            }
            if (part == ".") continue
            done += part
            val current = "/" + done.joinToString("/")
            val host = hostFor(current)
            if (Files.isSymbolicLink(host.toPath())) {
                if (++hops > maxHops) return null
                val target = runCatching { Files.readSymbolicLink(host.toPath()).toString() }.getOrNull() ?: return current
                done.removeAt(done.size - 1)
                if (target.startsWith("/")) done.clear()
                pending = ArrayDeque(target.split('/').filter { it.isNotEmpty() } + pending)
            }
        }
        return "/" + done.joinToString("/")
    }

    fun list(guestPath: String): List<Entry>? {
        val dirPath = resolve(guestPath) ?: return null
        val dir = hostFor(dirPath)
        // Mount points (and the folders leading to them) show up even where the rootfs has no such folder.
        val virtual = HashSet<String>()
        val base = if (dirPath == "/") "/" else "$dirPath/"
        for ((prefix, _) in mounts) {
            if (prefix != "/" && prefix.startsWith(base) && prefix.length > base.length) {
                virtual += prefix.removePrefix(base).substringBefore('/')
            }
        }
        val names = dir.list() ?: if (virtual.isNotEmpty()) emptyArray() else return null
        val children = HashSet<String>(virtual)
        children += names
        return children.map { name ->
            val childGuest = if (dirPath == "/") "/$name" else "$dirPath/$name"
            val host = hostFor(childGuest)
            val link = Files.isSymbolicLink(host.toPath())
            val target = if (link) runCatching { Files.readSymbolicLink(host.toPath()).toString() }.getOrNull() else null
            val resolved = if (link) resolve(childGuest)?.let(::hostFor) else host
            val isDir = resolved?.isDirectory == true
            Entry(
                name = name,
                guestPath = childGuest,
                isDirectory = isDir,
                isSymlink = link,
                linkTarget = target,
                sizeBytes = if (!isDir && resolved != null && Files.exists(resolved.toPath(), LinkOption.NOFOLLOW_LINKS)) resolved.length() else 0,
                readable = resolved?.canRead() == true,
            )
        }.sortedWith(compareBy<Entry>({ !it.isDirectory }, { it.name.lowercase() }))
    }

    fun preview(guestPath: String, maxBytes: Int = PREVIEW_BYTES): Preview {
        val resolved = resolve(guestPath) ?: return Preview.Unreadable("Too many symbolic links.")
        val file = hostFor(resolved)
        if (!file.isFile) return Preview.Unreadable("Not a regular file.")
        if (!file.canRead()) return Preview.Unreadable("Permission denied (files created as root in Root mode may be unreadable here).")
        val total = file.length()
        val bytes = runCatching {
            file.inputStream().use { input ->
                val buf = ByteArray(minOf(maxBytes.toLong(), total).toInt())
                var off = 0
                while (off < buf.size) {
                    val n = input.read(buf, off, buf.size - off)
                    if (n < 0) break
                    off += n
                }
                buf.copyOf(off)
            }
        }.getOrElse { return Preview.Unreadable(it.message ?: "Read failed.") }
        if (looksBinary(bytes)) return Preview.Binary(total)
        return Preview.Text(String(bytes, Charsets.UTF_8), truncated = total > bytes.size, totalBytes = total)
    }

    companion object {
        const val PREVIEW_BYTES = 64 * 1024

        fun normalize(path: String): String {
            val parts = ArrayList<String>()
            for (p in path.split('/')) {
                when (p) {
                    "", "." -> Unit
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                    else -> parts += p
                }
            }
            return "/" + parts.joinToString("/")
        }

        fun parentOf(path: String): String = normalize(path).substringBeforeLast('/').ifEmpty { "/" }

        /**
         * Text unless it has a NUL byte or too many other control bytes in the first 8 KiB. A multi-byte UTF-8
         * sequence cut at the end of the sample is not counted against it.
         */
        fun looksBinary(bytes: ByteArray): Boolean {
            val n = minOf(bytes.size, 8192)
            if (n == 0) return false
            var control = 0
            for (i in 0 until n) {
                val b = bytes[i].toInt() and 0xFF
                if (b == 0) return true
                if (b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D && b != 0x0C && b != 0x1B && b != 0x08) control++
            }
            return control * 10 > n // more than 10 % control characters
        }
    }
}
