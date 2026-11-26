package atom.core.legacy

import atom.core.llm.ChatMessage
import atom.core.llm.ContentPart
import atom.core.llm.ReasoningEffort
import atom.core.llm.Role
import atom.core.llm.ToolCall
import java.io.InputStream
import java.util.UUID
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeToSequence
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One entry of an imported conversation, in the shape Atom 4 stores (the app adds its own metadata). */
sealed interface ImportedEntry {
    val createdAt: Long

    data class Message(
        val message: ChatMessage,
        val reasoning: String? = null,
        val reasoningMs: Long? = null,
        val edited: Boolean = false,
        val isError: Boolean = false,
        val imagePaths: List<String> = emptyList(),
        override val createdAt: Long = 0,
    ) : ImportedEntry

    /** A timeline note; [code] is the old notice code (`stopped`, `interrupted`, ...). Never sent to the model. */
    data class Notice(val code: String, val detail: String?, val isError: Boolean, override val createdAt: Long = 0) : ImportedEntry
}

data class ImportedConversation(
    val sourceId: String,
    val title: String,
    /** True when the old chat was untitled and [title] was derived from its first message. */
    val titleIsPlaceholder: Boolean,
    /** Set for character chats; Atom 4 cannot bind them to its own characters, so they import as normal chats. */
    val characterName: String?,
    val isCharacterChat: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean,
    /** The old `provider_models.id`; the caller maps it to an Atom 4 provider and model, or drops it. */
    val modelRowId: String?,
    val reasoningEnabled: Boolean?,
    val reasoningEffort: ReasoningEffort?,
    val entries: List<ImportedEntry>,
    /** Rows whose text could not be restored from chunks (imported empty). */
    val damagedRows: Int = 0,
    /** Entries cut to [LegacyConversations.MAX_MESSAGE_CHARS]. */
    val truncatedEntries: Int = 0,
    /** Tool cards the transcript had no entry for; they keep only the timeline's short previews. */
    val toolRowsWithoutDetails: Int = 0,
)

/**
 * Rebuilds a conversation from the old visible timeline (`conversation_messages`, ordered by `sort_index`),
 * enriched with the checkpoint journal for full tool arguments, results and reasoning. Rows carry no timestamps, so
 * entries get increasing times spread between the conversation's creation and last update.
 */
object LegacyConversations {
    /** About 2 MB of UTF-16; longer single values are cut with a note. */
    const val MAX_MESSAGE_CHARS = 1_000_000
    const val UNTITLED_LEGACY = "New conversation"
    const val IMAGE_PLACEHOLDER = "[image]"
    const val TRUNCATED_NOTE = "\n\n[truncated during import]"
    const val NO_RESULT = "[result not kept by the previous app]"

    val NOTICE_CODES = setOf("stopped", "empty_result", "runtime_failed", "model_retry", "context_compaction", "interrupted")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun map(
        row: LegacyConversationRow,
        messages: List<LegacyMessageRow>,
        journal: LegacyJournal = LegacyJournal.EMPTY,
        maxChars: Int = MAX_MESSAGE_CHARS,
    ): ImportedConversation {
        val builder = Builder(journal, maxChars)
        messages.sortedBy { it.sortIndex }.forEach(builder::row)
        builder.finish()
        val entries = builder.entries
        val times = timestamps(row.createdAt, row.updatedAt, entries.size)
        val timed = entries.mapIndexed { i, e ->
            when (e) {
                is ImportedEntry.Message -> e.copy(createdAt = times[i])
                is ImportedEntry.Notice -> e.copy(createdAt = times[i])
            }
        }
        val character = row.roleplayJson.isNotBlank()
        val characterName = if (character) characterName(row.roleplayJson) else null
        val raw = row.title.trim()
        val untitled = raw.isEmpty() || raw == UNTITLED_LEGACY
        val firstUser = timed.firstNotNullOfOrNull { (it as? ImportedEntry.Message)?.message?.takeIf { m -> m.role == Role.User }?.text }
        val title = when {
            !untitled -> raw
            characterName != null -> characterName
            else -> titleFrom(firstUser.orEmpty())
        }
        val effort = LegacyEffort.parse(row.reasoningEffort)
        return ImportedConversation(
            sourceId = row.id,
            title = title,
            titleIsPlaceholder = untitled && characterName == null,
            characterName = characterName,
            isCharacterChat = character,
            createdAt = row.createdAt.takeIf { it > 0 } ?: times.firstOrNull() ?: row.updatedAt,
            updatedAt = maxOf(row.updatedAt, times.lastOrNull() ?: 0L),
            pinned = row.pinned,
            modelRowId = row.modelRowId?.takeIf { it.isNotBlank() },
            reasoningEnabled = effort != LegacyEffort.Off,
            reasoningEffort = effort.effort?.takeIf { effort != LegacyEffort.Off },
            entries = timed,
            damagedRows = messages.count { it.damaged },
            truncatedEntries = builder.truncated,
            toolRowsWithoutDetails = builder.withoutDetails,
        )
    }

    /**
     * [n] strictly increasing times from [createdAt] to [updatedAt] (spread evenly; one millisecond apart when the
     * span is too short). A missing creation time falls back to the update time.
     */
    fun timestamps(createdAt: Long, updatedAt: Long, n: Int): List<Long> {
        if (n <= 0) return emptyList()
        val start = if (createdAt > 0) createdAt else maxOf(updatedAt, 0L)
        val end = maxOf(updatedAt, start)
        val span = end - start
        val out = ArrayList<Long>(n)
        for (i in 0 until n) {
            val even = if (n == 1) start else start + span * i / (n - 1)
            out += if (i == 0) even else maxOf(even, out[i - 1] + 1)
        }
        return out
    }

    /** The first non-blank line, at most 48 characters, as Atom 4 titles new chats. */
    fun titleFrom(text: String): String {
        val line = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (line.length <= 48) line else line.take(47).trimEnd() + "…"
    }

    /** `characterName` from a resolved `roleplay_json`, or null when it is unreadable or blank. */
    fun characterName(roleplayJson: String): String? {
        val o = runCatching { json.parseToJsonElement(roleplayJson) }.getOrNull() as? JsonObject ?: return null
        return (o["characterName"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** The image strings of a resolved `images_json` (normally `data:image/...;base64,...` URLs); blanks dropped. */
    fun images(imagesJson: String): List<String> {
        if (imagesJson.isBlank()) return emptyList()
        val out = ArrayList<String>()
        images(imagesJson.byteInputStream(Charsets.UTF_8)) { out += it }
        return out
    }

    /**
     * Streams the image strings of an `images_json` value to [each], one at a time, so only one image is in memory.
     * Returns false when the value is not a readable JSON array of strings (images before the damage are kept).
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun images(input: InputStream, each: (String) -> Unit): Boolean = try {
        json.decodeToSequence(input, JsonElement.serializer(), DecodeSequenceMode.ARRAY_WRAPPED).forEach { element ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }?.let(each)
        }
        true
    } catch (e: SerializationException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    } catch (e: IllegalStateException) {
        false
    }

    private class PendingTool(val call: ToolCall, val result: String?, val isError: Boolean)

    private class Builder(private val journal: LegacyJournal, private val maxChars: Int) {
        val entries = ArrayList<ImportedEntry>()
        var truncated = 0
        var withoutDetails = 0
        private var toolRound: String? = null
        private val reasoning = StringBuilder()
        private var reasoningMs: Long? = null
        private val tools = ArrayList<PendingTool>()
        private val usedIds = HashSet<String>()

        fun row(r: LegacyMessageRow) {
            when (r.type) {
                LegacyMessageRow.USER -> {
                    flushTools()
                    flushReasoning()
                    val notes = List(r.droppedImages) { IMAGE_PLACEHOLDER }
                    val text = (listOf(r.content).filter { it.isNotEmpty() } + notes).joinToString("\n")
                    entries += ImportedEntry.Message(ChatMessage.user(cap(text)), edited = r.edited, imagePaths = r.imagePaths)
                }
                LegacyMessageRow.THINKING -> {
                    // A thinking card after tool cards starts the next model round.
                    flushTools()
                    if (r.content.isNotBlank()) {
                        if (reasoning.isNotEmpty()) reasoning.append("\n\n")
                        reasoning.append(r.content.trim())
                    }
                    r.elapsedSeconds?.takeIf { it >= 0 }?.let { reasoningMs = (reasoningMs ?: 0L) + it * 1000 }
                }
                LegacyMessageRow.TOOL -> {
                    // `<runId>-tool-<round>-<callId>`: calls of a later round are a separate model turn.
                    val round = TOOL_ROUND.find(r.id)?.groupValues?.get(1)
                    if (tools.isNotEmpty() && round != null && toolRound != null && round != toolRound) flushTools()
                    toolRound = round
                    tools += tool(r)
                }
                LegacyMessageRow.ASSISTANT -> {
                    flushTools()
                    val thought = takeReasoning() ?: journal.reasoningFor(r.id)?.let { cap(it.trim()) to null }
                    if (r.content.isBlank() && thought == null) return
                    entries += ImportedEntry.Message(
                        ChatMessage.assistant(cap(r.content)),
                        reasoning = thought?.first,
                        reasoningMs = thought?.second,
                    )
                }
                LegacyMessageRow.SYSTEM_NOTICE -> {
                    val code = r.content.trim()
                    if (code !in NOTICE_CODES) return
                    flushTools()
                    flushReasoning()
                    entries += ImportedEntry.Notice(code, r.resultSummary?.takeIf { it.isNotBlank() }, isError = code == "runtime_failed")
                }
                // tool_summary is a decorative card; unknown types are skipped as the old app did.
                else -> Unit
            }
        }

        fun finish() {
            flushTools()
            flushReasoning()
        }

        private fun tool(r: LegacyMessageRow): PendingTool {
            val callId = journal.callIdFor(r.id)
            val fromJournal = callId?.let { journal.calls[it] }
            if (fromJournal == null) withoutDetails++
            val id = uniqueId(callId ?: syntheticId(r.id))
            val name = r.toolName?.takeIf { it.isNotBlank() } ?: fromJournal?.name ?: "tool"
            val args = fromJournal?.argumentsJson?.takeIf { it.isNotBlank() } ?: buildJsonObject {
                if (r.content.isNotBlank()) put("command", r.content)
                r.argumentsSummary?.takeIf { it.isNotBlank() }?.let { put("summary", it) }
            }.toString()
            val status = r.toolStatus?.trim()
            val result = callId?.let { journal.results[it] }
                ?: r.resultSummary?.takeIf { it.isNotBlank() }
                ?: if (status == "Success" || status == "Failed") NO_RESULT else null
            // Cutting JSON would make it invalid, so over-long arguments are replaced by a marker object.
            val kept = if (args.length <= maxChars) args else buildJsonObject { put("truncated", true) }.toString().also { truncated++ }
            return PendingTool(ToolCall(id, name, kept), result?.let(::cap), isError = status == "Failed")
        }

        /** The model turn that made the pending calls, then one result per call that has one. */
        private fun flushTools() {
            if (tools.isEmpty()) return
            val thought = takeReasoning()
            entries += ImportedEntry.Message(
                ChatMessage.assistant("", tools.map { it.call }),
                reasoning = thought?.first,
                reasoningMs = thought?.second,
            )
            for (t in tools) {
                val result = t.result ?: continue
                entries += ImportedEntry.Message(
                    ChatMessage(Role.Tool, listOf(ContentPart.Text(result)), toolCallId = t.call.id, toolName = t.call.name),
                    isError = t.isError,
                )
            }
            tools.clear()
        }

        /** Reasoning with no reply after it (a stopped run) keeps its card on an empty model turn. */
        private fun flushReasoning() {
            val thought = takeReasoning() ?: return
            entries += ImportedEntry.Message(ChatMessage.assistant(""), reasoning = thought.first, reasoningMs = thought.second)
        }

        private fun takeReasoning(): Pair<String, Long?>? {
            if (reasoning.isEmpty()) {
                reasoningMs = null
                return null
            }
            val out = cap(reasoning.toString()) to reasoningMs
            reasoning.setLength(0)
            reasoningMs = null
            return out
        }

        private fun uniqueId(base: String): String {
            var id = base
            var n = 2
            while (!usedIds.add(id)) id = "${base}_${n++}"
            return id
        }

        private fun cap(text: String): String {
            if (text.length <= maxChars) return text
            truncated++
            var end = maxChars
            if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
            return text.substring(0, end) + TRUNCATED_NOTE
        }
    }

    private val TOOL_ROUND = Regex("-tool-(\\d+)-")

    /** A provider-neutral call id for a row the journal does not know (`call_` + 24 hex characters). */
    fun syntheticId(rowId: String): String =
        "call_" + UUID.nameUUIDFromBytes(rowId.toByteArray(Charsets.UTF_8)).toString().replace("-", "").take(24)
}
