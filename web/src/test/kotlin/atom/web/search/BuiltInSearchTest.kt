package atom.web.search

import atom.web.net.HttpResult
import atom.web.net.HttpSpec
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun fixture(name: String): String =
    assertNotNull(BuiltInSearchTest::class.java.getResource("/search/$name"), "missing fixture $name").readText()

class KeylessParsersTest {
    @Test
    fun duckDuckGoLiteSkipsAdsAndPairsSnippets() {
        val r = assertNotNull(SearchParsers.duckDuckGoLite(fixture("ddg_lite.html")))
        assertEquals(
            listOf(
                "https://kotlinlang.org/docs/coroutines-overview.html",
                "https://github.com/Kotlin/kotlinx.coroutines",
                "https://developer.android.com/kotlin/coroutines",
            ),
            r.map { it.url },
        )
        assertEquals("Coroutines | Kotlin Documentation", r[0].title)
        assertEquals("A coroutine is an instance of a suspendable computation. Kotlin provides coroutines at the language level.", r[0].snippet)
        assertEquals("Library support for Kotlin coroutines with multiplatform support.", r[1].snippet)
        assertEquals("Kotlin coroutines on Android & Jetpack", r[2].title)
        assertEquals("", r[2].snippet, "the ad's snippet never lands on a result")
    }

    @Test
    fun duckDuckGoChallengeIsNotAResultsPage() {
        assertNull(SearchParsers.duckDuckGoLite(fixture("ddg_challenge.html")))
        assertTrue(SearchParsers.isDuckDuckGoChallenge(fixture("ddg_challenge.html")))
    }

    @Test
    fun duckDuckGoHtmlFixture() {
        val r = SearchParsers.duckDuckGo(fixture("ddg_html.html"))
        assertEquals(listOf("https://kotlinlang.org/docs/coroutines-overview.html"), r.map { it.url })
    }

    @Test
    fun bingUnwrapsTrackingLinksAndSkipsAdsAndInternalLinks() {
        val r = assertNotNull(SearchParsers.bing(fixture("bing.html")))
        assertEquals(listOf("https://kotlinlang.org/docs/coroutines-overview.html", "https://github.com/Kotlin/kotlinx.coroutines"), r.map { it.url })
        assertEquals("Coroutines | Kotlin Documentation", r[0].title)
        assertEquals("A coroutine is an instance of a suspendable computation & more.", r[0].snippet)
        assertEquals("Library support for Kotlin coroutines.", r[1].snippet)
    }

    @Test
    fun bingCaptchaIsNotAResultsPage() {
        assertNull(SearchParsers.bing(fixture("bing_captcha.html")))
    }

    @Test
    fun bingLinkUnwrapping() {
        assertEquals("https://example.org/a", SearchParsers.unwrapBingLink("https://example.org/a"))
        assertNull(SearchParsers.unwrapBingLink("/search?q=x"))
        assertNull(SearchParsers.unwrapBingLink("https://www.bing.com/ck/a?!&p=1&ntb=1"))
        // Base64url without padding, and a tracking link to a non-web scheme.
        assertEquals("https://a.b/c?d=1", SearchParsers.unwrapBingLink("https://www.bing.com/ck/a?u=a1aHR0cHM6Ly9hLmIvYz9kPTE&ntb=1"))
        assertNull(SearchParsers.unwrapBingLink("https://www.bing.com/ck/a?u=a1amF2YXNjcmlwdDphbGVydCgxKQ&ntb=1"))
    }

    @Test
    fun mojeekResultsAndEmptyPage() {
        val r = assertNotNull(SearchParsers.mojeek(fixture("mojeek.html")))
        assertEquals(
            listOf("https://kotlinlang.org/docs/coroutines-overview.html", "https://developer.android.com/kotlin/coroutines?hl=en&authuser=0"),
            r.map { it.url },
        )
        assertEquals("A coroutine is an instance of a suspendable computation.", r[0].snippet)
        assertEquals(emptyList(), SearchParsers.mojeek(fixture("mojeek_empty.html")))
        assertNull(SearchParsers.mojeek(fixture("bing_captcha.html")))
    }
}

class BuiltInSearchTest {
    private class FakeWeb(private val pages: Map<String, () -> HttpResult>) {
        val asked = mutableListOf<String>()
        suspend fun fetch(spec: HttpSpec): HttpResult {
            asked += spec.method + " " + spec.url
            val key = pages.keys.firstOrNull { spec.url.startsWith(it) } ?: return HttpResult(404, ByteArray(0), "text/html", spec.url)
            return pages.getValue(key)()
        }
    }

    private fun ok(html: String) = { HttpResult(200, html.toByteArray(), "text/html", "x") }
    private val down = { throw java.io.IOException("timeout") }

    private val ddgHtml = "https://html.duckduckgo.com/html/"
    private val ddgLite = "https://lite.duckduckgo.com/lite/"
    private val bing = "https://www.bing.com/search"
    private val mojeek = "https://www.mojeek.com/search"
    private val wiki = "https://en.wikipedia.org/"

    private fun search(web: FakeWeb, renderer: PageRenderer? = null) =
        BuiltInSearch(web::fetch, renderer = { renderer }, lang = { "en" })

    @Test
    fun firstSourceWithResultsWinsAndIsNamed() = runTest {
        val web = FakeWeb(mapOf(ddgHtml to ok(fixture("ddg_html.html"))))
        val out = search(web).search("kotlin coroutines", 8)
        assertEquals("DuckDuckGo", out.source)
        assertEquals(1, web.asked.size, "one request per search when the first source answers")
        assertTrue(web.asked.single().startsWith("POST $ddgHtml"))
    }

    @Test
    fun fallsThroughInOrderOneRequestEach() = runTest {
        val web = FakeWeb(
            mapOf(
                ddgHtml to ok(fixture("ddg_challenge.html")),
                ddgLite to ok(fixture("ddg_challenge.html")),
                bing to ok(fixture("bing_captcha.html")),
                mojeek to ok(fixture("mojeek.html")),
            ),
        )
        val out = search(web).search("kotlin coroutines", 8)
        assertEquals("Mojeek", out.source)
        assertEquals(2, out.results.size)
        assertEquals(listOf(ddgHtml, ddgLite, bing, mojeek), web.asked.map { it.substringAfter(' ').let { u -> listOf(ddgHtml, ddgLite, bing, mojeek).first { u.startsWith(it) } } })
    }

    @Test
    fun liteAndBingAreUsedWhenEarlierSourcesFail() = runTest {
        val lite = search(FakeWeb(mapOf(ddgHtml to down, ddgLite to ok(fixture("ddg_lite.html"))))).search("q", 2)
        assertEquals("DuckDuckGo Lite", lite.source)
        assertEquals(2, lite.results.size, "trimmed to the requested count")

        val b = search(FakeWeb(mapOf(ddgLite to down, bing to ok(fixture("bing.html"))))).search("q", 8)
        assertEquals("Bing", b.source)
    }

    @Test
    fun agentBrowserTierRunsWhenNoDirectRequestGetsResults() = runTest {
        val loaded = mutableListOf<String>()
        val renderer = PageRenderer { url, _ ->
            loaded += url
            if (url.startsWith(bing)) fixture("bing.html") else null
        }
        val web = FakeWeb(mapOf(wiki to ok("""{"pages":[]}""")))
        val out = search(web, renderer).search("kotlin coroutines", 8)
        assertEquals("Agent browser (Bing)", out.source)
        assertEquals(2, out.results.size)
        assertEquals(1, loaded.size)
        assertTrue(web.asked.none { it.contains("wikipedia") }, "Wikipedia is not needed once the browser found results")
    }

    @Test
    fun agentBrowserFallsBackToDuckDuckGoPage() = runTest {
        val renderer = PageRenderer { url, _ -> if (url.startsWith(bing)) fixture("bing_captcha.html") else fixture("ddg_html.html") }
        val out = search(FakeWeb(emptyMap()), renderer).search("kotlin coroutines", 8)
        assertEquals("Agent browser (DuckDuckGo)", out.source)
    }

    @Test
    fun wikipediaIsTheLastResortWithANote() = runTest {
        val json = """{"pages":[{"id":1,"key":"Coroutine","title":"Coroutine","excerpt":"A coroutine","description":"Computer science"}]}"""
        val out = search(FakeWeb(mapOf(wiki to ok(json))), PageRenderer { _, _ -> throw IllegalStateException("no WebView") }).search("coroutine", 8)
        assertEquals("Wikipedia", out.source)
        assertNotNull(out.note)
    }

    @Test
    fun everythingDownSaysWhatWasTried() = runTest {
        val e = assertFailsWith<WebException> { search(FakeWeb(emptyMap())).search("q", 8) }
        val msg = e.message.orEmpty()
        assertTrue(msg.startsWith("Built-in search is unavailable right now"))
        for (name in listOf("DuckDuckGo", "DuckDuckGo Lite", "Bing", "Mojeek", "Wikipedia")) assertTrue(name in msg, msg)
    }

    @Test
    fun aResultsPageWithNoHitsIsNoResultsNotAnError() = runTest {
        val out = search(FakeWeb(mapOf(mojeek to ok(fixture("mojeek_empty.html"))))).search("zzqxv", 8)
        assertEquals(emptyList(), out.results)
        assertEquals("Mojeek", out.source)
    }

    @Test
    fun slowSourcesAreSkippedOnceTheBudgetIsSpent() = runTest {
        var now = 0L
        val web = FakeWeb(mapOf(ddgHtml to { now += 21_000; throw java.io.IOException("slow") }))
        val e = assertFailsWith<WebException> { BuiltInSearch(web::fetch, renderer = { null }, clock = { now }, lang = { "en" }).search("q", 8) }
        assertEquals(1, web.asked.count { !it.contains("wikipedia") }, "no further direct request after the budget")
        assertTrue("Wikipedia" in e.message.orEmpty())
    }

    @Test
    fun requestsAreKeylessAndPlain() {
        val specs = KeylessSources.http.map { it.request("a b", 8, "en") }
        assertTrue(specs.none { s -> s.headers.keys.any { it.equals("Authorization", true) || it.equals("Cookie", true) } })
        assertEquals("https://lite.duckduckgo.com/lite/", specs[1].url)
        assertEquals("q=a+b&kl=", specs[1].body)
        assertEquals("https://www.bing.com/search?q=a+b&count=8", specs[2].url)
        assertEquals("https://www.mojeek.com/search?q=a+b", specs[3].url)
    }
}
