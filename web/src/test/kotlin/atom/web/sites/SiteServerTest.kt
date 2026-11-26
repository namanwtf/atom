package atom.web.sites

import atom.core.io.deleteTreeNoFollow
import java.io.File
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SiteServerTest {
    private lateinit var tmp: File
    private lateinit var root: File
    private lateinit var server: SiteServer

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("atom-site-test").toFile()
        root = File(tmp, "site").apply { mkdirs() }
        File(root, "index.html").writeText("<!doctype html><html><head><title>Hi</title></head><body><h1>Hello</h1></body></html>")
        File(root, "style.css").writeText("h1 { color: red; }")
        File(root, "app.js").writeText("console.log(1)")
        File(root, "logo.svg").writeText("<svg xmlns=\"http://www.w3.org/2000/svg\"/>")
        File(root, "pic.png").writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47))
        File(root, "data.bin").writeBytes(byteArrayOf(1, 2, 3))
        File(root, "sub").mkdirs()
        File(root, "sub/index.html").writeText("<p>sub</p>")
        File(root, ".secret").writeText("hidden")
        // Next to the site, never to be served.
        File(tmp, "outside").mkdirs()
        File(tmp, "outside/secret.txt").writeText("TOP SECRET")
        server = SiteServer(root, acceptTimeoutMs = 200).start()
    }

    @AfterTest
    fun tearDown() {
        server.stop()
        tmp.deleteTreeNoFollow()
    }

    private class Reply(val status: Int, val headers: Map<String, String>, val body: String)

    private fun get(path: String): Reply {
        val c = URL("http://127.0.0.1:${server.port}$path").openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 3_000
        c.readTimeout = 30_000
        val status = c.responseCode
        val body = (if (status < 400) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        val headers = c.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.joinToString(",") }
        c.disconnect()
        return Reply(status, headers, body)
    }

    /** Sends the request line exactly as given (HttpURLConnection would normalize some of these). */
    private fun raw(target: String, host: String = "127.0.0.1:${server.port}", method: String = "GET"): Reply {
        Socket("127.0.0.1", server.port).use { s ->
            s.soTimeout = 5_000
            s.getOutputStream().write("$method $target HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            s.getOutputStream().flush()
            val text = s.getInputStream().readBytes().toString(Charsets.UTF_8)
            val head = text.substringBefore("\r\n\r\n")
            val status = head.lineSequence().first().split(' ')[1].toInt()
            val headers = head.lineSequence().drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            return Reply(status, headers, text.substringAfter("\r\n\r\n", ""))
        }
    }

    @Test
    fun servesFilesWithTheirTypesAndNoCaching() {
        val page = get("/")
        assertEquals(200, page.status)
        assertEquals("text/html; charset=utf-8", page.headers["content-type"])
        assertEquals("no-store", page.headers["cache-control"])
        assertEquals("nosniff", page.headers["x-content-type-options"])
        assertTrue("<h1>Hello</h1>" in page.body)
        assertEquals(page.body.toByteArray().size.toString(), page.headers["content-length"])

        val types = mapOf(
            "/style.css" to "text/css; charset=utf-8",
            "/app.js" to "text/javascript; charset=utf-8",
            "/logo.svg" to "image/svg+xml",
            "/pic.png" to "image/png",
            "/data.bin" to "application/octet-stream",
            "/index.html" to "text/html; charset=utf-8",
        )
        for ((path, type) in types) {
            val r = get(path)
            assertEquals(200, r.status, path)
            assertEquals(type, r.headers["content-type"], path)
        }
        assertEquals("h1 { color: red; }", get("/style.css").body)
        assertEquals("h1 { color: red; }", get("/style.css?v=2").body)
        assertTrue(raw("/style.css", method = "HEAD").body.isEmpty())
    }

    @Test
    fun htmlGetsTheReloadScriptBeforeTheBodyEnds() {
        val body = get("/").body
        assertTrue(SiteServer.RELOAD_SCRIPT in body, body)
        assertTrue(body.indexOf(SiteServer.RELOAD_SCRIPT) < body.lowercase().lastIndexOf("</body>"))
        assertFalse(SiteServer.RELOAD_SCRIPT in get("/style.css").body)
        // A fragment without </body> gets it at the end; the file on disk is never changed.
        assertTrue(get("/sub/").body.endsWith(SiteServer.RELOAD_SCRIPT))
        assertEquals("<p>sub</p>", File(root, "sub/index.html").readText())
        assertEquals("a<b></BODY>".replace("</BODY>", SiteServer.RELOAD_SCRIPT + "</BODY>"), String(SiteServer.inject("a<b></BODY>".toByteArray())))
    }

    @Test
    fun foldersRedirectToTheirSlashAndMissingFilesAre404() {
        val r = get("/sub")
        assertEquals(301, r.status)
        assertEquals("/sub/", r.headers["location"])
        assertEquals(404, get("/nope.html").status)
        assertTrue(SiteServer.RELOAD_SCRIPT in get("/nope.html").body, "a missing page still reloads once the file is written")
    }

    @Test
    fun refusesTraversalInEveryEncoding() {
        for (target in listOf(
            "/../outside/secret.txt",
            "/sub/../../outside/secret.txt",
            "/%2e%2e/outside/secret.txt",
            "/%2E%2E/outside/secret.txt",
            "/.%2e/outside/secret.txt",
            "/%2e%2e%2foutside%2fsecret.txt",
            "/..%2Foutside%2Fsecret.txt",
            "/sub/%2e%2e/%2e%2e/outside/secret.txt",
            "http://127.0.0.1/../outside/secret.txt",
        )) {
            val r = raw(target)
            assertEquals(403, r.status, target)
            assertFalse("TOP SECRET" in r.body, target)
        }
        for (target in listOf("/..\\outside\\secret.txt", "/%5c..%5coutside%5csecret.txt", "/%00", "/%zz", "/%c0%ae%c0%ae/outside/secret.txt", "outside/secret.txt")) {
            val r = raw(target)
            assertEquals(400, r.status, target)
            assertFalse("TOP SECRET" in r.body, target)
        }
        // Double encoding decodes once: a literal "%2e%2e" name, which does not exist.
        val double = raw("/%252e%252e/outside/secret.txt")
        assertEquals(404, double.status)
        assertFalse("TOP SECRET" in double.body)
    }

    @Test
    fun absolutePathsStayInsideTheSite() {
        val abs = File(tmp, "outside/secret.txt").absolutePath
        for (target in listOf(abs, "/$abs", "//${abs.trimStart('/')}", "/%2F${abs.trimStart('/')}")) {
            val r = raw(if (target.startsWith("/")) target else "/$target")
            assertTrue(r.status == 404 || r.status == 403, "$target -> ${r.status}")
            assertFalse("TOP SECRET" in r.body, target)
        }
        assertEquals(404, raw("/etc/passwd").status)
    }

    @Test
    fun hiddenFilesAndSymlinksAreNotServed() {
        assertEquals(404, raw("/.secret").status)
        Files.createSymbolicLink(File(root, "link.txt").toPath(), File(tmp, "outside/secret.txt").toPath())
        Files.createSymbolicLink(File(root, "linkdir").toPath(), File(tmp, "outside").toPath())
        Files.createSymbolicLink(File(root, "sub/inner").toPath(), File(root, "style.css").toPath())
        for (target in listOf("/link.txt", "/linkdir/secret.txt", "/linkdir/", "/sub/inner")) {
            val r = raw(target)
            assertEquals(403, r.status, target)
            assertFalse("TOP SECRET" in r.body, target)
        }
    }

    @Test
    fun onlyItsOwnHostAndGetOrHead() {
        assertEquals(403, raw("/", host = "evil.example").status)
        assertEquals(403, raw("/", host = "127.0.0.1:1").status)
        assertEquals(200, raw("/", host = "localhost:${server.port}").status)
        val post = raw("/", method = "POST")
        assertEquals(405, post.status)
        assertEquals("GET, HEAD", post.headers["allow"])
    }

    @Test
    fun reloadEndpointAnswersAndWaitsForAChange() {
        val first = get(SiteServer.RELOAD_PATH)
        assertEquals(200, first.status)
        assertEquals("application/json; charset=utf-8", first.headers["content-type"])
        val v = Regex("\"version\":(\\d+)").find(first.body)!!.groupValues[1].toLong()
        assertEquals(server.version, v)

        // An old version answers at once.
        assertTrue("\"version\":$v" in get("${SiteServer.RELOAD_PATH}?since=${v - 1}").body)

        // The current one waits until the site changes.
        val t = Thread {
            Thread.sleep(300)
            server.changed()
        }
        val start = System.currentTimeMillis()
        t.start()
        val waited = get("${SiteServer.RELOAD_PATH}?since=$v")
        val took = System.currentTimeMillis() - start
        t.join()
        assertTrue("\"version\":${v + 1}" in waited.body, waited.body)
        assertTrue(took in 250..10_000, "long poll took $took ms")
    }

    @Test
    fun stopsWhenIdleAndKeepsRunningWhileHeld() {
        server.stop()
        val stopped = java.util.concurrent.CountDownLatch(1)
        val idle = SiteServer(root, idleTimeoutMs = 300, acceptTimeoutMs = 100, onStopped = { stopped.countDown() }).start()
        try {
            idle.acquire()
            Thread.sleep(800)
            assertTrue(idle.isRunning, "a held server keeps running")
            idle.release(stopIfIdleForMs = 60_000)
            assertTrue(idle.isRunning, "released right after use: it waits for the idle time")
            assertTrue(stopped.await(5, java.util.concurrent.TimeUnit.SECONDS), "an idle server stops itself")
            assertFalse(idle.isRunning)
        } finally {
            idle.stop()
        }
        // Releasing after a long quiet stops it at once.
        val quiet = SiteServer(root, idleTimeoutMs = 60_000, acceptTimeoutMs = 100).start()
        quiet.acquire()
        quiet.release(stopIfIdleForMs = -1)
        assertFalse(quiet.isRunning)
    }

    @Test
    fun keepsItsPortWhenRestartedAndListensOnLoopbackOnly() {
        val port = server.port
        server.stop()
        val again = SiteServer(root, preferredPort = port).start()
        try {
            assertEquals(port, again.port)
            assertEquals("http://127.0.0.1:$port/", again.url)
            // A second server cannot take the same port; it falls back to another one.
            val other = SiteServer(root, preferredPort = port).start()
            try {
                assertTrue(other.port != port && other.port > 0)
            } finally {
                other.stop()
            }
        } finally {
            again.stop()
        }
    }

    @Test
    fun targetsAndDecoding() {
        assertEquals("/a" to "b=1", SiteServer.splitTarget("/a?b=1#x"))
        assertEquals("/x/y" to "", SiteServer.splitTarget("http://127.0.0.1:9/x/y"))
        assertEquals(null, SiteServer.splitTarget("x"))
        assertEquals("/a b/ü", SiteServer.percentDecode("/a%20b/%C3%BC"))
        assertEquals("/a+b", SiteServer.percentDecode("/a+b"))
        assertEquals(null, SiteServer.percentDecode("/%"))
        assertEquals(null, SiteServer.percentDecode("/%C3"))
    }
}
