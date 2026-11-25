package atom.web.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadableTest {
    private val article = """
        <!DOCTYPE html><html><head><title>Paper boats &amp; rivers</title>
        <meta property="og:title" content="OG title"><style>body{color:red}</style>
        <script>var secret = "do not show";</script></head>
        <body>
          <header><nav class="site-nav"><a href="/">Home</a><a href="/about">About</a></nav></header>
          <div id="cookie-banner">We use cookies. <button>Accept</button></div>
          <main>
            <article>
              <h1>How to fold a paper boat</h1>
              <p>Folding a paper boat takes a single sheet, a flat table, and about two minutes of patience. Start with a rectangle.</p>
              <p>Fold it in half, then bring the corners down to make a hat shape &mdash; the classic first step, used by children everywhere.</p>
              <ul><li>Crease firmly</li><li>Open the hat into a square</li></ul>
              <p>Read <a href="/more/tips">more tips</a> or <a href="https://other.example/x">another guide</a>.</p>
              <pre>  keep   spacing</pre>
              <div class="share-buttons">Share on everything</div>
            </article>
            <aside>Related: buy boats</aside>
          </main>
          <footer>Copyright 2026</footer>
        </body></html>
    """.trimIndent()

    @Test
    fun extractsMainArticleWithoutChrome() {
        val doc = Readable.extract(article, "https://site.example/guide/boats")
        assertEquals("Paper boats & rivers", doc.title)
        val t = doc.text
        assertTrue(t.startsWith("# How to fold a paper boat"), t)
        assertTrue("single sheet, a flat table" in t)
        assertTrue("hat shape — the classic" in t)
        assertTrue("- Crease firmly" in t)
        assertTrue("  keep   spacing" in t)
        for (junk in listOf("do not show", "cookies", "Home", "Copyright", "buy boats", "Share on everything", "color:red")) {
            assertFalse(junk in t, "should drop: $junk")
        }
        assertEquals(
            listOf("https://site.example/more/tips", "https://other.example/x"),
            doc.links.map { it.url },
        )
    }

    @Test
    fun scoresDensestBlockWithoutArticleTag() {
        val html = """
            <html><body><div class="menu"><a href="/a">A</a> <a href="/b">B</a></div>
            <div class="content"><div class="post">
              <p>This is the first real paragraph of the post, with enough words, commas, and detail to score well.</p>
              <p>This second paragraph continues the story, adding more text, more commas, and more meaning here.</p>
            </div></div>
            <div class="sidebar">Sidebar junk that should go</div></body></html>
        """.trimIndent()
        val t = Readable.extract(html).text
        assertTrue(t.startsWith("This is the first real paragraph"), t)
        assertFalse("Sidebar junk" in t)
    }

    @Test
    fun survivesBrokenMarkupAndEntities() {
        val t = Readable.extract("<p>Fish &lt;3 chips &#169; &#x1F600; &unknown; <b>bold<p>next & last").text
        assertTrue("Fish <3 chips © 😀 &unknown; bold" in t, t)
        assertTrue("next & last" in t)
    }

    @Test
    fun pagesTextAtLineBreaks() {
        val text = (1..100).joinToString("\n") { "line $it with some words" }
        val first = Paging.page(text, 0, 200)
        assertTrue(first.text.endsWith("\n"))
        assertTrue(first.hasMore)
        val second = Paging.page(text, first.end, 200)
        assertEquals(first.end, second.offset)
        assertTrue(second.text.startsWith("line "))
        val last = Paging.page(text, text.length - 10, 200)
        assertFalse(last.hasMore)
        assertTrue("offset=${first.end}" in first.footer())
    }

    @Test
    fun duckDuckGoResults() {
        val html = """
            <div class="results">
              <div class="result results_links web-result">
                <h2 class="result__title"><a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org%2Fdocs%2F&amp;rut=abc">Kotlin <b>Docs</b></a></h2>
                <a class="result__snippet" href="#">Official Kotlin documentation.</a>
              </div>
              <div class="result results_links web-result result--ad">
                <a class="result__a" href="https://duckduckgo.com/y.js?ad_domain=x">Ad</a>
              </div>
              <div class="result web-result">
                <a class="result__a" href="https://example.com/page">Example</a>
                <div class="result__snippet">Second &amp; snippet</div>
              </div>
            </div>
        """.trimIndent()
        val r = SearchParsers.duckDuckGo(html)
        assertEquals(listOf("https://kotlinlang.org/docs/", "https://example.com/page"), r.map { it.url })
        assertEquals("Kotlin Docs", r[0].title)
        assertEquals("Second & snippet", r[1].snippet)
        assertTrue(SearchParsers.isDuckDuckGoChallenge("<div class=\"anomaly-modal__check\"></div>"))
        assertFalse(SearchParsers.isDuckDuckGoChallenge(html))
    }

    @Test
    fun wikipediaResults() {
        val json = """{"pages":[{"id":1,"key":"Paper_boat","title":"Paper boat","excerpt":"A <span class=\"searchmatch\">paper</span> boat","description":"Toy"}]}"""
        val r = SearchParsers.wikipedia(json, "en")
        assertEquals("https://en.wikipedia.org/wiki/Paper_boat", r[0].url)
        assertEquals("Toy — A paper boat", r[0].snippet)
    }
}
