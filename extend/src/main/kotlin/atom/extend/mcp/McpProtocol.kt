package atom.extend.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.BufferedReader

class McpException(message: String, val authFailed: Boolean = false, val sessionExpired: Boolean = false) : Exception(message)

data class McpToolInfo(
    val name: String,
    val title: String?,
    val description: String,
    val inputSchema: JsonObject,
    val readOnly: Boolean,
)

data class McpContent(val text: String, val images: List<Pair<String, String>>, val isError: Boolean)

/** JSON-RPC 2.0 framing and Streamable HTTP (JSON or SSE) response parsing. Pure, so it is tested with fixtures. */
object McpProtocol {
    const val VERSION = "2025-06-18"
    val SUPPORTED = setOf("2025-06-18", "2025-03-26", "2024-11-05")
    private const val MAX_RESULT_CHARS = 30_000

    val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    fun request(id: Int, method: String, params: JsonObject? = null): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        if (params != null) put("params", params)
    }.toString()

    fun notification(method: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", method)
    }.toString()

    fun initializeParams(): JsonObject = buildJsonObject {
        put("protocolVersion", VERSION)
        put("capabilities", JsonObject(emptyMap()))
        put("clientInfo", buildJsonObject {
            put("name", "Atom")
            put("version", "1.0")
        })
    }

    /** One server-sent event per call of [onEvent]; return true to stop reading. */
    fun readSse(reader: BufferedReader, onEvent: (event: String?, data: String) -> Boolean) {
        var event: String? = null
        val data = StringBuilder()
        var hasData = false
        while (true) {
            val line = reader.readLine()
            if (line == null || line.isEmpty()) {
                if (hasData && onEvent(event, data.toString())) return
                event = null
                data.setLength(0)
                hasData = false
                if (line == null) return
                continue
            }
            if (line.startsWith(":")) continue
            val field = line.substringBefore(':')
            val value = line.substringAfter(':', "").removePrefix(" ")
            when (field) {
                "event" -> event = value
                "data" -> {
                    if (hasData) data.append('\n')
                    data.append(value)
                    hasData = true
                }
            }
        }
    }

    /**
     * The JSON-RPC response with [id] from a reply body of [contentType]. Server requests and notifications sent
     * on the same stream are skipped.
     */
    fun findResponse(contentType: String?, reader: BufferedReader, id: Int): JsonObject? {
        if (contentType?.contains("text/event-stream", ignoreCase = true) == true) {
            var found: JsonObject? = null
            readSse(reader) { _, data ->
                found = matching(data, id)
                found != null
            }
            return found
        }
        return matching(reader.readText(), id)
    }

    private fun matching(text: String, id: Int): JsonObject? {
        val el = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return null
        val candidates = if (el is JsonArray) el.mapNotNull { it as? JsonObject } else listOfNotNull(el as? JsonObject)
        return candidates.firstOrNull { o ->
            (o["id"] as? JsonPrimitive)?.let { it.intOrNull == id || it.contentOrNull == id.toString() } == true &&
                ("result" in o || "error" in o)
        }
    }

    /** The result object, or an [McpException] carrying the server's error message. */
    fun result(response: JsonObject): JsonObject {
        (response["error"] as? JsonObject)?.let { err ->
            val msg = (err["message"] as? JsonPrimitive)?.contentOrNull ?: "Unknown error"
            val code = (err["code"] as? JsonPrimitive)?.intOrNull
            throw McpException("Server error${code?.let { " $it" } ?: ""}: $msg")
        }
        return response["result"] as? JsonObject ?: JsonObject(emptyMap())
    }

    fun parseTools(result: JsonObject): Pair<List<McpToolInfo>, String?> {
        val tools = (result["tools"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val annotations = o["annotations"] as? JsonObject
            McpToolInfo(
                name = name,
                title = (o["title"] ?: annotations?.get("title"))?.let { (it as? JsonPrimitive)?.contentOrNull },
                description = o["description"]?.let { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                inputSchema = normalizeSchema(o["inputSchema"] as? JsonObject),
                readOnly = (annotations?.get("readOnlyHint") as? JsonPrimitive)?.booleanOrNull == true,
            )
        }
        val cursor = result["nextCursor"]?.let { (it as? JsonPrimitive)?.contentOrNull }?.takeIf { it.isNotEmpty() }
        return tools to cursor
    }

    private fun normalizeSchema(schema: JsonObject?): JsonObject {
        if (schema == null) return JsonObject(mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(emptyMap())))
        val m = schema.toMutableMap()
        m["type"] = JsonPrimitive("object")
        if (m["properties"] !is JsonObject) m["properties"] = JsonObject(emptyMap())
        m.remove("\$schema")
        return JsonObject(m)
    }

    fun parseContent(result: JsonObject): McpContent {
        val text = StringBuilder()
        val images = mutableListOf<Pair<String, String>>()
        (result["content"] as? JsonArray).orEmpty().forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            when (str("type")) {
                "text" -> text.appendBlock(str("text").orEmpty())
                "image" -> {
                    val data = str("data")
                    if (data != null && images.size < 4) images += (str("mimeType") ?: "image/png") to data else text.appendBlock("[image omitted]")
                }
                "audio" -> text.appendBlock("[audio omitted]")
                "resource_link" -> text.appendBlock("[resource: ${str("name") ?: ""} ${str("uri") ?: ""}]".replace("  ", " "))
                "resource" -> {
                    val r = o["resource"] as? JsonObject
                    val t = (r?.get("text") as? JsonPrimitive)?.contentOrNull
                    text.appendBlock(t ?: "[binary resource ${(r?.get("uri") as? JsonPrimitive)?.contentOrNull.orEmpty()}]")
                }
            }
        }
        if (text.isEmpty()) {
            result["structuredContent"]?.takeIf { it !is JsonNull }?.let { text.append(it.toString()) }
        }
        val isError = (result["isError"] as? JsonPrimitive)?.booleanOrNull == true
        var out = text.toString()
        if (out.length > MAX_RESULT_CHARS) out = out.take(MAX_RESULT_CHARS) + "\n…[${out.length - MAX_RESULT_CHARS} chars trimmed]"
        if (out.isEmpty() && images.isEmpty()) out = if (isError) "The tool reported an error." else "(no content)"
        return McpContent(out, images, isError)
    }

    private fun StringBuilder.appendBlock(s: String) {
        if (isNotEmpty()) append("\n\n")
        append(s)
    }

    fun negotiatedVersion(initResult: JsonObject): String =
        (initResult["protocolVersion"] as? JsonPrimitive)?.contentOrNull?.takeIf { it in SUPPORTED } ?: VERSION
}
