package atom.linux.install

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TarExtractorTest {
    private val tmp: File = Files.createTempDirectory("atom-tar").toFile()

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    /** Minimal ustar writer for test archives. */
    private class TarWriter {
        val out = ByteArrayOutputStream()

        fun entry(name: String, type: Char, mode: Int, data: ByteArray = ByteArray(0), link: String = "") {
            val h = ByteArray(512)
            fun put(off: Int, s: String) = s.toByteArray().copyInto(h, off)
            fun octal(off: Int, len: Int, v: Long) = put(off, v.toString(8).padStart(len - 1, '0'))
            put(0, name)
            octal(100, 8, mode.toLong())
            octal(108, 8, 0)
            octal(116, 8, 0)
            octal(124, 12, data.size.toLong())
            octal(136, 12, 0)
            h[156] = type.code.toByte()
            put(157, link)
            put(257, "ustar\u000000")
            for (i in 148 until 156) h[i] = ' '.code.toByte()
            val sum = h.sumOf { it.toInt() and 0xFF }
            put(148, sum.toString(8).padStart(6, '0') + "\u0000 ")
            out.write(h)
            out.write(data)
            val pad = (512 - data.size % 512) % 512
            out.write(ByteArray(pad))
        }

        fun pax(records: Map<String, String>) {
            val body = records.entries.joinToString("") { (k, v) ->
                val rec = " $k=$v\n"
                var len = rec.length + 1
                while ((len.toString() + rec).length != len) len = (len.toString() + rec).length
                len.toString() + rec
            }.toByteArray()
            entry("PaxHeader", 'x', "644".toInt(8), body)
        }

        fun finish(): ByteArray {
            out.write(ByteArray(1024))
            return out.toByteArray()
        }
    }

    @Test
    fun extractsFilesDirsSymlinksAndHardlinks() {
        val w = TarWriter()
        w.entry("./", '5', "755".toInt(8))
        w.entry("./bin/", '5', "755".toInt(8))
        w.entry("./bin/busybox", '0', "755".toInt(8), "#!/bin/true\n".toByteArray())
        w.entry("./bin/sh", '2', "777".toInt(8), link = "busybox")
        w.entry("./bin/ash", '1', "755".toInt(8), link = "./bin/busybox")
        w.entry("./etc/hostname", '0', "644".toInt(8), "alpine\n".toByteArray()) // parent dir implied
        w.entry("./usr/lib/os-release", '0', "644".toInt(8), "ID=alpine\n".toByteArray())
        w.entry("./etc/os-release", '2', "777".toInt(8), link = "../usr/lib/os-release")
        w.entry("./dev/null", '3', "666".toInt(8))
        // gzip round trip, like the real minirootfs
        val gz = ByteArrayOutputStream().also { GZIPOutputStream(it).use { z -> z.write(w.finish()) } }.toByteArray()

        val root = File(tmp, "rootfs")
        val stats = TarExtractor(root).extract(GZIPInputStream(ByteArrayInputStream(gz)))

        assertEquals("#!/bin/true\n", File(root, "bin/busybox").readText())
        assertTrue(Files.isSymbolicLink(File(root, "bin/sh").toPath()))
        assertEquals("busybox", Files.readSymbolicLink(File(root, "bin/sh").toPath()).toString())
        assertEquals("#!/bin/true\n", File(root, "bin/ash").readText())
        assertEquals("alpine\n", File(root, "etc/hostname").readText())
        assertEquals("ID=alpine\n", File(root, "etc/os-release").readText()) // relative link resolves
        assertFalse(File(root, "dev/null").exists())
        val perms = Files.getPosixFilePermissions(File(root, "bin/busybox").toPath())
        assertTrue(PosixFilePermission.OWNER_EXECUTE in perms && PosixFilePermission.OTHERS_EXECUTE in perms)
        assertEquals(3, stats.files)
        assertEquals(2, stats.symlinks)
        assertEquals(1, stats.hardlinks + stats.hardlinkCopies)
        assertEquals(1, stats.skipped) // the device node
    }

    @Test
    fun refusesToEscapeTheRoot() {
        val w = TarWriter()
        w.entry("../evil", '0', "644".toInt(8), "x".toByteArray())
        w.entry("/abs/file", '0', "644".toInt(8), "inside".toByteArray())
        w.entry("link", '2', "777".toInt(8), link = "/")
        w.entry("link/escaped", '0', "644".toInt(8), "nope".toByteArray()) // would write through the symlink
        w.entry("hard", '1', "644".toInt(8), link = "../../etc/passwd")
        val root = File(tmp, "r2")
        val stats = TarExtractor(root).extract(ByteArrayInputStream(w.finish()))

        assertFalse(File(tmp, "evil").exists())
        assertEquals("inside", File(root, "abs/file").readText()) // absolute paths land inside the root
        assertFalse(File("/escaped").exists())
        assertFalse(File(root, "hard").exists())
        assertEquals(3, stats.skipped)
    }

    @Test
    fun paxLongNamesAndRestrictiveDirModes() {
        val long = "d/" + "x".repeat(150) + "/file.txt"
        val w = TarWriter()
        w.entry("ro/", '5', "555".toInt(8))
        w.entry("ro/inner", '0', "444".toInt(8), "ok".toByteArray())
        w.pax(mapOf("path" to long))
        w.entry("placeholder", '0', "644".toInt(8), "long".toByteArray())
        val root = File(tmp, "r3")
        TarExtractor(root).extract(ByteArrayInputStream(w.finish()))

        assertEquals("long", File(root, long).readText())
        assertEquals("ok", File(root, "ro/inner").readText())
        // Owners keep write access so the tree can be managed (and deleted) without root.
        assertTrue(PosixFilePermission.OWNER_WRITE in Files.getPosixFilePermissions(File(root, "ro").toPath()))
    }

    @Test
    fun parsesNumbersAndPax() {
        val h = ByteArray(512)
        "0000644\u0000".toByteArray().copyInto(h, 100)
        assertEquals("644".toLong(8), TarExtractor.parseNumber(h, 100, 8))
        h[124] = 0x80.toByte(); h[135] = 0x10
        assertEquals(16L, TarExtractor.parseNumber(h, 124, 12))
        assertEquals(mapOf("path" to "a b", "size" to "5"), TarExtractor.parsePax("12 path=a b\n10 size=5\n"))
    }
}
