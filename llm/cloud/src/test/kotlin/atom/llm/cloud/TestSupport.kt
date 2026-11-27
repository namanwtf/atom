package atom.llm.cloud

import atom.core.llm.LlmEngine
import atom.core.llm.LlmEvent
import atom.core.llm.LlmException
import atom.core.llm.LlmRequest
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

internal fun sseBody(vararg data: String): String = data.joinToString("") { "data: $it\n\n" }

internal fun namedSseBody(vararg events: Pair<String, String>): String =
    events.joinToString("") { (type, data) -> "event: $type\ndata: $data\n\n" }

internal fun MockWebServer.enqueueSse(body: String) =
    enqueue(MockResponse.Builder().addHeader("Content-Type", "text/event-stream").body(body).build())

internal fun MockWebServer.enqueueJson(code: Int, body: String) =
    enqueue(MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build())

internal fun LlmEngine.collect(request: LlmRequest): List<LlmEvent> = runBlocking { stream(request).toList() }

internal fun LlmEngine.failure(request: LlmRequest): LlmException =
    assertFailsWith<LlmException> { collect(request) }

internal fun RecordedRequest.json(): JsonObject = Json.parseToJsonElement(body!!.utf8()).jsonObject

internal fun List<LlmEvent>.text(): String = filterIsInstance<LlmEvent.TextDelta>().joinToString("") { it.text }

internal fun List<LlmEvent>.calls() = filterIsInstance<LlmEvent.ToolCallReady>().map { it.call }

internal fun List<LlmEvent>.finished() = filterIsInstance<LlmEvent.Finished>()

internal val weatherSchema: JsonObject = Json.parseToJsonElement(
    """{"type":"object","properties":{"city":{"type":"string","description":"City name"},
       "unit":{"type":["string","null"],"enum":["c","f"]}},"required":["city","missing"],
       "additionalProperties":false,"${'$'}schema":"http://json-schema.org/draft-07/schema#"}""",
).jsonObject

/**
 * Whether [text] is one JSON object under RFC 8259, as a strict provider parses it. kotlinx's own parser is no
 * judge here: it accepts unquoted values (`{"a":hi}`) even when not lenient.
 */
internal fun isStrictJsonObject(text: String): Boolean {
    var i = 0
    fun ws() { while (i < text.length && text[i] in " \t\r\n") i++ }
    fun fail(): Nothing = throw IllegalArgumentException("at $i")
    fun expect(c: Char) { if (i < text.length && text[i] == c) i++ else fail() }
    fun string() {
        expect('"')
        while (true) {
            if (i >= text.length) fail()
            val c = text[i++]
            when {
                c == '"' -> return
                c == '\\' -> {
                    if (i >= text.length) fail()
                    when (text[i++]) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                        'u' -> repeat(4) { if (i >= text.length || text[i++].lowercaseChar() !in "0123456789abcdef") fail() }
                        else -> fail()
                    }
                }
                c < ' ' -> fail()
            }
        }
    }
    lateinit var value: () -> Unit
    fun obj() {
        expect('{'); ws()
        if (i < text.length && text[i] == '}') { i++; return }
        while (true) {
            ws(); string(); ws(); expect(':'); value(); ws()
            if (i < text.length && text[i] == ',') { i++; continue }
            expect('}'); return
        }
    }
    fun arr() {
        expect('['); ws()
        if (i < text.length && text[i] == ']') { i++; return }
        while (true) {
            value(); ws()
            if (i < text.length && text[i] == ',') { i++; continue }
            expect(']'); return
        }
    }
    val number = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
    value = {
        ws()
        when {
            i >= text.length -> fail()
            text[i] == '{' -> obj()
            text[i] == '[' -> arr()
            text[i] == '"' -> string()
            text.startsWith("true", i) -> i += 4
            text.startsWith("false", i) -> i += 5
            text.startsWith("null", i) -> i += 4
            else -> number.matchAt(text, i)?.let { i += it.value.length } ?: fail()
        }
    }
    return try {
        ws(); obj(); ws(); i == text.length
    } catch (e: IllegalArgumentException) {
        false
    }
}
