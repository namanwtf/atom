package atom.core.io

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Deletes this file or directory tree WITHOUT ever following symbolic links: a link is removed as a link, the thing
 * it points at is never touched. Use this instead of Kotlin's [File.deleteRecursively], which follows links into
 * directories, so a tree containing a link to "/" (a Linux rootfs, an extracted archive, a test fixture) would delete
 * every file the process can reach. That happened to the owner's Mac on 2026-11-25.
 *
 * Also refuses the filesystem root and paths with fewer than [MIN_DEPTH] name components (e.g. a home folder), so a
 * bug that passes the wrong directory cannot wipe a whole disk. The JVM temp directory (`java.io.tmpdir`) and its
 * parents are always refused. A path strictly inside the temp directory is allowed even when it is short: on Linux the
 * temp directory is `/tmp`, so a fresh temp dir like `/tmp/atom-test-1` has only two components. Returns true when
 * nothing remains at this path.
 */
fun File.deleteTreeNoFollow(): Boolean {
    val start = toPath().toAbsolutePath().normalize()
    deletionRefusal(start)?.let { throw IllegalArgumentException("refusing to delete $start: $it") }
    if (!Files.exists(start, LinkOption.NOFOLLOW_LINKS)) return true
    // Without FileVisitOption.FOLLOW_LINKS, a link is reported to visitFile and deleted as a link, never entered.
    Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            runCatching { Files.deleteIfExists(file) }
            return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
            runCatching { Files.deleteIfExists(file) }
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            runCatching { Files.deleteIfExists(dir) }
            return FileVisitResult.CONTINUE
        }
    })
    return !Files.exists(start, LinkOption.NOFOLLOW_LINKS)
}

/** Why [deleteTreeNoFollow] would refuse [path] (absolute and normalized), or null when it may be deleted. */
internal fun deletionRefusal(path: Path, tempDir: Path? = jvmTempDir()): String? {
    if (path.nameCount == 0) return "filesystem root"
    if (tempDir != null && tempDir.startsWith(path)) return "temp directory or one of its parents"
    if (path.nameCount >= MIN_DEPTH) return null
    if (tempDir != null && tempDir.nameCount >= 1 && path.startsWith(tempDir)) return null
    return "path too short"
}

private fun jvmTempDir(): Path? =
    System.getProperty("java.io.tmpdir")?.takeIf { it.isNotBlank() }?.let { Path.of(it).toAbsolutePath().normalize() }

private const val MIN_DEPTH = 3
