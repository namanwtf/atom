package atom.extend.skills

import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

data class SkillLimits(
    val maxArchiveBytes: Long = 10L shl 20,
    val maxFiles: Int = 300,
    val maxFileBytes: Long = 5L shl 20,
    val maxTotalBytes: Long = 25L shl 20,
)

/** A validated skill unpacked into a staging directory, ready to be committed by [SkillStore]. */
data class StagedSkill(val manifest: SkillManifest, val dir: File)

/**
 * Unpacks one skill from a ZIP. Paths are checked before anything is written (no absolute paths, no "..", no
 * drive letters or backslashes, no duplicates) and again after resolving (zip-slip), and sizes are counted from
 * the bytes actually inflated, never from the headers.
 */
object SkillZip {
    private val JUNK = listOf("__MACOSX/", ".DS_Store", "Thumbs.db")

    fun safeRelativePath(name: String): String? {
        if (name.isEmpty() || name.length > 512) return null
        if (name.any { it == '\u0000' || it == '\\' || it.code < 0x20 }) return null
        if (name.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(name)) return null
        val parts = name.trimEnd('/').split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        return parts.joinToString("/")
    }

    fun extract(input: InputStream, staging: File, limits: SkillLimits = SkillLimits()): StagedSkill {
        val raw = File(staging, "raw").apply { deleteRecursively(); mkdirs() }
        val base = raw.canonicalFile
        val written = mutableListOf<String>()
        var total = 0L
        try {
            ZipInputStream(Capped(input, limits.maxArchiveBytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (JUNK.any { name.startsWith(it) || name.endsWith("/$it") || name == it }) continue
                    val path = safeRelativePath(name) ?: throw SkillException("Unsafe path in ZIP: \"${name.take(80)}\".")
                    if (entry.isDirectory) continue
                    if (path in written) throw SkillException("The ZIP lists \"$path\" twice.")
                    if (written.size >= limits.maxFiles) throw SkillException("The ZIP has more than ${limits.maxFiles} files.")
                    val target = File(raw, path).canonicalFile
                    if (!target.path.startsWith(base.path + File.separator)) throw SkillException("Unsafe path in ZIP: \"$path\".")
                    target.parentFile?.mkdirs()
                    var size = 0L
                    target.outputStream().use { out ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            size += n
                            total += n
                            if (size > limits.maxFileBytes) throw SkillException("\"$path\" is larger than ${limits.maxFileBytes shr 20} MiB.")
                            if (total > limits.maxTotalBytes) throw SkillException("The skill unpacks to more than ${limits.maxTotalBytes shr 20} MiB.")
                            out.write(buf, 0, n)
                        }
                    }
                    written += path
                }
            }
        } catch (e: ZipException) {
            throw SkillException("Not a valid ZIP file: ${e.message}")
        }
        val rootPath = skillRoot(written)
        val root = if (rootPath.isEmpty()) raw else File(raw, rootPath)
        val manifest = SkillManifest.parse(File(root, SkillManifest.FILE).readText()).getOrElse { throw it }
        return StagedSkill(manifest, root)
    }

    /** Directory (relative, "" for the archive root) of the one SKILL.md closest to the root. */
    fun skillRoot(paths: List<String>): String {
        val manifests = paths.filter { it.substringAfterLast('/') == SkillManifest.FILE }
        if (manifests.isEmpty()) throw SkillException("The ZIP has no SKILL.md.")
        val depth = manifests.minOf { it.count { c -> c == '/' } }
        val top = manifests.filter { it.count { c -> c == '/' } == depth }
        if (top.size > 1) throw SkillException("The ZIP holds ${top.size} skills; import one skill per ZIP.")
        val root = top.single().substringBeforeLast('/', "")
        if (root.isNotEmpty() && manifests.any { !it.startsWith("$root/") }) {
            throw SkillException("The ZIP holds more than one skill; import one skill per ZIP.")
        }
        return root
    }

    private class Capped(input: InputStream, private val max: Long) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int = super.read().also { if (it >= 0) bump(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) bump(it.toLong()) }

        private fun bump(n: Long) {
            count += n
            if (count > max) throw SkillException("The ZIP is larger than ${max shr 20} MiB.")
        }
    }
}
