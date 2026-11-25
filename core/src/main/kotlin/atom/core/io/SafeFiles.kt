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
 * bug that passes the wrong directory cannot wipe a whole disk. Returns true when nothing remains at this path.
 */
fun File.deleteTreeNoFollow(): Boolean {
    val start = toPath().toAbsolutePath().normalize()
    require(start.nameCount >= MIN_DEPTH) { "refusing to delete $start: path too short" }
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

private const val MIN_DEPTH = 3
