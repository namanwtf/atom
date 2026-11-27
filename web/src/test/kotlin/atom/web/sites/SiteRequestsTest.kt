package atom.web.sites

import atom.core.io.deleteTreeNoFollow
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How Atom's WebViews answer a site's address: the files of that site, to that site only, nothing else. */
class SiteRequestsTest {
    private lateinit var tmp: File
    private lateinit var store: SiteStore
    private lateinit var root: File
    private lateinit var origin: String

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("atom-site-test").toFile()
        store = SiteStore(File(tmp, "sites").apply { mkdirs() })
        store.create("Site")
        root = store.dir("site")
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
        File(store.root, "outside").mkdirs()
        File(store.root, "outside/secret.txt").writeText("TOP SECRET")
        origin = SiteOrigin.origin("site", store.originTag("site"))
    }

    @AfterTest
    fun tearDown() {
        tmp.deleteTreeNoFollow()
    }

    /** A request from the site's own page (or its main-frame load when [main]). */
    private fun get(
        path: String,
        method: String = "GET",
        main: Boolean = false,
        page: String? = "$origin/",
        headers: Map<String, String> = emptyMap(),
        url: String = origin + path,
    ): SiteReply = SiteRequests.handle(store, url, method, headers, main, if (main) url else page)!!

    private fun SiteReply.type() = mime + (charset?.let { "; charset=$it" } ?: "")

    @Test
    fun originsAreFixedPerSiteAndNeverResolve() {
        val tag = store.originTag("site")
        assertEquals("https://site-$tag.invalid/", SiteOrigin.url("site", tag))
        assertEquals("site" to tag, SiteOrigin.parse("https://site-$tag.invalid/a/b?c#d"))
        assertEquals("water-tracker", SiteOrigin.siteOf("https://water-tracker-abc123.invalid/"))
        assertEquals("/a%20b/", SiteOrigin.rawPathOf("https://site-$tag.invalid/a%20b/?x=1#y"))
        assertEquals("/", SiteOrigin.rawPathOf("https://site-$tag.invalid"))
        // Each site is a single label under the reserved top level: its own registrable domain, so no cookie set
        // by one site (Domain= included) can reach another.
        assertEquals(1, SiteOrigin.url("site", tag).removePrefix("https://").removeSuffix("/").count { it == '.' })
        // Not ours: other hosts, look-alikes.
        for (u in listOf("https://example.com/", "https://invalid.example.com/", "https://evilinvalid/", "https://x.invalidx/", "about:blank", "", "x")) {
            assertFalse(SiteOrigin.isSiteUrl(u), u)
            assertNull(SiteRequests.handle(store, u, "GET", emptyMap(), true, u), u)
        }
        // Ours but malformed: answered (refused) here, so it never goes to the network.
        for (u in listOf(
            "http://site-$tag.invalid/", "https://site-$tag.invalid:8443/", "https://user@site-$tag.invalid/",
            "https://invalid/", "https://site.invalid/", "https://SITE-$tag.invalid./x", "https://a.site-$tag.invalid/",
            "https://atom.invalid/",
        )) {
            assertTrue(SiteOrigin.isSiteUrl(u), u)
            assertNull(SiteOrigin.parse(u), u)
            val r = SiteRequests.handle(store, u, "GET", emptyMap(), true, u)!!
            assertTrue(r.status >= 400, "$u -> ${r.status}")
            assertFalse("Hello" in r.text(), u)
        }
    }

    @Test
    fun noServiceWorkers() {
        // The registration's script request is refused, and so is every site request a worker makes.
        assertEquals(403, get("/sw.js", headers = mapOf("Service-Worker" to "script")).status)
        assertEquals(403, SiteRequests.serviceWorker("$origin/app.js")!!.status)
        assertEquals(403, SiteRequests.serviceWorker("https://anything.invalid/x")!!.status)
        assertNull(SiteRequests.serviceWorker("https://example.com/app.js"), "other addresses load as usual")
    }

    @Test
    fun linksToASiteOpenInAtom() {
        val tag = store.originTag("site")
        assertEquals("web/sites/preview/site", SiteLinks.routeFor("https://site-$tag.invalid/"))
        assertEquals("web/sites/preview/site", SiteLinks.routeFor("https://site-$tag.invalid"))
        assertTrue(SiteLinks.routeFor("https://site-$tag.invalid/sub/page.html").startsWith("web/browser/open/"))
        assertTrue(SiteLinks.routeFor("https://broken.invalid/").startsWith("web/browser/open/"))
    }

    @Test
    fun cookiePathsCoverEveryFolderAndFile() {
        assertEquals(
            listOf("/", "/index.html", "/css", "/css/", "/css/a.css", "/css/deep", "/css/deep/", "/css/deep/b.css"),
            SiteOrigin.cookiePaths(listOf("index.html", "css/a.css", "css/deep/b.css")),
        )
    }

    @Test
    fun servesFilesWithTheirTypesAndNoCaching() {
        val page = get("/", main = true)
        assertEquals(200, page.status)
        assertEquals("text/html; charset=utf-8", page.type())
        assertEquals("no-store", page.headers["Cache-Control"])
        assertEquals("nosniff", page.headers["X-Content-Type-Options"])
        assertEquals("no-referrer", page.headers["Referrer-Policy"])
        assertTrue("<h1>Hello</h1>" in page.text())
        // The file is served as written: nothing is injected any more.
        assertEquals(File(root, "index.html").readText(), page.text())

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
            assertEquals(type, r.type(), path)
        }
        assertEquals("h1 { color: red; }", get("/style.css").text())
        assertEquals("h1 { color: red; }", get("/style.css?v=2").text())
        assertEquals("", get("/style.css", method = "HEAD").text())
        assertEquals(200, get("/style.css", method = "HEAD").status)
    }

    @Test
    fun foldersSendTheBrowserToTheirSlashAndMissingFilesAre404() {
        val r = get("/sub", main = true)
        assertEquals(200, r.status)
        assertTrue("url=/sub/" in r.text(), r.text())
        assertEquals("<p>sub</p>", get("/sub/").text())
        val missing = get("/nope.html", main = true)
        assertEquals(404, missing.status)
        assertTrue("Nothing here yet: /nope.html" in missing.text())
        assertFalse("<script" in get("/<script>x.html").text(), "the missing path is escaped")
    }

    @Test
    fun refusesTraversalInEveryEncoding() {
        for (path in listOf(
            "/../outside/secret.txt",
            "/sub/../../outside/secret.txt",
            "/%2e%2e/outside/secret.txt",
            "/%2E%2E/outside/secret.txt",
            "/.%2e/outside/secret.txt",
            "/%2e%2e%2foutside%2fsecret.txt",
            "/..%2Foutside%2Fsecret.txt",
            "/sub/%2e%2e/%2e%2e/outside/secret.txt",
        )) {
            val r = get(path)
            assertEquals(403, r.status, path)
            assertFalse("TOP SECRET" in r.text(), path)
        }
        for (path in listOf("/..\\outside\\secret.txt", "/%5c..%5coutside%5csecret.txt", "/%00", "/%zz", "/%c0%ae%c0%ae/outside/secret.txt")) {
            val r = get(path)
            assertEquals(400, r.status, path)
            assertFalse("TOP SECRET" in r.text(), path)
        }
        // Double encoding decodes once: a literal "%2e%2e" name, which does not exist.
        val double = get("/%252e%252e/outside/secret.txt")
        assertEquals(404, double.status)
        assertFalse("TOP SECRET" in double.text())
    }

    @Test
    fun absolutePathsStayInsideTheSite() {
        val abs = File(store.root, "outside/secret.txt").absolutePath
        for (path in listOf("/$abs", "//${abs.trimStart('/')}", "/%2F${abs.trimStart('/')}")) {
            val r = get(path)
            assertTrue(r.status == 404 || r.status == 403, "$path -> ${r.status}")
            assertFalse("TOP SECRET" in r.text(), path)
        }
        assertEquals(404, get("/etc/passwd").status)
    }

    @Test
    fun hiddenFilesAndSymlinksAreNotServed() {
        assertEquals(404, get("/.secret").status)
        assertEquals(404, get("/.origin").status, "the origin tag file is hidden")
        Files.createSymbolicLink(File(root, "link.txt").toPath(), File(store.root, "outside/secret.txt").toPath())
        Files.createSymbolicLink(File(root, "linkdir").toPath(), File(store.root, "outside").toPath())
        Files.createSymbolicLink(File(root, "sub/inner").toPath(), File(root, "style.css").toPath())
        for (path in listOf("/link.txt", "/linkdir/secret.txt", "/linkdir/", "/sub/inner")) {
            val r = get(path)
            assertEquals(403, r.status, path)
            assertFalse("TOP SECRET" in r.text(), path)
        }
    }

    @Test
    fun getOrHeadOnly() {
        for (m in listOf("POST", "PUT", "DELETE", "OPTIONS")) {
            val r = get("/", method = m)
            assertEquals(405, r.status, m)
            assertEquals("GET, HEAD", r.headers["Allow"])
        }
    }

    @Test
    fun onlyTheSiteItselfGetsItsFiles() {
        store.create("Other")
        val other = SiteOrigin.url("other", store.originTag("other"))
        // A page of another site, of the web, or none at all cannot load this site's files.
        for (page in listOf(other, "https://evil.example/", "about:blank", null)) {
            for (path in listOf("/", "/app.js", "/style.css")) {
                val r = get(path, page = page)
                assertEquals(403, r.status, "$page $path")
                assertFalse("Hello" in r.text() || "console" in r.text(), "$page $path")
            }
        }
        // A foreign Origin or a cross-site fetch is refused even from the site's own page.
        assertEquals(403, get("/app.js", headers = mapOf("Origin" to "https://evil.example")).status)
        assertEquals(403, get("/app.js", headers = mapOf("origin" to "null")).status)
        assertEquals(403, get("/app.js", headers = mapOf("Sec-Fetch-Site" to "cross-site")).status)
        assertEquals(403, get("/app.js", headers = mapOf("Sec-Fetch-Site" to "same-site")).status)
        assertEquals(200, get("/app.js", headers = mapOf("Origin" to origin, "Sec-Fetch-Site" to "same-origin")).status)
        // Opening the site itself (a top-level load, typed or followed) is fine: it then runs in its own origin.
        assertEquals(200, get("/", main = true).status)
    }

    @Test
    fun aDeletedOrRemadeSiteIsNotServedAtItsOldAddress() {
        val old = "$origin/"
        assertTrue(store.delete("site"))
        assertEquals(404, SiteRequests.handle(store, old, "GET", emptyMap(), true, old)!!.status)
        store.create("Site")
        val again = SiteRequests.handle(store, old, "GET", emptyMap(), true, old)!!
        assertEquals(404, again.status, "a site made again under the same name has a new origin")
        val now = SiteOrigin.url("site", store.originTag("site"))
        assertEquals(200, SiteRequests.handle(store, now, "GET", emptyMap(), true, now)!!.status)
    }

    @Test
    fun decoding() {
        assertEquals("/a b/ü", SiteRequests.percentDecode("/a%20b/%C3%BC"))
        assertEquals("/a+b", SiteRequests.percentDecode("/a+b"))
        assertEquals(null, SiteRequests.percentDecode("/%"))
        assertEquals(null, SiteRequests.percentDecode("/%C3"))
    }
}
