package atom.web.files

import atom.web.PrivilegedFiles
import atom.web.PrivilegedScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext

data class FileEntry(
    val path: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val isLink: Boolean = false,
    val readable: Boolean = true,
    val writable: Boolean = true,
)

data class GrepHit(val path: String, val line: Int, val text: String)

/** A file system the agent tools work on: the app user's view, root, or the Linux environment. */
interface Fs {
    val label: String
    suspend fun stat(path: String): FileEntry?
    suspend fun list(path: String): List<FileEntry>
    suspend fun read(path: String, maxBytes: Int): ByteArray
    suspend fun write(path: String, bytes: ByteArray, append: Boolean)

    /** Files under [root], at most [limit], relative paths. */
    suspend fun files(root: String, maxDepth: Int, limit: Int): List<String>

    suspend fun grep(root: String, text: String, ignoreCase: Boolean, limit: Int): List<GrepHit>
}

class LocalFs : Fs {
    override val label = "app"

    override suspend fun stat(path: String): FileEntry? = withContext(Dispatchers.IO) {
        val f = File(path)
        if (!f.exists() && !isLink(f)) null else entry(f)
    }

    override suspend fun list(path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        val dir = File(path)
        if (!dir.isDirectory) throw IOException(if (dir.exists()) "$path is not a directory" else "$path does not exist")
        val kids = dir.listFiles() ?: throw IOException("Permission denied: $path")
        kids.map(::entry)
    }

    override suspend fun read(path: String, maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        File(path).inputStream().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (out.size() < maxBytes) {
                val n = input.read(buf, 0, minOf(buf.size, maxBytes - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    }

    override suspend fun write(path: String, bytes: ByteArray, append: Boolean) = withContext(Dispatchers.IO) {
        val f = File(path)
        f.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Cannot create folder ${it.path}") }
        FileOutputStream(f, append).use { it.write(bytes) }
    }

    override suspend fun files(root: String, maxDepth: Int, limit: Int): List<String> = withContext(Dispatchers.IO) {
        val base = File(root)
        if (!base.isDirectory) throw IOException(if (base.exists()) "$root is not a directory" else "$root does not exist")
        val ctx = coroutineContext
        base.walkTopDown().maxDepth(maxDepth).onEnter { !isLink(it) || it == base }
            .filter { ctx.ensureActive(); it.isFile }
            .take(limit)
            .map { it.relativeTo(base).path }
            .toList()
    }

    override suspend fun grep(root: String, text: String, ignoreCase: Boolean, limit: Int): List<GrepHit> = withContext(Dispatchers.IO) {
        val base = File(root)
        val targets = if (base.isFile) sequenceOf(base) else base.walkTopDown().maxDepth(24).filter { it.isFile && it.length() < 8L * 1024 * 1024 }
        val out = ArrayList<GrepHit>()
        val ctx = coroutineContext
        for (f in targets) {
            ctx.ensureActive()
            if (out.size >= limit) break
            val head = runCatching { f.inputStream().use { s -> ByteArray(4096).let { b -> b.copyOf(s.read(b).coerceAtLeast(0)) } } }.getOrNull() ?: continue
            if (Sizes.looksBinary(head)) continue
            runCatching {
                f.bufferedReader().useLines { lines ->
                    lines.forEachIndexed { i, line ->
                        if (out.size < limit && line.contains(text, ignoreCase)) out += GrepHit(f.path, i + 1, line.take(400))
                    }
                }
            }
        }
        out
    }

    private fun isLink(f: File) = runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)

    private fun entry(f: File) = FileEntry(
        f.path, f.name, f.isDirectory, if (f.isFile) f.length() else 0L, f.lastModified(), isLink(f), f.canRead(), f.canWrite(),
    )
}

/** Runs the same operations through a root or Linux shell. Commands stick to POSIX + toybox/busybox options. */
class ShellFs(private val priv: PrivilegedFiles, private val scope: PrivilegedScope) : Fs {
    override val label = scope.name.lowercase()

    private suspend fun sh(cmd: String, stdin: ByteArray? = null, timeout: Long = 30_000) = priv.exec(scope, cmd, stdin, timeout)

    override suspend fun stat(path: String): FileEntry? {
        val r = sh("stat -c '%F|%s|%Y|%A|%n' -- ${q(path)}")
        if (!r.ok) return null
        return parseStat(r.text.trim().lineSequence().firstOrNull() ?: return null)
    }

    override suspend fun list(path: String): List<FileEntry> {
        val r = sh("[ -d ${q(path)} ] || { echo 'not a directory' >&2; exit 2; }; find ${q(path)} -mindepth 1 -maxdepth 1 -exec stat -c '%F|%s|%Y|%A|%n' {} +")
        if (!r.ok) throw IOException(r.stderr.trim().ifBlank { "Cannot list $path" })
        return r.text.lineSequence().filter { it.isNotBlank() }.mapNotNull(::parseStat).toList()
    }

    override suspend fun read(path: String, maxBytes: Int): ByteArray {
        val r = sh("head -c $maxBytes -- ${q(path)}", timeout = 60_000)
        if (!r.ok) throw IOException(r.stderr.trim().ifBlank { "Cannot read $path" })
        return r.stdout
    }

    override suspend fun write(path: String, bytes: ByteArray, append: Boolean) {
        val dir = path.substringBeforeLast('/', "")
        val mk = if (dir.isNotEmpty()) "mkdir -p ${q(dir)} && " else ""
        val r = sh("${mk}cat ${if (append) ">>" else ">"} ${q(path)}", bytes, 60_000)
        if (!r.ok) throw IOException(r.stderr.trim().ifBlank { "Cannot write $path" })
    }

    override suspend fun files(root: String, maxDepth: Int, limit: Int): List<String> {
        val r = sh("find ${q(root)} -mindepth 1 -maxdepth $maxDepth -type f 2>/dev/null | head -n $limit", timeout = 60_000)
        val prefix = root.trimEnd('/') + "/"
        return r.text.lineSequence().filter { it.isNotBlank() }.map { it.removePrefix(prefix) }.toList()
    }

    override suspend fun grep(root: String, text: String, ignoreCase: Boolean, limit: Int): List<GrepHit> {
        val flags = if (ignoreCase) "-rnFi" else "-rnF"
        val r = sh("grep $flags -e ${q(text)} -- ${q(root)} 2>/dev/null | head -n ${limit * 4}", timeout = 90_000)
        return r.text.lineSequence().mapNotNull { line ->
            val m = Regex("^(.*?):(\\d+):(.*)$").find(line) ?: return@mapNotNull null
            if (m.groupValues[3].startsWith("Binary file")) return@mapNotNull null
            GrepHit(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].take(400))
        }.take(limit).toList()
    }

    private fun parseStat(line: String): FileEntry? {
        val parts = line.split('|', limit = 5)
        if (parts.size < 5) return null
        val type = parts[0]
        val path = parts[4]
        val mode = parts[3]
        return FileEntry(
            path = path,
            name = path.trimEnd('/').substringAfterLast('/'),
            isDir = type.startsWith("directory"),
            size = parts[1].toLongOrNull() ?: 0,
            modified = (parts[2].toLongOrNull() ?: 0) * 1000,
            isLink = type.contains("link"),
            readable = mode.getOrNull(1) == 'r',
            writable = mode.getOrNull(2) == 'w',
        )
    }

    companion object {
        fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
    }
}
