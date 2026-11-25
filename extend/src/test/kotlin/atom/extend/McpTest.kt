package atom.extend

import atom.core.tool.Risk
import atom.core.tool.ToolContext
import atom.extend.mcp.McpCatalog
import atom.extend.mcp.McpClient
import atom.extend.mcp.McpException
import atom.extend.mcp.McpHttpReply
import atom.extend.mcp.McpManager
import atom.extend.mcp.McpNaming
import atom.extend.mcp.McpProtocol
import atom.extend.mcp.McpTools
import atom.extend.mcp.McpTransport
import atom.extend.mcp.McpTruncation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.StringReader
import java.nio.file.Files as NioFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun reader(s: String) = BufferedReader(StringReader(s))

private val SSE_FIXTURE = """
    |: keep-alive comment
    |event: message
    |data: {"jsonrpc":"2.0","method":"notifications/progress","params":{"progress":1}}
    |
    |event: message
    |data: {"jsonrpc":"2.0","id":7,"method":"ping"}
    |
    |id: 42
    |event: message
    |data: {"jsonrpc":"2.0","id":3,
    |data:  "result":{"tools":[{"name":"get_weather","description":"Weather","inputSchema":{"type":"object","properties":{"city":{"type":"string"}}},"annotations":{"readOnlyHint":true}},
    |data: {"name":"Send Email!","title":"Send email"}],"nextCursor":"p2"}}
    |
    |data: {"jsonrpc":"2.0","id":99,"result":{}}
    |
""".trimMargin()

private class FakeServer(
    private val sse: Boolean = true,
    private val sessionId: String? = "sess-1",
    private val pages: Int = 1,
    private val status: Int = 200,
) : McpTransport {
    val methods = mutableListOf<String>()
    val sentHeaders = mutableListOf<Map<String, String>>()
    var deletes = 0

    override suspend fun <T> post(url: String, headers: Map<String, String>, body: String, handle: (McpHttpReply) -> T): T {
        sentHeaders += headers
        val req = McpProtocol.json.parseToJsonElement(body).jsonObject
        val method = req["method"]!!.jsonPrimitive.content
        methods += method
        val id = req["id"]?.jsonPrimitive?.content
        if (id == null) return handle(McpHttpReply(202, emptyMap(), reader("")))
        val result = when (method) {
            "initialize" -> """{"protocolVersion":"2025-03-26","capabilities":{"tools":{}},"serverInfo":{"name":"fake","version":"1"}}"""
            "tools/list" -> {
                val cursor = (req["params"] as? JsonObject)?.get("cursor")?.jsonPrimitive?.content
                val page = cursor?.removePrefix("p")?.toInt() ?: 1
                val next = if (page < pages) ""","nextCursor":"p${page + 1}"""" else ""
                """{"tools":[{"name":"tool_$page","description":"Tool $page","annotations":{"readOnlyHint":${page % 2 == 1}}}]$next}"""
            }
            "tools/call" -> """{"content":[{"type":"text","text":"sunny"},{"type":"image","data":"AAAA","mimeType":"image/png"}],"isError":false}"""
            else -> """{}"""
        }
        val json = """{"jsonrpc":"2.0","id":$id,"result":$result}"""
        val headersOut = buildMap {
            put("content-type", if (sse) "text/event-stream" else "application/json")
            if (method == "initialize" && sessionId != null) put("mcp-session-id", sessionId)
        }
        val payload = if (sse) "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\"}\n\ndata: $json\n\n" else json
        return handle(McpHttpReply(status, headersOut, reader(payload)))
    }

    override suspend fun delete(url: String, headers: Map<String, String>) {
        deletes++
    }
}

private class MemorySecrets : SecretStore {
    val map = mutableMapOf<String, String>()
    var unreadable = false
    override suspend fun read(key: String) = when {
        unreadable && key in map -> SecretRead.Unreadable
        else -> map[key]?.let { SecretRead.Value(it) } ?: SecretRead.Missing
    }
    override suspend fun write(key: String, value: String) { map[key] = value }
    override suspend fun remove(key: String) { map.remove(key) }
}

private val noConfirm = object : ToolContext {
    override val taskId = "t"
    override suspend fun confirm(action: String) = true
}

class McpTest {
    @Test
    fun sseResponseIsFoundAmongNotificationsAndServerRequests() {
        val response = McpProtocol.findResponse("text/event-stream; charset=utf-8", reader(SSE_FIXTURE), 3)
        assertNotNull(response)
        val (tools, cursor) = McpProtocol.parseTools(McpProtocol.result(response))
        assertEquals(listOf("get_weather", "Send Email!"), tools.map { it.name })
        assertTrue(tools[0].readOnly)
        assertEquals(false, tools[1].readOnly)
        assertEquals("Send email", tools[1].title)
        assertEquals("object", tools[1].inputSchema["type"]!!.jsonPrimitive.content)
        assertEquals("p2", cursor)
    }

    @Test
    fun sseEventsJoinMultipleDataLines() {
        val events = mutableListOf<Pair<String?, String>>()
        McpProtocol.readSse(reader("event: a\ndata: one\ndata: two\n\ndata: three")) { e, d -> events += e to d; false }
        assertEquals(listOf("a" to "one\ntwo", null to "three"), events)
    }

    @Test
    fun plainJsonAndBatchResponses() {
        assertNotNull(McpProtocol.findResponse("application/json", reader("""{"jsonrpc":"2.0","id":2,"result":{}}"""), 2))
        assertNotNull(McpProtocol.findResponse("application/json", reader("""[{"jsonrpc":"2.0","method":"x"},{"jsonrpc":"2.0","id":"2","result":{}}]"""), 2))
        assertNull(McpProtocol.findResponse("application/json", reader("""{"jsonrpc":"2.0","id":5,"result":{}}"""), 2))
        assertNull(McpProtocol.findResponse("application/json", reader("<html>"), 2))
    }

    @Test
    fun jsonRpcErrorsBecomeExceptions() {
        val e = assertFailsWith<McpException> {
            McpProtocol.result(McpProtocol.json.parseToJsonElement("""{"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"Unknown tool"}}""").jsonObject)
        }
        assertTrue("Unknown tool" in e.message!!)
    }

    @Test
    fun contentPartsAreConverted() {
        val result = McpProtocol.json.parseToJsonElement(
            """{"content":[{"type":"text","text":"a"},{"type":"resource_link","uri":"file:///x","name":"x"},
               {"type":"resource","resource":{"uri":"u","text":"embedded"}},{"type":"audio","data":"x"}],"isError":true}""",
        ).jsonObject
        val c = McpProtocol.parseContent(result)
        assertTrue(c.isError)
        assertTrue("a" in c.text && "file:///x" in c.text && "embedded" in c.text && "[audio omitted]" in c.text)
        val structured = McpProtocol.parseContent(McpProtocol.json.parseToJsonElement("""{"structuredContent":{"t":21}}""").jsonObject)
        assertEquals("""{"t":21}""", structured.text)
    }

    @Test
    fun requestFraming() {
        val r = McpProtocol.json.parseToJsonElement(McpProtocol.request(1, "initialize", McpProtocol.initializeParams())).jsonObject
        assertEquals("2.0", r["jsonrpc"]!!.jsonPrimitive.content)
        assertEquals(McpProtocol.VERSION, r["params"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
        assertNull(McpProtocol.json.parseToJsonElement(McpProtocol.notification("notifications/initialized")).jsonObject["id"])
    }

    @Test
    fun sessionLifecycleWithPaginationHeadersAndClose(): Unit = runBlocking {
        val server = FakeServer(pages = 3)
        val (tools, truncated) = McpClient(server, "https://example.com/mcp", "tok").session { listTools() }
        assertEquals(listOf("tool_1", "tool_2", "tool_3"), tools.map { it.name })
        assertNull(truncated)
        assertEquals(listOf("initialize", "notifications/initialized", "tools/list", "tools/list", "tools/list"), server.methods)
        assertEquals("Bearer tok", server.sentHeaders[0]["Authorization"])
        assertNull(server.sentHeaders[0]["Mcp-Session-Id"])
        assertEquals("sess-1", server.sentHeaders[2]["Mcp-Session-Id"])
        assertEquals("2025-03-26", server.sentHeaders[2]["MCP-Protocol-Version"])
        assertEquals(1, server.deletes)
    }

    @Test
    fun catalogIsTruncated(): Unit = runBlocking {
        val (tools, truncated) = McpClient(FakeServer(pages = 5, sse = false, sessionId = null), "http://192.168.1.5:8080/mcp", null)
            .session { listTools(maxTools = 2) }
        assertEquals(2, tools.size)
        assertEquals(McpTruncation.ToolLimit, truncated)
    }

    @Test
    fun truncationNamesTheReason(): Unit = runBlocking {
        val pages = McpClient(FakeServer(pages = 5, sse = false, sessionId = null), "https://x/mcp", null).session { listTools(maxPages = 2) }
        assertEquals(2, pages.first.size)
        assertEquals(McpTruncation.PageLimit, pages.second)
        val size = McpClient(FakeServer(pages = 3, sse = false, sessionId = null), "https://x/mcp", null).session { listTools(maxBytes = 60) }
        assertEquals(McpTruncation.SizeBudget, size.second)
        assertTrue(size.first.size < 3)
        assertEquals(McpTruncation.DuplicateCursor, McpCatalog.stopReason(3, 10, 3, 64, "c1", setOf("c1")))
        assertNull(McpCatalog.stopReason(3, 10, 3, 64, null, emptySet()))
        assertNull(McpCatalog.stopReason(10, 10, 3, 64, null, emptySet()))
        assertEquals(McpTruncation.ToolLimit, McpCatalog.stopReason(10, 10, 3, 64, "c2", emptySet()))
    }

    @Test
    fun authFailureIsReported(): Unit = runBlocking {
        val e = assertFailsWith<McpException> { McpClient(FakeServer(status = 401), "https://x/mcp", "bad").session { listTools() } }
        assertTrue(e.authFailed)
    }

    @Test
    fun urlValidationAllowsHttpAndLan() {
        assertNull(McpClient.validateUrl("http://192.168.1.10:3000/mcp"))
        assertNull(McpClient.validateUrl("https://mcp.example.com"))
        assertNotNull(McpClient.validateUrl("ftp://x"))
        assertNotNull(McpClient.validateUrl("example.com"))
    }

    @Test
    fun namingIsSanitizedBoundedAndUnique() {
        assertEquals("mcp_home_assistant_turn_on_light", McpNaming.assign(listOf("Home Assistant" to "turn-on.light")).single())
        val names = McpNaming.assign(listOf("srv" to "a b", "srv" to "a-b", "srv" to "A_B"))
        assertEquals(listOf("mcp_srv_a_b", "mcp_srv_a_b_2", "mcp_srv_a_b_3"), names)
        val long = McpNaming.assign(listOf("x".repeat(50) to "y".repeat(100), "x".repeat(50) to "y".repeat(100)))
        assertTrue(long.all { it.length <= 64 && Regex("^[a-z0-9_]+$").matches(it) })
        assertEquals(2, long.toSet().size)
        assertEquals("mcp_x_x", McpNaming.assign(listOf("!!!" to "???")).single())
    }

    @Test
    fun managerToolsAreOffByDefaultAndScopedByRisk(): Unit = runBlocking {
        val secrets = MemorySecrets()
        val server = FakeServer(pages = 2)
        val manager = McpManager(NioFiles.createTempDirectory("mcp").toFile(), { secrets }, server)
        val s = manager.add("Weather", "https://w.example/mcp", "secret-token")
        assertEquals("secret-token", secrets.map["mcp.token.${s.id}"])
        assertTrue(McpTools.create(manager).isEmpty())
        manager.setAllTools(s.id, true)
        val tools = McpTools.create(manager)
        assertEquals(listOf("mcp_weather_tool_1", "mcp_weather_tool_2"), tools.map { it.spec.name })
        assertEquals(Risk.Read, tools[0].risk)
        assertEquals(Risk.Sensitive, tools[1].risk)
        val result = tools[0].run(JsonObject(mapOf("city" to JsonPrimitive("Kota"))), noConfirm)
        assertTrue(result.sensitive)
        assertEquals("sunny", result.text)
        assertEquals(1, result.images.size)
        manager.setEnabled(s.id, false)
        assertTrue(McpTools.create(manager).isEmpty())
    }

    @Test
    fun unreadableTokenIsNeverTreatedAsEmpty(): Unit = runBlocking {
        val secrets = MemorySecrets()
        val manager = McpManager(NioFiles.createTempDirectory("mcp").toFile(), { secrets }, FakeServer())
        val s = manager.add("A", "https://a/mcp", "t")
        manager.setAllTools(s.id, true)
        secrets.unreadable = true
        val result = McpTools.create(manager).first().run(JsonObject(emptyMap()), noConfirm)
        assertTrue(result.isError)
        assertTrue("decrypted" in result.text)
    }

    @Test
    fun refreshKeepsEnabledToolsThatStillExist(): Unit = runBlocking {
        val dir = NioFiles.createTempDirectory("mcp").toFile()
        val manager = McpManager(dir, { MemorySecrets() }, FakeServer(pages = 2))
        val s = manager.add("A", "https://a/mcp", null)
        manager.setToolEnabled(s.id, "tool_2", true)
        manager.refresh(s.id)
        assertEquals(setOf("tool_2"), manager.get(s.id)!!.enabledTools)
        assertEquals(1, McpManager(dir, { MemorySecrets() }).get(s.id)!!.enabledCount)
    }
}
