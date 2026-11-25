package atom.extend.mcp

import atom.extend.Files
import atom.extend.SecretRead
import atom.extend.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.UUID

@Serializable
data class McpToolEntry(
    val name: String,
    val title: String? = null,
    val description: String = "",
    val inputSchema: JsonObject = JsonObject(emptyMap()),
    val readOnly: Boolean = false,
)

@Serializable
data class McpServer(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val hasToken: Boolean = false,
    val tools: List<McpToolEntry> = emptyList(),
    val enabledTools: Set<String> = emptySet(),
    val truncated: Boolean = false,
    /** Why the catalog was cut short; null when it was complete (or saved by an older version). */
    val truncation: McpTruncation? = null,
    val lastError: String? = null,
    val refreshedAt: Long = 0,
) {
    val enabledCount: Int get() = tools.count { it.name in enabledTools }
}

/** Saved MCP servers and their cached tool catalogs. Nothing here connects unless the user or a tool call asks. */
class McpManager(dir: File, private val secrets: () -> SecretStore, private val transport: McpTransport = OkHttpMcpTransport) {
    private val file = File(dir, "servers.json")
    private val serializer = ListSerializer(McpServer.serializer())
    private val _servers = MutableStateFlow(load())
    val servers: StateFlow<List<McpServer>> = _servers.asStateFlow()

    fun get(id: String): McpServer? = _servers.value.firstOrNull { it.id == id }

    private fun tokenKey(id: String) = "mcp.token.$id"

    suspend fun tokenState(id: String): SecretRead = secrets().read(tokenKey(id))

    /** Discovers the catalog first, so only reachable servers are saved. */
    suspend fun add(name: String, url: String, token: String?): McpServer {
        val cleanName = name.trim()
        val cleanUrl = url.trim()
        require(cleanName.isNotEmpty()) { "Enter a name." }
        McpClient.validateUrl(cleanUrl)?.let { throw IllegalArgumentException(it) }
        if (_servers.value.any { it.name.equals(cleanName, ignoreCase = true) }) throw IllegalArgumentException("A server named \"$cleanName\" already exists.")
        val tok = token?.trim()?.takeIf { it.isNotEmpty() }
        val (tools, truncated) = McpClient(transport, cleanUrl, tok).session { listTools() }
        val server = McpServer(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            url = cleanUrl,
            hasToken = tok != null,
            tools = tools.map { it.entry() },
            truncated = truncated != null,
            truncation = truncated,
            refreshedAt = System.currentTimeMillis(),
        )
        if (tok != null) secrets().write(tokenKey(server.id), tok)
        mutate { it + server }
        return server
    }

    /** Re-reads the catalog. Enabled tools that still exist stay enabled; new tools start off. */
    suspend fun refresh(id: String): McpServer {
        val server = get(id) ?: throw IllegalArgumentException("Server not found.")
        return try {
            val (tools, truncated) = client(server).session { listTools() }
            val names = tools.map { it.name }.toSet()
            val updated = server.copy(
                tools = tools.map { it.entry() },
                enabledTools = server.enabledTools.filterTo(mutableSetOf()) { it in names },
                truncated = truncated != null,
                truncation = truncated,
                lastError = null,
                refreshedAt = System.currentTimeMillis(),
            )
            replace(updated)
            updated
        } catch (e: Exception) {
            replace(server.copy(lastError = e.message))
            throw e
        }
    }

    suspend fun setToken(id: String, token: String?) {
        val tok = token?.trim()?.takeIf { it.isNotEmpty() }
        if (tok == null) secrets().remove(tokenKey(id)) else secrets().write(tokenKey(id), tok)
        get(id)?.let { replace(it.copy(hasToken = tok != null)) }
    }

    fun setEnabled(id: String, enabled: Boolean) = get(id)?.let { replace(it.copy(enabled = enabled)) }

    fun setToolEnabled(id: String, tool: String, enabled: Boolean) = get(id)?.let {
        replace(it.copy(enabledTools = if (enabled) it.enabledTools + tool else it.enabledTools - tool))
    }

    fun setAllTools(id: String, enabled: Boolean) = get(id)?.let {
        replace(it.copy(enabledTools = if (enabled) it.tools.map { t -> t.name }.toSet() else emptySet()))
    }

    suspend fun delete(id: String) {
        mutate { list -> list.filterNot { it.id == id } }
        runCatching { secrets().remove(tokenKey(id)) }
    }

    /** One tool call in its own session; an expired session is reopened once. */
    suspend fun call(id: String, tool: String, arguments: JsonObject): McpContent {
        val server = get(id) ?: throw McpException("That MCP server was removed.")
        val client = client(server)
        return try {
            client.session { callTool(tool, arguments) }
        } catch (e: McpException) {
            if (!e.sessionExpired) throw e
            client.session { callTool(tool, arguments) }
        }
    }

    private suspend fun client(server: McpServer): McpClient {
        val token = if (!server.hasToken) {
            null
        } else {
            when (val read = tokenState(server.id)) {
                is SecretRead.Value -> read.value
                SecretRead.Missing -> null
                SecretRead.Unreadable -> throw McpException(
                    "The token for \"${server.name}\" can no longer be decrypted. Re-enter it in Settings → MCP servers.",
                    authFailed = true,
                )
            }
        }
        return McpClient(transport, server.url, token)
    }

    private fun McpToolInfo.entry() = McpToolEntry(name, title, description, inputSchema, readOnly)

    private fun replace(server: McpServer) = mutate { list -> list.map { if (it.id == server.id) server else it } }

    @Synchronized
    private fun mutate(change: (List<McpServer>) -> List<McpServer>) {
        _servers.update(change)
        Files.writeAtomic(file, McpProtocol.json.encodeToString(serializer, _servers.value))
    }

    private fun load(): List<McpServer> =
        Files.readOrNull(file)?.let { runCatching { McpProtocol.json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
}

/** Pure rules for switching MCP tools on (unit-tested). */
object McpToolRules {
    /** How many tools "Enable all" would switch on that are not marked read-only (and so need confirming). */
    fun riskyToEnable(tools: List<McpToolEntry>, enabled: Set<String>): Int =
        tools.count { !it.readOnly && it.name !in enabled }
}
