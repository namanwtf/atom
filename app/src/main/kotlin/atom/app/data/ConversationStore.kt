package atom.app.data

import atom.core.agent.Compactor
import atom.core.agent.SessionStats
import atom.core.llm.SearchSuggestions
import atom.core.llm.ChatMessage
import atom.core.llm.ContentPart
import atom.core.llm.ReasoningEffort
import atom.core.llm.Role
import atom.core.llm.ToolCall
import atom.core.tool.Risk
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** What a stored entry is. Only [Message] and [Summary] entries reach the model. */
enum class EntryKind {
    Message,

    /** A compaction summary: the model sees it instead of everything before it. */
    Summary,

    /** A timeline note for the user (stopped, interrupted, model fallback...). Never sent to the model. */
    Notice,
}

enum class AttachmentKind { Image, File, Folder, Path }

/** Something the user attached. Files, folders and paths reach the agent as paths it reads with its own tools. */
@Serializable
data class Attachment(val kind: AttachmentKind, val path: String, val name: String, val sizeBytes: Long? = null)

/** A message plus the app-side metadata the core model does not carry. */
data class StoredMessage(
    val message: ChatMessage,
    val durationMs: Long? = null,
    val isError: Boolean = false,
    val risk: Risk? = null,
    /** Agent-internal user turns (loop-guard nudges): sent to the model, not shown in the transcript. */
    val hidden: Boolean = false,
    val kind: EntryKind = EntryKind.Message,
    val reasoning: String? = null,
    val reasoningMs: Long? = null,
    val attachments: List<Attachment> = emptyList(),
    /** A user message delivered to a task while it was running. */
    val steering: Boolean = false,
    val createdAt: Long = 0,
    /**
     * Search suggestions the provider attached to this answer (Gemini grounding). The chat shows them under the
     * answer: [SearchSuggestions.renderedContent] as given, or one chip per query opening [SearchSuggestions.searchUrl].
     */
    val searchSuggestions: SearchSuggestions? = null,
) {
    val isVisibleUser: Boolean get() = kind == EntryKind.Message && message.role == Role.User && !hidden

    companion object {
        fun notice(text: String, now: Long = System.currentTimeMillis()) =
            StoredMessage(ChatMessage(Role.System, listOf(ContentPart.Text(text))), kind = EntryKind.Notice, createdAt = now)

        fun summary(message: ChatMessage, now: Long = System.currentTimeMillis()) =
            StoredMessage(message, kind = EntryKind.Summary, createdAt = now)
    }
}

enum class TitleSource { Placeholder, Generated, User }

data class Conversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<StoredMessage>,
    val pinned: Boolean = false,
    val titleSource: TitleSource = TitleSource.Placeholder,
    /** This conversation's own model; null follows the default. */
    val selection: EngineSelection? = null,
    /** Null follows the default reasoning switch. */
    val reasoningEnabled: Boolean? = null,
    /** Null means "model default". */
    val reasoningEffort: ReasoningEffort? = null,
    val stats: SessionStats = SessionStats(),
    val contextTokens: Int? = null,
    val contextWindow: Int? = null,
) {
    val history: List<ChatMessage> get() = messages.filter { it.kind != EntryKind.Notice }.map { it.message }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()

        fun titleFrom(prompt: String): String {
            val line = prompt.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            return if (line.length <= 48) line else line.take(47).trimEnd() + "…"
        }

        fun empty(id: String = newId(), now: Long = System.currentTimeMillis()) =
            Conversation(id = id, title = "", createdAt = now, updatedAt = now, messages = emptyList())
    }
}

/** What the drawer shows for a conversation without loading it. */
data class ConversationSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val preview: String = "",
)

data class ConversationMatch(val id: String, val snippet: String)

/**
 * One JSON file per conversation under filesDir/conversations plus a small index so the drawer never has to
 * parse every transcript. Image payloads are dropped on save: screenshots are large and only useful live.
 */
class ConversationStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val mutex = Mutex()
    private val index = MutableStateFlow<List<ConversationSummary>?>(null)

    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /** Ids of conversations just saved, updated or deleted. */
    val changes: SharedFlow<String> = _changes.asSharedFlow()

    /** Plain text per conversation for search, built on the first search and kept in step with saves. */
    private val searchText = HashMap<String, Pair<Long, String>>()

    /** Pinned first, then newest first. Building the index happens lazily on first collection. */
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
            searchText[conversation.id] = conversation.updatedAt to plainText(conversation)
            publishIndexLocked(index.value.orEmpty().filterNot { it.id == conversation.id } + conversation.summary())
        }
        _changes.tryEmit(conversation.id)
    }

    /** Load, change and save in one step; returns the saved conversation or null if it does not exist. */
    suspend fun update(id: String, change: (Conversation) -> Conversation): Conversation? = withContext(Dispatchers.IO) {
        val file = fileFor(id) ?: return@withContext null
        val saved = mutex.withLock {
            loadIndexLocked()
            val current = readConversation(file) ?: return@withLock null
            val next = change(current)
            writeAtomic(file, json.encodeToString(ConversationDto.serializer(), next.toDto()))
            searchText[id] = next.updatedAt to plainText(next)
            publishIndexLocked(index.value.orEmpty().filterNot { it.id == id } + next.summary())
            next
        }
        if (saved != null) _changes.tryEmit(id)
        saved
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadIndexLocked()
            fileFor(id)?.delete()
            searchText.remove(id)
            publishIndexLocked(index.value.orEmpty().filterNot { it.id == id })
        }
        _changes.tryEmit(id)
    }

    /** Matches titles and every message, including conversations not loaded yet. */
    suspend fun search(query: String, limit: Int = 50): List<ConversationMatch> = withContext(Dispatchers.IO) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return@withContext emptyList()
        mutex.withLock {
            loadIndexLocked()
            val out = ArrayList<ConversationMatch>()
            for (summary in index.value.orEmpty()) {
                val cached = searchText[summary.id]
                val text = if (cached != null && cached.first == summary.updatedAt) {
                    cached.second
                } else {
                    val c = fileFor(summary.id)?.let(::readConversation) ?: continue
                    plainText(c).also { searchText[summary.id] = summary.updatedAt to it }
                }
                val at = text.lowercase().indexOf(q)
                if (at >= 0 || summary.title.lowercase().contains(q)) {
                    out += ConversationMatch(summary.id, if (at >= 0) snippet(text, at, q.length) else "")
                    if (out.size >= limit) break
                }
            }
            out
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
                .map { ConversationSummary(it.id, it.title, it.updatedAt, it.pinned, it.preview) }
        }.getOrNull()
        index.value = sort(fromFile ?: rebuildIndex())
    }

    private fun rebuildIndex(): List<ConversationSummary> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") && f != indexFile }.orEmpty()
        val rebuilt = files.mapNotNull { f -> readConversation(f)?.summary() }
        if (rebuilt.isNotEmpty()) writeIndexFile(rebuilt)
        return rebuilt
    }

    private fun publishIndexLocked(list: List<ConversationSummary>) {
        val sorted = sort(list)
        writeIndexFile(sorted)
        index.value = sorted
    }

    private fun sort(list: List<ConversationSummary>) =
        list.sortedWith(compareByDescending<ConversationSummary> { it.pinned }.thenByDescending { it.updatedAt })

    private fun writeIndexFile(list: List<ConversationSummary>) {
        dir.mkdirs()
        val dto = list.map { SummaryDto(it.id, it.title, it.updatedAt, it.pinned, it.preview) }
        writeAtomic(indexFile, json.encodeToString(ListSerializer(SummaryDto.serializer()), dto))
    }

    private fun readConversation(file: File): Conversation? =
        runCatching { json.decodeFromString(ConversationDto.serializer(), file.readText()).toModel() }.getOrNull()

    private val indexFile get() = File(dir, INDEX_NAME)

    /** Ids are generated UUIDs; anything else is rejected so a crafted id can never escape [dir]. */
    private fun fileFor(id: String): File? = if (isValidId(id)) File(dir, "$id.json") else null

    private fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    companion object {
        private const val INDEX_NAME = "index.json"

        fun isValidId(id: String): Boolean = id.isNotEmpty() && id.length <= 64 && id.all { it.isLetterOrDigit() || it == '-' }

        private fun plainText(c: Conversation): String = buildString {
            append(c.title).append('\n')
            c.messages.forEach { m ->
                if (m.kind == EntryKind.Message && m.message.role != Role.Tool && !m.hidden) append(m.message.text).append('\n')
            }
        }

        private fun snippet(text: String, at: Int, len: Int): String {
            val start = (at - 40).coerceAtLeast(0)
            val end = (at + len + 80).coerceAtMost(text.length)
            return (if (start > 0) "…" else "") + text.substring(start, end).replace('\n', ' ').trim() + if (end < text.length) "…" else ""
        }
    }
}

private fun Conversation.summary(): ConversationSummary {
    val last = messages.lastOrNull { it.kind == EntryKind.Message && !it.hidden && it.message.text.isNotBlank() && it.message.role != Role.Tool }
    val preview = last?.message?.text?.replace('\n', ' ')?.trim()?.take(120).orEmpty()
    return ConversationSummary(id, title, updatedAt, pinned, preview)
}

// ---- Persistence DTOs (kept by proguard-rules.pro) ----

@Serializable
internal data class SummaryDto(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val preview: String = "",
)

@Serializable
internal data class ConversationDto(
    val version: Int = 2,
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<MessageDto>,
    val pinned: Boolean = false,
    val titleSource: String? = null,
    val selection: EngineSelection? = null,
    val reasoningEnabled: Boolean? = null,
    val reasoningEffort: String? = null,
    val stats: SessionStats? = null,
    val contextTokens: Int? = null,
    val contextWindow: Int? = null,
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
    val kind: String? = null,
    val reasoning: String? = null,
    val reasoningMs: Long? = null,
    val attachments: List<Attachment> = emptyList(),
    val steering: Boolean = false,
    val createdAt: Long = 0,
    val searchSuggestions: SearchSuggestions? = null,
)

@Serializable
internal data class ToolCallDto(val id: String, val name: String, val arguments: String)

internal fun Conversation.toDto() = ConversationDto(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    messages = messages.map { it.toDto() },
    pinned = pinned,
    titleSource = titleSource.name,
    selection = selection,
    reasoningEnabled = reasoningEnabled,
    reasoningEffort = reasoningEffort?.name,
    stats = stats.takeIf { it != SessionStats() },
    contextTokens = contextTokens,
    contextWindow = contextWindow,
)

internal fun StoredMessage.toDto() = MessageDto(
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
    kind = kind.takeIf { it != EntryKind.Message }?.name,
    reasoning = reasoning,
    reasoningMs = reasoningMs,
    attachments = attachments,
    steering = steering,
    createdAt = createdAt,
    searchSuggestions = searchSuggestions?.takeIf { !it.isEmpty },
)

internal fun ConversationDto.toModel() = Conversation(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    messages = messages.mapNotNull { it.toModel() },
    pinned = pinned,
    // Files written before titles were generated have no source; their titles are never rewritten.
    titleSource = titleSource?.let { name -> TitleSource.entries.firstOrNull { it.name == name } } ?: TitleSource.Generated,
    selection = selection,
    reasoningEnabled = reasoningEnabled,
    reasoningEffort = reasoningEffort?.let { name -> ReasoningEffort.entries.firstOrNull { it.name == name } },
    stats = stats ?: SessionStats(),
    contextTokens = contextTokens,
    contextWindow = contextWindow,
)

internal fun MessageDto.toModel(): StoredMessage? {
    val role = Role.entries.firstOrNull { it.name == role } ?: return null
    val message = ChatMessage(
        role = role,
        parts = if (text.isEmpty()) emptyList() else listOf(ContentPart.Text(text)),
        toolCalls = toolCalls.map { ToolCall(it.id, it.name, it.arguments) },
        toolCallId = toolCallId,
        toolName = toolName,
    )
    val entryKind = kind?.let { name -> EntryKind.entries.firstOrNull { it.name == name } }
        ?: if (role == Role.User && Compactor.isSummary(message)) EntryKind.Summary else EntryKind.Message
    return StoredMessage(
        message = message,
        durationMs = durationMs,
        isError = isError,
        risk = risk?.let { name -> Risk.entries.firstOrNull { it.name == name } },
        hidden = hidden,
        kind = entryKind,
        reasoning = reasoning,
        reasoningMs = reasoningMs,
        attachments = attachments,
        steering = steering,
        createdAt = createdAt,
        searchSuggestions = searchSuggestions?.takeIf { !it.isEmpty },
    )
}
