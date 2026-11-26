package atom.extend.mcp

import atom.extend.ExtendHttp
import atom.extend.await
import atom.extend.networkReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.IOException

class McpHttpReply(val code: Int, private val headers: Map<String, String>, val body: BufferedReader) {
    fun header(name: String): String? = headers[name.lowercase()]
}

/** One HTTP exchange; the reply (and its connection) is closed when [handle] returns. */
interface McpTransport {
    suspend fun <T> post(url: String, headers: Map<String, String>, body: String, handle: (McpHttpReply) -> T): T
    suspend fun delete(url: String, headers: Map<String, String>) {}
}

object OkHttpMcpTransport : McpTransport {
    private val JSON = "application/json".toMediaType()

    override suspend fun <T> post(url: String, headers: Map<String, String>, body: String, handle: (McpHttpReply) -> T): T {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.post(body.toRequestBody(JSON)).build()
        val response = try {
            ExtendHttp.client.newCall(request).await()
        } catch (e: IOException) {
            throw McpException(networkReason(e))
        }
        return response.use { r ->
            withContext(Dispatchers.IO) {
                val map = r.headers.associate { it.first.lowercase() to it.second }
                handle(McpHttpReply(r.code, map, r.body.charStream().buffered()))
            }
        }
    }

    override suspend fun delete(url: String, headers: Map<String, String>) {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.delete().build()
        runCatching { ExtendHttp.client.newCall(request).await().close() }
    }
}

/**
 * MCP over Streamable HTTP. A session is opened for one piece of work (discovery or a tool call) and closed
 * right after, so no connection outlives the run that needed it.
 */
class McpClient(private val transport: McpTransport, private val url: String, private val token: String?) {

    inner class Session internal constructor(private val sessionId: String?, private val version: String) {
        private var nextId = 2

        internal fun headers(): Map<String, String> = baseHeaders() + buildMap {
            put("MCP-Protocol-Version", version)
            sessionId?.let { put("Mcp-Session-Id", it) }
        }

        suspend fun send(method: String, params: JsonObject?): JsonObject {
            val id = nextId++
            return exchange(id, McpProtocol.request(id, method, params), headers(), sessionBound = sessionId != null).second
        }

        /**
         * The catalog and why it was cut short, if it was: more than [maxTools] tools, more than [maxPages] pages,
         * more than [maxBytes] of tool definitions, or a server that sent the same cursor twice.
         */
        suspend fun listTools(
            maxTools: Int = MAX_TOOLS,
            maxPages: Int = MAX_PAGES,
            maxBytes: Long = MAX_CATALOG_BYTES,
        ): Pair<List<McpToolInfo>, McpTruncation?> {
            val all = mutableListOf<McpToolInfo>()
            val seenCursors = HashSet<String>()
            var cursor: String? = null
            var pages = 0
            var bytes = 0L
            var reason: McpTruncation? = null
            while (true) {
                val params = cursor?.let { c -> buildJsonObject { put("cursor", c) } }
                val (tools, next) = McpProtocol.parseTools(McpProtocol.result(send("tools/list", params)))
                pages++
                for (t in tools) {
                    bytes += McpCatalog.sizeOf(t)
                    if (bytes > maxBytes) {
                        reason = McpTruncation.SizeBudget
                        break
                    }
                    all += t
                }
                reason = reason ?: McpCatalog.stopReason(all.size, maxTools, pages, maxPages, next, seenCursors)
                if (reason != null || next == null) break
                seenCursors += next
                cursor = next
            }
            if (all.distinctBy { it.name }.size > maxTools) reason = McpTruncation.ToolLimit
            return all.distinctBy { it.name }.take(maxTools) to reason
        }

        suspend fun callTool(name: String, arguments: JsonObject): McpContent {
            val params = buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            }
            return McpProtocol.parseContent(McpProtocol.result(send("tools/call", params)))
        }
    }

    suspend fun <T> session(block: suspend Session.() -> T): T {
        val (sessionId, response) = exchange(1, McpProtocol.request(1, "initialize", McpProtocol.initializeParams()), baseHeaders(), sessionBound = false)
        val version = McpProtocol.negotiatedVersion(McpProtocol.result(response))
        val session = Session(sessionId, version)
        try {
            runCatching {
                transport.post(url, session.headers(), McpProtocol.notification("notifications/initialized")) { }
            }
            return session.block()
        } finally {
            if (sessionId != null) withContext(NonCancellable) { transport.delete(url, session.headers()) }
        }
    }

    private fun baseHeaders(): Map<String, String> = buildMap {
        put("Accept", "application/json, text/event-stream")
        token?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
    }

    /** Sends one request; returns the session id header (initialize only) and the matching response. */
    private suspend fun exchange(id: Int, body: String, headers: Map<String, String>, sessionBound: Boolean): Pair<String?, JsonObject> =
        transport.post(url, headers, body) { reply ->
            when {
                reply.code == 401 || reply.code == 403 ->
                    throw McpException("The server refused access (HTTP ${reply.code}). Check the token.", authFailed = true)
                reply.code == 404 && sessionBound -> throw McpException("The server ended the session.", sessionExpired = true)
                reply.code !in 200..299 -> throw McpException("The server answered with HTTP ${reply.code}.")
            }
            val response = McpProtocol.findResponse(reply.header("content-type"), reply.body, id)
                ?: throw McpException("The server's reply was not a valid MCP response.")
            reply.header("mcp-session-id") to response
        }

    companion object {
        const val MAX_TOOLS = 2048
        const val MAX_PAGES = 64

        /** Budget for the cached tool definitions (names, descriptions and schemas). */
        const val MAX_CATALOG_BYTES = 4L * 1024 * 1024

        /** Null when [url] is a usable http(s) address (LAN hosts and plain http included). */
        fun validateUrl(url: String): String? {
            val u = url.trim().toHttpUrlOrNull() ?: return "Enter a full http:// or https:// address."
            return if (u.host.isBlank()) "Enter a full http:// or https:// address." else null
        }
    }
}

/** Why a tool catalog was cut short. */
@kotlinx.serialization.Serializable
enum class McpTruncation { ToolLimit, PageLimit, SizeBudget, DuplicateCursor }

/** Pure paging rules for tools/list (unit-tested). */
object McpCatalog {
    /** Rough size of one tool definition, as cached. */
    fun sizeOf(t: McpToolInfo): Long =
        (t.name.length + (t.title?.length ?: 0) + t.description.length + t.inputSchema.toString().length).toLong()

    /** Null to keep paging (or stop because there is no next page); otherwise why listing stops early. */
    fun stopReason(count: Int, maxTools: Int, pages: Int, maxPages: Int, next: String?, seen: Set<String>): McpTruncation? = when {
        count > maxTools -> McpTruncation.ToolLimit
        next == null -> null
        count >= maxTools -> McpTruncation.ToolLimit
        next in seen -> McpTruncation.DuplicateCursor
        pages >= maxPages -> McpTruncation.PageLimit
        else -> null
    }
}
