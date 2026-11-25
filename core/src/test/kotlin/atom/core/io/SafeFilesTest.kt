package atom.core.io

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SafeFilesTest {

    // The guard is checked on plain paths: no test ever points a real delete at a path it expects to be refused.

    @Test
    fun refusesRootAndShortPaths() {
        val tmp = Path.of("/tmp")
        assertEquals("filesystem root", deletionRefusal(Path.of("/"), tmp))
        assertEquals("path too short", deletionRefusal(Path.of("/home"), tmp))
        assertEquals("path too short", deletionRefusal(Path.of("/home/runner"), tmp))
        assertEquals("path too short", deletionRefusal(Path.of("/Users/n"), tmp))
        assertNull(deletionRefusal(Path.of("/home/runner/work"), tmp))
    }

    @Test
    fun refusesTheTempDirAndItsParentsButAllowsWhatIsInside() {
        val linuxTmp = Path.of("/tmp")
        assertNotNull(deletionRefusal(linuxTmp, linuxTmp))
        assertNull(deletionRefusal(Path.of("/tmp/atom-test-1"), linuxTmp))

        val macTmp = Path.of("/var/folders/ab/cdef/T")
        assertNotNull(deletionRefusal(macTmp, macTmp))
        assertNotNull(deletionRefusal(Path.of("/var/folders/ab/cdef"), macTmp))
        assertNotNull(deletionRefusal(Path.of("/var/folders"), macTmp))
        assertNull(deletionRefusal(Path.of("/var/folders/ab/cdef/T/atom-test-1"), macTmp))

        assertEquals("path too short", deletionRefusal(Path.of("/tmpfoo"), linuxTmp))
        assertEquals("path too short", deletionRefusal(Path.of("/tmp/x"), null))
    }

    @Test
    fun deletesATreeInsideTheTempDir() {
        val dir = Files.createTempDirectory("atom-safefiles").toFile()
        File(dir, "a/b").mkdirs()
        File(dir, "a/b/c.txt").writeText("x")
        assertTrue(dir.deleteTreeNoFollow())
        assertFalse(dir.exists())
    }

    @Test
    fun removesLinksWithoutTouchingTheirTargets() {
        val outside = Files.createTempDirectory("atom-safefiles-target").toFile()
        val keep = File(outside, "keep.txt").apply { writeText("keep") }
        val dir = Files.createTempDirectory("atom-safefiles-links").toFile()
        try {
            Files.createSymbolicLink(File(dir, "to-dir").toPath(), outside.toPath())
            Files.createSymbolicLink(File(dir, "to-file").toPath(), keep.toPath())
            assertTrue(dir.deleteTreeNoFollow())
            assertFalse(dir.exists())
            assertTrue(keep.exists(), "link target was deleted")
        } finally {
            keep.delete()
            outside.delete()
        }
    }

    @Test
    fun missingPathCountsAsDeleted() {
        val missing = File(System.getProperty("java.io.tmpdir"), "atom-safefiles-missing-" + System.nanoTime())
        assertTrue(missing.deleteTreeNoFollow())
    }
}
