package atom.linux

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Test cleanup that never follows symlinks.
 *
 * Kotlin's File.deleteRecursively() follows symlinks to directories. A test tree holding a link to "/"
 * (TarExtractorTest builds one on purpose) therefore deletes every file on the host the user can delete.
 * That happened on 2026-11-25. Use this instead of deleteRecursively() in every test here.
 *
 * As a second guard, it refuses any path outside the JVM's temp directory.
 */
internal fun File.deleteTestTree() {
    val tmpRoot = File(System.getProperty("java.io.tmpdir")).canonicalFile.toPath()
    val start = toPath().toAbsolutePath().normalize()
    // Canonicalise only the parent (/var -> /private/var on macOS); `this` itself may be a link and must not be resolved.
    val parent = start.parent ?: error("refusing to delete $start")
    val real = parent.toFile().canonicalFile.toPath().resolve(start.fileName)
    require(real.startsWith(tmpRoot) && real != tmpRoot) { "refusing to delete $real: not inside $tmpRoot" }
    if (!Files.exists(start, LinkOption.NOFOLLOW_LINKS)) return
    // Without FileVisitOption.FOLLOW_LINKS a symlink is reported to visitFile and deleted as a link, never entered.
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
}
