package atom.app.data.legacy

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import atom.core.io.deleteTreeNoFollow
import atom.core.legacy.LegacyChunks
import atom.core.legacy.LegacyConversationRow
import atom.core.legacy.LegacyConversations
import atom.core.legacy.LegacyFiles
import atom.core.legacy.LegacyJournal
import atom.core.legacy.LegacyMessageRow
import atom.core.legacy.LegacyModelRow
import atom.core.legacy.LegacyProviderRow
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * The previous app's Room database (`databases/atom.db`), read with plain SQLite: never through a Room definition
 * (Room would see a foreign schema and could wipe it) and never written. The database, its WAL and shared-memory
 * files are copied into `cache/atom4_import/` and the copy is opened, so committed WAL frames are seen while the
 * originals stay untouched. The copy is deleted on [close]. Columns are read by name; a missing one takes the old
 * schema's default.
 */
internal class LegacyDatabase private constructor(private val db: SQLiteDatabase, private val copyDir: File?) : Closeable {

    /** `PRAGMA user_version`; 30 on every release of the previous app. */
    val version: Int get() = db.version

    private val tables: Set<String> by lazy {
        db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }
    }

    fun hasTable(name: String): Boolean = name in tables

    fun providers(): List<LegacyProviderRow> {
        if (!hasTable("model_providers")) return emptyList()
        return db.rawQuery("SELECT * FROM model_providers", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.str("id")
                    if (id.isEmpty()) continue
                    add(
                        LegacyProviderRow(
                            id = id,
                            type = c.str("type", LegacyProviderRow.TYPE_OPENAI),
                            name = c.str("name"),
                            baseUrl = c.str("base_url"),
                            apiKey = c.str("api_key"),
                            enabled = c.bool("is_enabled", true),
                            builtIn = c.bool("is_built_in", false),
                            sortOrder = c.int("sort_order", 0),
                            systemPrompt = c.strOrNull("system_prompt"),
                            customHeadersJson = c.str("custom_headers_json", "[]"),
                            customBodyJson = c.str("custom_body_json", "[]"),
                            createdAt = c.long("created_at", 0),
                            endpointMode = c.str("endpoint_mode"),
                            hostedWebSearch = c.bool("hosted_web_search_enabled", false),
                            anthropicVersion = c.str("anthropic_version", "2023-06-01"),
                            authMode = c.str("auth_mode", LegacyProviderRow.AUTH_API_KEY),
                            vertexProject = c.str("vertex_project"),
                            vertexLocation = c.str("vertex_location", "global"),
                        ),
                    )
                }
            }
        }
    }

    fun models(): List<LegacyModelRow> {
        if (!hasTable("provider_models")) return emptyList()
        return db.rawQuery("SELECT * FROM provider_models", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.str("id")
                    if (id.isEmpty()) continue
                    add(
                        LegacyModelRow(
                            id = id,
                            providerId = c.str("provider_id"),
                            modelId = c.str("model_id"),
                            displayName = c.str("display_name"),
                            enabled = c.bool("is_enabled", true),
                            builtIn = c.bool("is_built_in", false),
                            sortOrder = c.int("sort_order", 0),
                            contextWindow = c.intOrNull("context_window"),
                            contextWindowOverride = c.intOrNull("context_window_override"),
                            inputModalitiesJson = c.str("input_modalities_json"),
                            attachment = c.intOrNull("attachment"),
                            reasoning = c.intOrNull("reasoning"),
                            reasoningCapabilitiesJson = c.str("reasoning_capabilities_json", "null"),
                            reasoningOverride = c.intOrNull("reasoning_override"),
                            reasoningCapabilitiesOverrideJson = c.str("reasoning_capabilities_override_json", "null"),
                            source = c.str("source", "manual"),
                        ),
                    )
                }
            }
        }
    }

    /** Conversation ids, most recently updated first (the old list order). */
    fun conversationIds(): List<String> {
        if (!hasTable("conversations")) return emptyList()
        return db.rawQuery("SELECT id FROM conversations ORDER BY updated_at DESC, id ASC", null).use { c ->
            buildList { while (c.moveToNext()) c.getString(0)?.takeIf { it.isNotEmpty() }?.let(::add) }
        }
    }

    fun conversation(id: String): LegacyConversationRow? =
        db.rawQuery("SELECT * FROM conversations WHERE id = ?", arrayOf(id)).use { c ->
            if (!c.moveToFirst()) return@use null
            val roleplayRaw = c.str("roleplay_json")
            // Only the character's name is needed; a damaged or huge value still marks a character chat.
            val roleplay = if (roleplayRaw.isEmpty()) "" else resolve("conversations", id, "roleplay", roleplayRaw, ROLEPLAY_MAX).text.ifEmpty { "{}" }
            LegacyConversationRow(
                id = id,
                title = c.str("title"),
                reasoningEffort = c.str("reasoning_effort", "default"),
                roleplayJson = roleplay,
                createdAt = c.long("created_at", 0),
                updatedAt = c.long("updated_at", 0),
                modelRowId = c.strOrNull("model_id"),
                pinned = c.bool("is_pinned", false),
            )
        }

    /**
     * The visible rows of a conversation in `sort_index` order, read in pages of [PAGE] so memory stays bounded.
     * Chunked text is resolved (at most [maxChars] per value); [saveImages] turns a user row's image strings into
     * local copies and returns (paths, how many could not be kept).
     */
    fun messages(conversationId: String, maxChars: Int, saveImage: (String) -> String?): List<LegacyMessageRow> {
        if (!hasTable("conversation_messages")) return emptyList()
        val out = ArrayList<LegacyMessageRow>()
        var after = Long.MIN_VALUE
        while (true) {
            val page = db.rawQuery(
                "SELECT * FROM conversation_messages WHERE conversation_id = ? AND sort_index > $after ORDER BY sort_index LIMIT $PAGE",
                arrayOf(conversationId),
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            Raw(
                                id = c.str("id"),
                                sortIndex = c.long("sort_index", 0),
                                type = c.str("type"),
                                content = c.str("content"),
                                images = c.str("images_json"),
                                edited = c.bool("is_edited", false),
                                elapsedSeconds = c.longOrNull("elapsed_seconds"),
                                toolName = c.strOrNull("tool_name"),
                                toolStatus = c.strOrNull("tool_status"),
                                argumentsSummary = c.strOrNull("arguments_summary"),
                                resultSummary = c.strOrNull("result_summary"),
                            ),
                        )
                    }
                }
            }
            if (page.isEmpty()) break
            for (raw in page) {
                // One unit over the limit, so the mapper sees the value is longer and cuts it with a note.
                val content = LegacyChunks.resolvePrefix(raw.content, maxChars + 1) { n -> chunks("conversation_messages", raw.id, "content", n) }
                val paths = ArrayList<String>()
                var dropped = 0
                if (raw.type == LegacyMessageRow.USER && raw.images.isNotBlank()) {
                    // Streamed and saved one image at a time, so only one is ever in memory.
                    val read = stream("conversation_messages", raw.id, "images", raw.images) { input ->
                        LegacyConversations.images(input) { image ->
                            val path = saveImage(image)
                            if (path != null) paths += path else dropped++
                        }
                    }
                    if (read != true) dropped++
                }
                out += LegacyMessageRow(
                    id = raw.id,
                    sortIndex = raw.sortIndex,
                    type = raw.type,
                    content = content.text,
                    imagePaths = paths,
                    droppedImages = dropped,
                    edited = raw.edited,
                    elapsedSeconds = raw.elapsedSeconds,
                    toolName = raw.toolName,
                    toolStatus = raw.toolStatus,
                    argumentsSummary = raw.argumentsSummary,
                    resultSummary = raw.resultSummary,
                    damaged = content is LegacyChunks.Resolved.Corrupt,
                )
            }
            if (page.size < PAGE) break
            after = page.last().sortIndex
        }
        return out
    }

    /**
     * The checkpoint transcript (`journal_json`, or `history_json` when the journal is empty), parsed as a stream so a
     * transcript of any size fits; results are cut to [maxResultChars]. Empty when there is no checkpoint or its
     * chunks are damaged.
     */
    fun journal(conversationId: String, maxResultChars: Int): LegacyJournal {
        if (!hasTable("conversation_context_checkpoints")) return LegacyJournal.EMPTY
        val chosen = db.rawQuery("SELECT * FROM conversation_context_checkpoints WHERE conversation_id = ?", arrayOf(conversationId)).use { c ->
            if (!c.moveToFirst()) {
                null
            } else {
                val journal = c.str("journal_json")
                if (journal.isNotBlank()) "journal" to journal else "history" to c.str("history_json")
            }
        } ?: return LegacyJournal.EMPTY
        if (chosen.second.isBlank()) return LegacyJournal.EMPTY
        return stream("conversation_context_checkpoints", conversationId, chosen.first, chosen.second) { input ->
            LegacyJournal.parse(input, maxResultChars)
        } ?: LegacyJournal.EMPTY
    }

    /**
     * Reads a possibly chunked value as a UTF-8 stream, one chunk in memory at a time. Null when the reference is
     * malformed or its chunks are incomplete.
     */
    private fun <T> stream(ownerTable: String, ownerId: String, field: String, value: String, block: (InputStream) -> T): T? {
        if (!LegacyChunks.isRef(value)) return block(value.byteInputStream(Charsets.UTF_8))
        val ref = LegacyChunks.parseRef(value) ?: return null
        if (!hasTable(CHUNKS)) return null
        val args = arrayOf(ownerTable, ownerId, field)
        val indexes = db.rawQuery("SELECT chunk_index FROM $CHUNKS WHERE owner_table = ? AND owner_id = ? AND field = ?", args).use { c ->
            buildList { while (c.moveToNext()) add(c.getInt(0)) }
        }
        if (LegacyChunks.indexProblem(ref, indexes) != null) return null
        return db.rawQuery("SELECT content FROM $CHUNKS WHERE owner_table = ? AND owner_id = ? AND field = ? ORDER BY chunk_index", args).use { c ->
            val pieces = generateSequence { if (c.moveToNext()) c.getString(0).orEmpty() else null }.iterator()
            block(LegacyChunks.utf8Stream(pieces))
        }
    }

    private fun resolve(ownerTable: String, ownerId: String, field: String, value: String, maxChars: Int): LegacyChunks.Resolved =
        LegacyChunks.resolve(value, maxChars) { chunks(ownerTable, ownerId, field, Int.MAX_VALUE) }

    /** The chunk rows of one value with `chunk_index` below [below]. */
    private fun chunks(ownerTable: String, ownerId: String, field: String, below: Int): List<LegacyChunks.Chunk> {
        if (!hasTable(CHUNKS)) return emptyList()
        return db.rawQuery(
            "SELECT chunk_index, content FROM $CHUNKS WHERE owner_table = ? AND owner_id = ? AND field = ? AND chunk_index < $below",
            arrayOf(ownerTable, ownerId, field),
        ).use { c -> buildList { while (c.moveToNext()) add(LegacyChunks.Chunk(c.getInt(0), c.getString(1).orEmpty())) } }
    }

    override fun close() {
        runCatching { db.close() }
        copyDir?.let { runCatching { it.deleteTreeNoFollow() } }
    }

    private class Raw(
        val id: String,
        val sortIndex: Long,
        val type: String,
        val content: String,
        val images: String,
        val edited: Boolean,
        val elapsedSeconds: Long?,
        val toolName: String?,
        val toolStatus: String?,
        val argumentsSummary: String?,
        val resultSummary: String?,
    )

    /** Too little free space for a temporary copy, and the original cannot be read without touching its files. */
    class NotEnoughSpaceException : IOException("not enough free space")

    companion object {
        private const val PAGE = 128
        private const val ROLEPLAY_MAX = 1_000_000
        private const val CHUNKS = "agent_text_chunks"
        private const val SPARE_BYTES = 32L * 1024 * 1024

        /** The database, its WAL, its WAL index and its rollback journal (a kill mid-write leaves a hot journal). */
        private val SUFFIXES = listOf("", "-wal", "-shm", "-journal")

        fun source(context: Context): File = context.getDatabasePath(LegacyFiles.DATABASE)

        /** Where temporary copies go; removed after every run. */
        fun tempRoot(context: Context): File = File(context.cacheDir, "atom4_import")

        /** Null when the previous app's database is not on this device. */
        fun open(context: Context): LegacyDatabase? {
            val source = source(context)
            if (!source.isFile) return null
            val files = SUFFIXES.map { File(source.path + it) }.filter { it.isFile }
            val needed = files.sumOf { it.length() }
            if (context.cacheDir.usableSpace >= needed + SPARE_BYTES) {
                val dir = File(tempRoot(context), UUID.randomUUID().toString())
                try {
                    if (!dir.mkdirs()) throw IOException("cannot create a temporary folder")
                    for (file in files) file.inputStream().use { input -> File(dir, file.name).outputStream().use { input.copyTo(it) } }
                    // The copy is ours, so it is opened read-write: SQLite then applies a copied WAL or rolls back a
                    // copied hot journal on the copy, and can build a WAL index there. The originals are never opened.
                    val copy = File(dir, source.name).path
                    return LegacyDatabase(SQLiteDatabase.openDatabase(copy, null, SQLiteDatabase.OPEN_READWRITE), dir)
                } catch (e: Exception) {
                    runCatching { dir.deleteTreeNoFollow() }
                    throw e
                }
            }
            // No room for a copy. A read-only connection to a rollback-journal database with no hot journal never
            // writes or creates a file, so it may be read in place. Anything else (a WAL file of any size, a WAL-mode
            // header, a non-empty journal) could make SQLite create or rewrite a file next to it, so the import stops.
            val wal = File(source.path + "-wal").exists()
            val journal = File(source.path + "-journal").let { it.isFile && it.length() > 0 }
            if (wal || journal || isWalMode(source)) throw NotEnoughSpaceException()
            return LegacyDatabase(SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READONLY), null)
        }

        /** The SQLite header's file format bytes (offsets 18 and 19) are 2 in WAL mode. Unreadable counts as WAL. */
        private fun isWalMode(file: File): Boolean = runCatching {
            val header = ByteArray(20)
            val n = file.inputStream().use { it.read(header) }
            n < 20 || header[18].toInt() == 2 || header[19].toInt() == 2
        }.getOrDefault(true)
    }
}

private fun Cursor.index(name: String): Int = getColumnIndex(name)

private fun Cursor.strOrNull(name: String): String? {
    val i = index(name)
    return if (i < 0 || isNull(i)) null else getString(i)
}

private fun Cursor.str(name: String, default: String = ""): String = strOrNull(name) ?: default

private fun Cursor.longOrNull(name: String): Long? {
    val i = index(name)
    return if (i < 0 || isNull(i)) null else getLong(i)
}

private fun Cursor.long(name: String, default: Long): Long = longOrNull(name) ?: default

private fun Cursor.intOrNull(name: String): Int? = longOrNull(name)?.toInt()

private fun Cursor.int(name: String, default: Int): Int = intOrNull(name) ?: default

private fun Cursor.bool(name: String, default: Boolean): Boolean = longOrNull(name)?.let { it != 0L } ?: default
