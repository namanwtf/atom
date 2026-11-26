package atom.web.sites

import atom.core.io.deleteTreeNoFollow
import atom.core.tool.Tool
import atom.core.tool.ToolArgumentException
import atom.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SiteToolsTest {
    private lateinit var tmp: File
    private lateinit var backend: FakeBackend
    private lateinit var tools: Map<String, Tool>

    private class FakeBackend(override val store: SiteStore) : SiteBackend {
        val currents = HashMap<String, String>()
        val changes = ArrayList<String>()
        val previews = ArrayList<String>()

        override fun conversationOf(taskId: String) = "conv-$taskId"
        override fun current(conversation: String) = currents[conversation]?.takeIf { store.exists(it) }
        override fun setCurrent(conversation: String, id: String) {
            currents[conversation] = id
        }
        override fun changed(id: String) {
            changes += id
        }
        override suspend fun preview(id: String): Pair<String, Boolean> {
            previews += id
            return "http://127.0.0.1:4321/" to true
        }
        override fun previewing(id: String) = id in previews
    }

    private class Ctx(override val taskId: String = "t1") : ToolContext {
        override suspend fun confirm(action: String) = true
    }

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("atom-sites-test").toFile()
        backend = FakeBackend(SiteStore(File(tmp, "sites").apply { mkdirs() }))
        tools = SiteTools.create(backend).associateBy { it.spec.name }
    }

    @AfterTest
    fun tearDown() {
        tmp.deleteTreeNoFollow()
    }

    private fun args(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })

    private suspend fun call(name: String, vararg pairs: Pair<String, String>, ctx: ToolContext = Ctx()) =
        tools.getValue(name).run(args(*pairs), ctx)

    @Test
    fun theToolSetMatchesItsNames() {
        assertEquals(SiteToolNames.ALL, SiteTools.create(backend).map { it.spec.name })
        assertTrue(SiteToolNames.ALL.all { it.startsWith("site_") })
    }

    @Test
    fun idsAreShortSlugs() {
        assertEquals("water-tracker", SitePaths.idOf("Water Tracker!"))
        assertEquals("cafe-creme", SitePaths.idOf("  Café   Crème "))
        assertEquals("a".repeat(40), SitePaths.idOf("a".repeat(80)))
        assertNull(SitePaths.idOf("!!!"))
        assertNull(SitePaths.idOf("日本"))
        assertTrue(SitePaths.isId("my-site-2"))
        assertFalse(SitePaths.isId("../x"))
        assertFalse(SitePaths.isId("-x"))
        assertFalse(SitePaths.isId("X"))
    }

    @Test
    fun pathsStayInsideTheSite() {
        assertEquals("index.html", SitePaths.clean("index.html"))
        assertEquals("index.html", SitePaths.clean("/index.html"))
        assertEquals("css/style.css", SitePaths.clean("./css/style.css"))
        for (bad in listOf(
            "", "  ", "../x.html", "a/../../x", "a/./b", "..", "/..", "//etc/passwd", "a//b", ".env", "css/.hidden",
            "a\\b", "~/x", "file:///etc/passwd", "C:/x", "a%2e%2e", "a?b", "x#y", "nul\u0000.html", "a/" + "b/".repeat(9) + "c",
            "x".repeat(201),
        )) {
            assertFailsWith<ToolArgumentException>(bad) { SitePaths.clean(bad) }
        }
    }

    @Test
    fun mimeTypes() {
        assertEquals("text/html; charset=utf-8", SitePaths.mimeOf("INDEX.HTM"))
        assertEquals("text/javascript; charset=utf-8", SitePaths.mimeOf("a.mjs"))
        assertEquals("image/svg+xml", SitePaths.mimeOf("a.svg"))
        assertEquals("font/woff2", SitePaths.mimeOf("f.woff2"))
        assertEquals("application/wasm", SitePaths.mimeOf("m.wasm"))
        assertEquals("application/octet-stream", SitePaths.mimeOf("noext"))
    }

    @Test
    fun createWriteReadListPreviewAndDelete() = runTest {
        val created = call(SiteToolNames.CREATE, "name" to "Water tracker")
        assertFalse(created.isError, created.text)
        assertTrue("water-tracker" in created.text)
        assertEquals("water-tracker", backend.currents["conv-t1"])
        assertTrue(backend.store.read("water-tracker", "index.html").contains("<title>Water tracker</title>"))

        // The current site is used when none is named.
        val wrote = call(SiteToolNames.WRITE, "path" to "index.html", "content" to "<title>Water</title><h1>Hello</h1>")
        assertFalse(wrote.isError, wrote.text)
        assertTrue(call(SiteToolNames.WRITE, "path" to "/css/style.css", "content" to "h1{}").text.startsWith("Wrote css/style.css"))
        assertEquals("<title>Water</title><h1>Hello</h1>", call(SiteToolNames.READ, "path" to "index.html").text.substringAfter('\n'))
        assertTrue(backend.changes.count { it == "water-tracker" } >= 3)

        val list = call(SiteToolNames.LIST).text
        assertTrue("css/style.css" in list && "index.html" in list, list)

        val preview = call(SiteToolNames.PREVIEW)
        assertFalse(preview.isError, preview.text)
        assertTrue("http://127.0.0.1:4321/" in preview.text)
        assertEquals("water-tracker", SiteCards.siteOf(preview.text))
        assertEquals("web/sites/preview/water-tracker", SiteCards.previewRoute("water-tracker"))
        assertTrue("The preview reloads" in call(SiteToolNames.WRITE, "path" to "app.js", "content" to "1").text)

        assertFalse(call(SiteToolNames.DELETE_FILE, "path" to "css/style.css").isError)
        assertFalse(File(backend.store.dir("water-tracker"), "css").exists(), "an emptied folder goes too")
        assertTrue(call(SiteToolNames.DELETE_FILE, "path" to "css/style.css").isError)

        // Creating it again reopens it.
        assertTrue("already exists" in call(SiteToolNames.CREATE, "name" to "water tracker").text)
    }

    @Test
    fun argumentsAreValidated() = runTest {
        assertTrue(call(SiteToolNames.CREATE).isError)
        assertTrue(call(SiteToolNames.CREATE, "name" to "!!!").isError)
        assertTrue(call(SiteToolNames.CREATE, "name" to "x".repeat(61)).isError)
        // No site yet.
        val none = call(SiteToolNames.WRITE, "path" to "index.html", "content" to "x")
        assertTrue(none.isError && "site_create" in none.text, none.text)

        call(SiteToolNames.CREATE, "name" to "One")
        call(SiteToolNames.CREATE, "name" to "Two", ctx = Ctx("t2"))
        for (bad in listOf("../escape.html", "/../../x", "a\\b.html", ".hidden", "~/x", "file:///etc/passwd")) {
            val r = call(SiteToolNames.WRITE, "path" to bad, "content" to "x")
            assertTrue(r.isError, bad)
        }
        assertTrue(call(SiteToolNames.WRITE, "path" to "index.html").isError, "content is required")
        assertTrue(call(SiteToolNames.READ, "path" to "missing.html").isError)
        assertTrue(call(SiteToolNames.WRITE, "path" to "a.html", "content" to "x", "site" to "nope").isError)
        val big = call(SiteToolNames.WRITE, "path" to "big.html", "content" to "x".repeat(SiteStore.MAX_FILE + 1))
        assertTrue(big.isError && "at most" in big.text, big.text)

        // Two sites and a new conversation: it must name one.
        val which = call(SiteToolNames.WRITE, "path" to "a.html", "content" to "x", ctx = Ctx("t3"))
        assertTrue(which.isError && "one" in which.text && "two" in which.text, which.text)
        assertFalse(call(SiteToolNames.WRITE, "path" to "a.html", "content" to "x", "site" to "Two", ctx = Ctx("t3")).isError)
        assertEquals("two", backend.currents["conv-t3"])
        assertTrue(File(backend.store.dir("two"), "a.html").exists())
        assertFalse(File(backend.store.dir("one"), "a.html").exists())
    }

    @Test
    fun writesNeverFollowSymlinks() = runTest {
        call(SiteToolNames.CREATE, "name" to "Links")
        val outside = File(tmp, "outside").apply { mkdirs() }
        val victim = File(outside, "victim.txt").apply { writeText("keep") }
        val site = backend.store.dir("links")
        Files.createSymbolicLink(File(site, "out").toPath(), outside.toPath())
        Files.createSymbolicLink(File(site, "v.txt").toPath(), victim.toPath())
        assertTrue(call(SiteToolNames.WRITE, "path" to "out/victim.txt", "content" to "pwned").isError)
        assertTrue(call(SiteToolNames.WRITE, "path" to "v.txt", "content" to "pwned").isError)
        assertTrue(call(SiteToolNames.READ, "path" to "v.txt").isError)
        assertTrue(call(SiteToolNames.DELETE_FILE, "path" to "v.txt").isError)
        assertEquals("keep", victim.readText())
        // Listing and packing leave links out.
        assertEquals(listOf("index.html"), backend.store.files("links").map { it.path })
        val zip = backend.store.zip("links", File(tmp, "links.zip"))
        ZipFile(zip).use { z -> assertEquals(listOf("links/index.html"), z.entries().toList().map { it.name }) }
        // Deleting the site removes the links, not what they point at.
        assertTrue(backend.store.delete("links"))
        assertEquals("keep", victim.readText())
    }

    @Test
    fun listsSitesNewestFirstWithTheirTitles() = runTest {
        call(SiteToolNames.CREATE, "name" to "Old one")
        Thread.sleep(20)
        call(SiteToolNames.CREATE, "name" to "New one", ctx = Ctx("t2"))
        val all = backend.store.all()
        assertEquals(listOf("new-one", "old-one"), all.map { it.id })
        assertEquals("New one", all.first().title)
        val list = call(SiteToolNames.LIST, ctx = Ctx("t9")).text
        assertTrue(list.startsWith("Sites:") && "old-one" in list && "new-one" in list, list)
    }
}
