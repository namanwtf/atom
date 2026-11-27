package atom.llm.cloud

import atom.core.llm.ChatMessage
import atom.core.llm.ContentPart
import atom.core.llm.FinishReason
import atom.core.llm.LlmRequest
import atom.core.llm.ReasoningEffort
import atom.core.llm.ToolCall
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockWebServer

/**
 * A tool call cut off when the reply hit its output limit (finish reason `length`) can sit in a stored conversation
 * with arguments that are not JSON. Strict providers refuse a request that replays it (DeepSeek: "messages[6]
 * .tool_calls[0].function.arguments: EOF while parsing a string"), which would fail every later turn of the
 * conversation, so the wires that send arguments as text must always send a valid JSON object.
 */
class TruncatedToolCallTest {
    private lateinit var server: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() = server.close()

    private val cut = ToolCall(
        "call_cut0123456789abcdefghij",
        "site_write",
        """{"site":"demo","path":"index.html","content":"<!doctype html>\n<html><head><style>body{font-family:""",
    )
    private val whole = ToolCall("call_ok0123456789abcdefghijk", "site_read", """{"site": "demo", "path": "index.html"}""")

    /** A conversation as an older build stored it: the cut call kept its raw fragment. */
    private fun history() = listOf(
        ChatMessage.user("make me a landing page"),
        ChatMessage.assistant("", listOf(whole)),
        ChatMessage.toolResult(whole, listOf(ContentPart.Text("(placeholder page)"))),
        ChatMessage.assistant("Writing the page.", listOf(cut)),
        ChatMessage.toolResult(cut, listOf(ContentPart.Text("Arguments for 'site_write' were not a valid JSON object"))),
        ChatMessage.user("try again").copy(midTurn = true),
    )

    private val done = sseBody("""{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""", "[DONE]")

    private fun chatBody(providerId: String, effort: ReasoningEffort?): JsonObject {
        server.enqueueSse(done)
        CloudEngines.create(Providers.byId(providerId)!!.copy(wire = Wire.OpenAiChat), "key-abcdef", "deepseek-flash", server.url("/v1").toString())
            .collect(LlmRequest("deepseek-flash", history(), reasoning = effort))
        return server.takeRequest().json()
    }

    private fun validObject(text: String) = isStrictJsonObject(text)

    private fun chatArguments(body: JsonObject): Map<String, String> =
        body["messages"]!!.jsonArray.map { it.jsonObject }.flatMap { m -> m["tool_calls"]?.jsonArray.orEmpty() }
            .associate { c ->
                val call = c.jsonObject
                call["id"]!!.jsonPrimitive.content to call["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content
            }

    @Test
    fun chatCompletionsNeverReplaysACutOffCallsArguments() {
        // Thinking off keeps both calls in tool-call form (no notes), as with deepseek-chat or Kimi Instruct.
        for (provider in listOf("deepseek", "openai", "openrouter", "mistral")) {
            val args = chatArguments(chatBody(provider, ReasoningEffort.Off))
            assertEquals(2, args.size, provider)
            args.values.forEach { assertTrue(validObject(it), "$provider: $it") }
            // The complete call goes out byte for byte (a warm prompt cache), the cut one as an empty object.
            assertTrue(whole.argumentsJson in args.values, "$provider: $args")
            assertTrue("{}" in args.values, "$provider: $args")
        }
    }

    @Test
    fun aThinkingRequestIsValidJsonToo() {
        val body = chatBody("deepseek", ReasoningEffort.High)
        chatArguments(body).values.forEach { assertTrue(validObject(it), it) }
        // The whole body is what DeepSeek parses; it must round-trip.
        assertTrue(validObject(body.toString()))
    }

    @Test
    fun responsesNeverReplaysACutOffCallsArguments() {
        server.enqueueSse(
            namedSseBody(
                "response.completed" to """{"type":"response.completed","response":{"id":"r","status":"completed","output":[]}}""",
            ),
        )
        CloudEngines.create(Providers.byId("openai")!!, "sk-resp-secret-1", "gpt-6.1-sol", server.url("/v1").toString())
            .collect(LlmRequest("gpt-6.1-sol", history()))
        val calls = server.takeRequest().json()["input"]!!.jsonArray.map { it.jsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "function_call" }
        assertEquals(2, calls.size)
        val args = calls.map { it["arguments"]!!.jsonPrimitive.content }
        args.forEach { assertTrue(validObject(it), it) }
        assertEquals(listOf(whole.argumentsJson, "{}"), args)
    }

    @Test
    fun wireArgumentsKeepsValidJsonAndRepairsTheRest() {
        assertEquals("""{"level": 20}""", wireArguments("""{"level": 20}"""))
        assertEquals("{}", wireArguments(""))
        assertEquals("{}", wireArguments("""{"content":"<html><bo"""))
        assertEquals("{}", wireArguments("[1,2]"))
        assertEquals("{}", wireArguments("not json"))
        // Lenient JSON a model sometimes writes is sent strictly.
        assertEquals("""{"text":"hi"}""", wireArguments("""{text: hi}"""))
    }

    @Test
    fun theStrictCheckIsStrict() {
        assertTrue(isStrictJsonObject("""{"a": [1, -2.5e3, true, null, {"b": "\u00e9\n"}]}"""))
        listOf("""{"a":hi}""", """{a:1}""", """{"a":"x""", """{"a":1,}""", "[]", """{"a":01}""", """{"a":1} x""")
            .forEach { assertFalse(isStrictJsonObject(it), it) }
    }

    @Test
    fun structuredWiresQuoteUnquotedValuesToo() {
        assertEquals("""{"text":"hi","n":2}""", argumentsObject("""{"text": hi, "n": 2}""").toString())
        assertEquals("{}", argumentsObject(cut.argumentsJson).toString())
    }

    @Test
    fun theDecoderReportsTheLimitAndKeepsTheFragmentForTheAgent() {
        // The agent recognises a cut call by Length plus arguments that do not parse; it must see both.
        server.enqueueSse(
            sseBody(
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"site_write","arguments":""}}]}}]}""",
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"path\":\"index.html\",\"content\":\"<html>"}}]}}]}""",
                """{"choices":[{"index":0,"delta":{},"finish_reason":"length"}]}""",
                "[DONE]",
            ),
        )
        val events = CloudEngines.create(Providers.byId("deepseek")!!.copy(wire = Wire.OpenAiChat), "key-abcdef", "deepseek-flash", server.url("/v1").toString())
            .collect(LlmRequest("deepseek-flash", listOf(ChatMessage.user("page"))))
        assertEquals(listOf(FinishReason.Length), events.finished().map { it.reason })
        val call = events.calls().single()
        assertEquals("site_write", call.name)
        assertEquals("""{"path":"index.html","content":"<html>""", call.argumentsJson)
    }
}
