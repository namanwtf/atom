package atom.app.data

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A small append-only checkpoint per running conversation: one JSON line per finished step, so a crash or a kill
 * loses at most the step in flight. The full conversation file is written once when the run ends and the journal
 * is deleted. Leftover journals are folded back in on the next start and the run is marked interrupted.
 */
class RunJournal(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Serializable
    internal data class Record(
        val op: String,
        val base: Int? = null,
        val at: Int? = null,
        val message: MessageDto? = null,
    )

    fun begin(conversationId: String, baseCount: Int) = write(conversationId, Record(OP_BEGIN, base = baseCount), append = false)

    fun append(conversationId: String, message: StoredMessage) = write(conversationId, Record(OP_APPEND, message = message.toDto()))

    fun insert(conversationId: String, at: Int, message: StoredMessage) =
        write(conversationId, Record(OP_INSERT, at = at, message = message.toDto()))

    fun finish(conversationId: String) {
        fileFor(conversationId)?.delete()
    }

    fun pending(): List<String> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }.orEmpty().map { it.name.removeSuffix(SUFFIX) }

    /** Replays the journal onto [saved] (the conversation as written when the run began). */
    suspend fun replay(conversationId: String, saved: Conversation): Conversation? = withContext(Dispatchers.IO) {
        val file = fileFor(conversationId)?.takeIf { it.isFile } ?: return@withContext null
        val records = file.readLines().mapNotNull { line ->
            runCatching { json.decodeFromString(Record.serializer(), line) }.getOrNull()
        }
        val base = records.firstOrNull { it.op == OP_BEGIN }?.base ?: saved.messages.size
        val messages = saved.messages.take(base).toMutableList()
        for (r in records) {
            val m = r.message?.toModel() ?: continue
            when (r.op) {
                OP_APPEND -> messages += m
                OP_INSERT -> messages.add((r.at ?: messages.size).coerceIn(0, messages.size), m)
            }
        }
        saved.copy(messages = messages)
    }

    private fun write(conversationId: String, record: Record, append: Boolean = true) {
        val file = fileFor(conversationId) ?: return
        runCatching {
            dir.mkdirs()
            val line = json.encodeToString(Record.serializer(), record) + "\n"
            if (append) file.appendText(line) else file.writeText(line)
        }
    }

    private fun fileFor(id: String): File? = if (ConversationStore.isValidId(id)) File(dir, id + SUFFIX) else null

    private companion object {
        const val SUFFIX = ".jsonl"
        const val OP_BEGIN = "begin"
        const val OP_APPEND = "append"
        const val OP_INSERT = "insert"
    }
}
