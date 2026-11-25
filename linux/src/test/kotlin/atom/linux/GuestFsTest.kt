package atom.linux

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GuestFsTest {
    private val tmp: File = Files.createTempDirectory("atom-guestfs").toFile()
    private val rootfs = File(tmp, "rootfs").apply { mkdirs() }
    private val workspace = File(tmp, "ws").apply { mkdirs() }
    private val shared = File(tmp, "shared").apply { mkdirs() }

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    private fun fs() = GuestFs(rootfs, mapOf("/workspace" to workspace, "/workspace/mounts/dl" to shared))

    @Test
    fun mapsMountsAndResolvesLinksInsideTheGuest() {
        File(rootfs, "usr/bin").mkdirs()
        File(rootfs, "usr/bin/tool").writeText("#!/bin/sh\necho hi\n")
        Files.createSymbolicLink(File(rootfs, "bin").toPath(), File("usr/bin").toPath())
        Files.createSymbolicLink(File(rootfs, "abs").toPath(), File("/usr/bin/tool").toPath()) // absolute: guest root
        File(shared, "note.txt").writeText("shared")
        val fs = fs()
        assertEquals(File(workspace, "a/b"), fs.hostFor("/workspace/a/b"))
        assertEquals(File(shared, "x"), fs.hostFor("/workspace/mounts/dl/x"))
        assertEquals("/usr/bin/tool", fs.resolve("/bin/tool"))
        assertEquals("/usr/bin/tool", fs.resolve("/abs"))
        assertEquals("/usr", fs.resolve("/bin/.."))
        assertEquals("/", fs.resolve("/bin/../.."))
        val text = fs.preview("/abs") as GuestFs.Preview.Text
        assertTrue(text.text.contains("echo hi"))
        val root = fs.list("/")!!.map { it.name }
        assertTrue("workspace" in root) // mount point listed even though the rootfs has no such folder
        val ws = fs.list("/workspace")!!.map { it.name }
        assertTrue("mounts" in ws)
        assertEquals(listOf("note.txt"), fs.list("/workspace/mounts/dl")!!.map { it.name })
    }

    @Test
    fun linkLoopsAreDetected() {
        Files.createSymbolicLink(File(rootfs, "a").toPath(), File("b").toPath())
        Files.createSymbolicLink(File(rootfs, "b").toPath(), File("a").toPath())
        assertEquals(null, fs().resolve("/a"))
    }

    @Test
    fun previewsAreCappedAndBinaryIsDetected() {
        File(rootfs, "big.txt").writeText("x".repeat(100_000))
        File(rootfs, "bin.dat").writeBytes(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 0, 1, 2))
        val fs = fs()
        val big = fs.preview("/big.txt") as GuestFs.Preview.Text
        assertTrue(big.truncated)
        assertEquals(GuestFs.PREVIEW_BYTES, big.text.length)
        assertTrue(fs.preview("/bin.dat") is GuestFs.Preview.Binary)
        assertTrue(GuestFs.looksBinary(ByteArray(100) { 1 }))
        assertTrue(!GuestFs.looksBinary("héllo\tworld\n\u001b[31mred".toByteArray()))
    }
}
