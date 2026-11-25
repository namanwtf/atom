package atom.context

import atom.context.sources.DownloadTask
import atom.context.sources.DownloadTasks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadTasksTest {
    private val output = """
Row: 0 _id=12, title=report, final.pdf, status=192, current_bytes=512, total_bytes=2048, lastmod=1700000000000, notificationpackage=com.example.browser, _data=/storage/emulated/0/Download/report, final.pdf, uri=https://example.com/r.pdf
Row: 1 _id=13, title=NULL, status=495, current_bytes=0, total_bytes=-1, lastmod=1690000000000, notificationpackage=com.example.mail, _data=NULL, uri=https://example.com/files/a.zip?x=1
No result found.
""".trimIndent()

    @Test
    fun parsesProviderRowsWithCommasInValues() {
        val rows = DownloadTasks.parseProviderRows(output)
        assertEquals(2, rows.size)
        val a = rows[0]
        assertEquals(12L, a.id)
        assertEquals("report, final.pdf", a.title)
        assertEquals("running", a.status)
        assertTrue(a.inProgress)
        assertEquals(2048L, a.totalBytes)
        assertEquals("com.example.browser", a.app)
        assertEquals("/storage/emulated/0/Download/report, final.pdf", a.path)
        val b = rows[1]
        assertEquals("a.zip", b.title)
        assertEquals("failed (connection error)", b.status)
        assertFalse(b.inProgress)
        assertNull(b.path)
    }

    @Test
    fun statuses() {
        assertEquals("completed", DownloadTasks.providerStatus(200))
        assertEquals("cancelled", DownloadTasks.providerStatus(490))
        assertEquals("failed (HTTP 404)", DownloadTasks.providerStatus(404))
        assertEquals("waiting for Wi-Fi", DownloadTasks.providerStatus(196))
        assertEquals("running", DownloadTasks.publicStatus(2, 0))
        assertEquals("waiting for network", DownloadTasks.publicStatus(4, 2))
        assertEquals("failed (not enough space)", DownloadTasks.publicStatus(16, 1006))
        assertEquals("failed", DownloadTasks.publicStatus(16, 0))
        assertEquals("completed", DownloadTasks.publicStatus(8, 0))
    }

    @Test
    fun progressWording() {
        val human = { b: Long -> "$b B" }
        val running = DownloadTask(1, "x", "running", 512, 2048, 0, null, null)
        assertEquals("25% of 2048 B", DownloadTasks.progress(running, human))
        assertEquals("2048 B", DownloadTasks.progress(running.copy(status = "completed"), human))
        assertEquals("10 B so far", DownloadTasks.progress(running.copy(currentBytes = 10, totalBytes = -1), human))
        assertTrue(DownloadTasks.queryCommand().startsWith("content query --uri content://downloads/all_downloads --projection _id:title:"))
    }
}
