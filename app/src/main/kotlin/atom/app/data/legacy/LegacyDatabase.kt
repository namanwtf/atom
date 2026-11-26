package atom.app.data.legacy

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import atom.core.io.deleteTreeNoFollow
import atom.core.legacy.LegacyChunks
import atom.core.legacy.LegacyConversationRow
import atom.core.legacy.LegacyConversations
import atom.core.legacy.LegacyFiles
import atom.core.legacy.LegacyMessageRow
import atom.core.legacy.LegacyModelRow
import atom.core.legacy.LegacyProviderRow
import java.io.Closeable
import java.io.File
import java.io.IOException
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
    fun messages(conversationId: String, maxChars: Int, saveImages: (List<String>) -> Pair<List<String>, Int>): List<LegacyMessageRow> {
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
                val content = resolve("conversation_messages", raw.id, "content", raw.content, maxChars)
                val (paths, dropped) = if (raw.type == LegacyMessageRow.USER && raw.images.isNotBlank()) {
                    val images = resolve("conversation_messages", raw.id, "images", raw.images, IMAGES_MAX)
                    val list = LegacyConversations.images(images.text)
                    if (images is LegacyChunks.Resolved.Ok) saveImages(list) else emptyList<String>() to 1
                } else {
                    emptyList<String>() to 0
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

    /** The checkpoint transcript: `journal_json`, or `history_json` when the journal is empty. Null when absent or too large. */
    fun journal(conversationId: String, maxChars: Int): String? {
        if (!hasTable("conversation_context_checkpoints")) return null
        return db.rawQuery("SELECT * FROM conversation_context_checkpoints WHERE conversation_id = ?", arrayOf(conversationId)).use { c ->
            if (!c.moveToFirst()) return@use null
            val journal = c.str("journal_json")
            val history = c.str("history_json")
            val (field, value) = if (journal.isNotBlank()) "journal" to journal else "history" to history
            val resolved = resolve("conversation_context_checkpoints", conversationId, field, value, maxChars)
            (resolved as? LegacyChunks.Resolved.Ok)?.text?.takeIf { it.isNotBlank() }
        }
    }

    private fun resolve(ownerTable: String, ownerId: String, field: String, value: String, maxChars: Int): LegacyChunks.Resolved =
        LegacyChunks.resolve(value, maxChars) { chunks(ownerTable, ownerId, field) }

    private fun chunks(ownerTable: String, ownerId: String, field: String): List<LegacyChunks.Chunk> {
        if (!hasTable("agent_text_chunks")) return emptyList()
        return db.rawQuery(
            "SELECT chunk_index, content FROM agent_text_chunks WHERE owner_table = ? AND owner_id = ? AND field = ?",
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

    class NotEnoughSpaceException : IOException("not enough free space to read the previous app's database")

    companion object {
        private const val PAGE = 128
        private const val ROLEPLAY_MAX = 1_000_000
        private const val IMAGES_MAX = 30_000_000
        private const val SPARE_BYTES = 32L * 1024 * 1024
        private val SUFFIXES = listOf("", "-wal", "-shm")

        fun source(context: Context): File = context.getDatabasePath(LegacyFiles.DATABASE)

        /** Where temporary copies go; removed after every run. */
        fun tempRoot(context: Context): File = File(context.cacheDir, "atom4_import")

        /** Null when the previous app's database is not on this device. */
        fun open(context: Context): LegacyDatabase? {
            val source = source(context)
            if (!source.isFile) return null
            val files = SUFFIXES.map { File(source.path + it) }.filter { it.isFile }
            val hasWal = File(source.path + "-wal").let { it.isFile && it.length() > 0 }
            val needed = files.sumOf { it.length() }
            val root = tempRoot(context)
            if (context.cacheDir.usableSpace >= needed + SPARE_BYTES) {
                val dir = File(root, UUID.randomUUID().toString())
                try {
                    if (!dir.mkdirs()) throw IOException("cannot create a temporary folder")
                    for (file in files) file.inputStream().use { input -> File(dir, file.name).outputStream().use { input.copyTo(it) } }
                    // The copy is ours: read-write lets SQLite apply the copied WAL; without one it is opened read-only.
                    val flags = if (hasWal) SQLiteDatabase.OPEN_READWRITE else SQLiteDatabase.OPEN_READONLY
                    return LegacyDatabase(SQLiteDatabase.openDatabase(File(dir, source.name).path, null, flags), dir)
                } catch (e: Exception) {
                    runCatching { dir.deleteTreeNoFollow() }
                    throw e
                }
            }
            // No room for a copy. Without a WAL a read-only connection never writes, so the original can be read in
            // place; with one, reading in place could touch its shared-memory file, so the import stops instead.
            if (hasWal) throw NotEnoughSpaceException()
            return LegacyDatabase(SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READONLY), null)
        }
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
