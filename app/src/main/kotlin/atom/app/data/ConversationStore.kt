package atom.app.data

import atom.core.llm.ChatMessage
import atom.core.llm.ContentPart
import atom.core.llm.Role
import atom.core.llm.ToolCall
import atom.core.tool.Risk
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A message plus the app-side metadata the core model does not carry. */
data class StoredMessage(
    val message: ChatMessage,
    val durationMs: Long? = null,
    val isError: Boolean = false,
    val risk: Risk? = null,
    /** Agent-internal user turns (loop-guard nudges): sent to the model, not shown in the transcript. */
    val hidden: Boolean = false,
)

data class Conversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<StoredMessage>,
) {
    val history: List<ChatMessage> get() = messages.map { it.message }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()

        fun titleFrom(prompt: String): String {
            val line = prompt.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            return if (line.length <= 48) line else line.take(47).trimEnd() + "…"
        }
    }
}

data class ConversationSummary(val id: String, val title: String, val updatedAt: Long)

/**
 * One JSON file per conversation under filesDir/conversations plus a small index so the drawer never has to
 * parse every transcript. Image payloads are dropped on save: screenshots are large and only useful live.
 */
class ConversationStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val mutex = Mutex()
    private val index = MutableStateFlow<List<ConversationSummary>?>(null)

    /** Newest first. Building the index happens lazily on first collection. */
    val summaries: Flow<List<ConversationSummary>> = flow {
        ensureIndex()
        emitAll(index.filterNotNull())
    }

    suspend fun load(id: String): Conversation? = withContext(Dispatchers.IO) {
        mutex.withLock { readConversation(fileFor(id) ?: return@withLock null) }
    }

    suspend fun save(conversation: Conversation) = withContext(Dispatchers.IO) {
        val file = fileFor(conversation.id) ?: return@withContext
        mutex.withLock {
            loadIndexLocked()
            dir.mkdirs()
            writeAtomic(file, json.encodeToString(ConversationDto.serializer(), conversation.toDto()))
            val summary = ConversationSummary(conversation.id, conversation.title, conversation.updatedAt)
            publishIndexLocked((index.value.orEmpty().filterNot { it.id == conversation.id } + summary))
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadIndexLocked()
            fileFor(id)?.delete()
            publishIndexLocked(index.value.orEmpty().filterNot { it.id == id })
        }
    }

    private suspend fun ensureIndex() {
        if (index.value != null) return
        withContext(Dispatchers.IO) { mutex.withLock { loadIndexLocked() } }
    }

    private fun loadIndexLocked() {
        if (index.value != null) return
        val fromFile = runCatching {
            json.decodeFromString(ListSerializer(SummaryDto.serializer()), indexFile.readText())
                .map { ConversationSummary(it.id, it.title, it.updatedAt) }
        }.getOrNull()
        index.value = (fromFile ?: rebuildIndex()).sortedByDescending { it.updatedAt }
    }

    private fun rebuildIndex(): List<ConversationSummary> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") && f != indexFile }.orEmpty()
        val rebuilt = files.mapNotNull { f ->
            readConversation(f)?.let { ConversationSummary(it.id, it.title, it.updatedAt) }
        }
        if (rebuilt.isNotEmpty()) writeIndexFile(rebuilt)
        return rebuilt
    }

    private fun publishIndexLocked(list: List<ConversationSummary>) {
        val sorted = list.sortedByDescending { it.updatedAt }
        writeIndexFile(sorted)
        index.value = sorted
    }

    private fun writeIndexFile(list: List<ConversationSummary>) {
        dir.mkdirs()
        val dto = list.map { SummaryDto(it.id, it.title, it.updatedAt) }
        writeAtomic(indexFile, json.encodeToString(ListSerializer(SummaryDto.serializer()), dto))
    }

    private fun readConversation(file: File): Conversation? =
        runCatching { json.decodeFromString(ConversationDto.serializer(), file.readText()).toModel() }.getOrNull()

    private val indexFile get() = File(dir, INDEX_NAME)

    /** Ids are generated UUIDs; anything else is rejected so a crafted id can never escape [dir]. */
    private fun fileFor(id: String): File? =
        if (id.isNotEmpty() && id.length <= 64 && id.all { it.isLetterOrDigit() || it == '-' }) File(dir, "$id.json") else null

    private fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    private companion object {
        const val INDEX_NAME = "index.json"
    }
}

// ---- Persistence DTOs (kept by proguard-rules.pro) ----

@Serializable
internal data class SummaryDto(val id: String, val title: String, val updatedAt: Long)

@Serializable
internal data class ConversationDto(
    val version: Int = 1,
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<MessageDto>,
)

@Serializable
internal data class MessageDto(
    val role: String,
    val text: String = "",
    val toolCalls: List<ToolCallDto> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    val imagesDropped: Int = 0,
    val durationMs: Long? = null,
    val isError: Boolean = false,
    val risk: String? = null,
    val hidden: Boolean = false,
)

@Serializable
internal data class ToolCallDto(val id: String, val name: String, val arguments: String)

private fun Conversation.toDto() = ConversationDto(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    messages = messages.map { it.toDto() },
)

private fun StoredMessage.toDto() = MessageDto(
    role = message.role.name,
    text = message.text,
    toolCalls = message.toolCalls.map { ToolCallDto(it.id, it.name, it.argumentsJson) },
    toolCallId = message.toolCallId,
    toolName = message.toolName,
    imagesDropped = message.parts.count { it is ContentPart.Image },
    durationMs = durationMs,
    isError = isError,
    risk = risk?.name,
    hidden = hidden,
)

private fun ConversationDto.toModel() = Conversation(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    messages = messages.mapNotNull { it.toModel() },
)

private fun MessageDto.toModel(): StoredMessage? {
    val role = Role.entries.firstOrNull { it.name == role } ?: return null
    val message = ChatMessage(
        role = role,
        parts = if (text.isEmpty()) emptyList() else listOf(ContentPart.Text(text)),
        toolCalls = toolCalls.map { ToolCall(it.id, it.name, it.arguments) },
        toolCallId = toolCallId,
        toolName = toolName,
    )
    return StoredMessage(
        message = message,
        durationMs = durationMs,
        isError = isError,
        risk = risk?.let { name -> Risk.entries.firstOrNull { it.name == name } },
        hidden = hidden,
    )
}
