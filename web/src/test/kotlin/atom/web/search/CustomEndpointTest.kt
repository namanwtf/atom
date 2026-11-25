package atom.web.search

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CustomEndpointTest {
    private val json = """
        {"data": {"web": {"results": [
          {"meta": {"title": "First"}, "link": "https://a.example", "desc": "one"},
          {"meta": {"title": "Second"}, "link": "https://b.example", "desc": "two"},
          {"meta": {}, "desc": "no link"}
        ]}}, "pages": [{"body": {"text": "Hello page"}, "name": "P"}]}
    """.trimIndent()

    @Test
    fun extractsResultsWithNestedFieldPaths() {
        val ep = CustomEndpoint(resultsPath = "$.data.web.results", titlePath = "meta.title", linkPath = "link", snippetPath = "desc")
        val r = SearchParsers.custom(json, ep)
        assertEquals(2, r.size)
        assertEquals(SearchResult("First", "https://a.example", "one"), r[0])
        assertEquals("Second", r[1].title)
    }

    @Test
    fun wildcardAndIndexPaths() {
        val root = JsonPath.parse(json)
        assertEquals(listOf("https://a.example", "https://b.example"), JsonPath.select(root, "data.web.results[*].link").map { it.jsonPrimitive.content })
        assertEquals("two", JsonPath.string(root, "data.web.results[1].desc"))
        assertEquals("no link", JsonPath.string(root, "data.web.results[-1].desc"))
        assertEquals("Hello page", JsonPath.string(root, "pages[0].body.text"))
        assertEquals("First", JsonPath.string(root, "data['web'].results[0].meta.title"))
        assertNull(JsonPath.string(root, "data.nope[3].x"))
    }

    @Test
    fun rootArrayAndFanOut() {
        val arr = """[{"t":"x","u":"https://x"},{"t":"y","u":"https://y"}]"""
        val r = SearchParsers.custom(arr, CustomEndpoint(resultsPath = "", titlePath = "t", linkPath = "u", snippetPath = "s"))
        assertEquals(listOf("x", "y"), r.map { it.title })
        val fan = SearchParsers.custom(json, CustomEndpoint(resultsPath = "data.web.results[*]", titlePath = "meta.title", linkPath = "link"))
        assertEquals(2, fan.size)
    }

    @Test
    fun readerBodyPath() {
        val (title, body) = SearchParsers.customPage(json, CustomEndpoint(resultsPath = "pages", titlePath = "name", bodyPath = "body.text"))!!
        assertEquals("P", title)
        assertEquals("Hello page", body)
        assertNull(SearchParsers.customPage(json, CustomEndpoint(bodyPath = "missing")))
    }

    @Test
    fun badPathIsRejected() {
        assertFailsWith<IllegalArgumentException> { JsonPath.select(JsonPath.parse(json), "data[abc") }
    }

    @Test
    fun fillsJsonTemplateEscapingStringsAndKeepingNumbers() {
        val body = Template.json("""{"q": "{query}", "n": {count}, "s": {query}, "keep": "{other}"}""", mapOf("query" to "say \"hi\"\nnow", "count" to "7"))
        val o = JsonPath.parse(body).jsonObject
        assertEquals("say \"hi\"\nnow", o["q"]!!.jsonPrimitive.content)
        assertEquals(7, o["n"]!!.jsonPrimitive.int)
        assertEquals("say \"hi\"\nnow", o["s"]!!.jsonPrimitive.content)
        assertEquals("{other}", o["keep"]!!.jsonPrimitive.content)
    }

    @Test
    fun fillsUrlTemplateWithEncoding() {
        assertEquals("https://s.example/?q=a%20b%26c&n=5", Template.url("https://s.example/?q={query}&n={count}", mapOf("query" to "a b&c", "count" to "5")))
    }

    @Test
    fun customRequestBuilder() {
        val ep = CustomEndpoint(
            method = "post", url = "https://s.example/v1/search", bodyTemplate = """{"query":"{query}","limit":{count}}""",
            headers = listOf(HeaderEntry("X-Test", "1"), HeaderEntry("", "ignored")),
        )
        val spec = WebRequests.custom(ep, "sk-1", mapOf("query" to "kotlin", "count" to "8"))
        assertEquals("POST", spec.method)
        assertEquals("Bearer sk-1", spec.headers["Authorization"])
        assertEquals("1", spec.headers["X-Test"])
        assertEquals(1 + 1, spec.headers.size)
        assertEquals("""{"query":"kotlin","limit":8}""", spec.body)

        val get = WebRequests.custom(ep.copy(method = "GET", url = "https://s.example/?q={query}"), null, mapOf("query" to "x y"))
        assertNull(get.body)
        assertEquals("https://s.example/?q=x%20y", get.url)
        assertTrue("Authorization" !in get.headers)

        assertFailsWith<WebException> { WebRequests.custom(ep.copy(bodyTemplate = "{\"q\": {query"), null, mapOf("query" to "x")) }
    }

    @Test
    fun deepSeekRequests() {
        val s = WebRequests.deepSeekSearch("https://api.deepseek.com/v1/", "k", "what \"is\"", 6)
        assertEquals("https://api.deepseek.com/v1/tools/search", s.url)
        assertEquals("what \"is\"", JsonPath.string(JsonPath.parse(s.body!!), "query"))
        assertEquals("6", JsonPath.string(JsonPath.parse(s.body), "count"))
        assertEquals("https://api.deepseek.com/v1/tools/fetch", WebRequests.deepSeekFetch("https://api.deepseek.com", "k", "https://x").url)
    }
}
