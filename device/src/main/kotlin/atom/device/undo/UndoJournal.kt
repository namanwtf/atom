package atom.device.undo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * One state change a tool made. [kind] picks the [Restorer]; [undoData] is whatever it needs to put the before-state
 * back (null when the change cannot be undone, e.g. a force-stop).
 */
@Serializable
data class JournalEntry(
    val id: Long,
    val atMillis: Long,
    val tool: String,
    val label: String,
    val before: String,
    val after: String,
    val kind: String,
    val undoData: String? = null,
    val verified: Boolean = true,
    val undoneAtMillis: Long? = null,
    val undoNote: String? = null,
) {
    val undoable: Boolean get() = undoData != null && undoneAtMillis == null
    val summary: String get() = "$label: $before → $after"
}

data class UndoResult(val ok: Boolean, val message: String)

fun interface Restorer {
    suspend fun restore(entry: JournalEntry): UndoResult
}

interface JournalStore {
    fun load(): List<JournalEntry>
    fun save(entries: List<JournalEntry>)
}

class FileJournalStore(private val file: File) : JournalStore {
    private val json = Json { ignoreUnknownKeys = true }

    override fun load(): List<JournalEntry> = try {
        if (file.exists()) json.decodeFromString<List<JournalEntry>>(file.readText()) else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    override fun save(entries: List<JournalEntry>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(entries))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }
}

/**
 * The undo journal: every state change any tool makes, newest last. Pruned on insert (no timers): at most
 * [maxEntries], nothing older than [maxAgeMillis].
 */
class UndoJournal(
    private val store: JournalStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val maxEntries: Int = 500,
    private val maxAgeMillis: Long = 30L * 24 * 3600 * 1000,
) {
    private val mutex = Mutex()
    private val restorers = HashMap<String, Restorer>()
    private val _entries = MutableStateFlow(store.load())
    val entries: StateFlow<List<JournalEntry>> = _entries.asStateFlow()

    fun register(kindPrefix: String, restorer: Restorer) {
        synchronized(restorers) { restorers[kindPrefix] = restorer }
    }

    private fun restorerFor(kind: String): Restorer? {
        val local = synchronized(restorers) { pick(restorers, kind) }
        return local ?: synchronized(Undo.restorers) { pick(Undo.restorers, kind) }
    }

    private fun pick(map: Map<String, Restorer>, kind: String): Restorer? =
        map[kind] ?: map.entries.filter { kind.startsWith(it.key) }.maxByOrNull { it.key.length }?.value

    suspend fun record(
        tool: String,
        label: String,
        before: String,
        after: String,
        kind: String,
        undoData: String?,
        verified: Boolean = true,
    ): JournalEntry = mutex.withLock {
        val t = now()
        val list = _entries.value
        val entry = JournalEntry((list.maxOfOrNull { it.id } ?: 0) + 1, t, tool, label, before, after, kind, undoData, verified)
        val pruned = (list + entry).filter { t - it.atMillis <= maxAgeMillis }.takeLast(maxEntries)
        commit(pruned)
        entry
    }

    fun lastUndoable(): JournalEntry? = _entries.value.lastOrNull { it.undoable }

    fun today(): List<JournalEntry> {
        val z = zone()
        val day = Instant.ofEpochMilli(now()).atZone(z).toLocalDate()
        return _entries.value.filter { Instant.ofEpochMilli(it.atMillis).atZone(z).toLocalDate() == day }
    }

    suspend fun undo(id: Long): UndoResult {
        val entry = _entries.value.firstOrNull { it.id == id } ?: return UndoResult(false, "No change #$id in the journal.")
        if (entry.undoneAtMillis != null) return UndoResult(false, "\"${entry.summary}\" was already undone.")
        val data = entry.undoData ?: return UndoResult(false, "\"${entry.summary}\" cannot be undone automatically.")
        val restorer = restorerFor(entry.kind) ?: return UndoResult(false, "Nothing knows how to undo ${entry.kind} on this build.")
        val result = try {
            restorer.restore(entry.copy(undoData = data))
        } catch (e: Exception) {
            UndoResult(false, "Undo failed: ${e.message ?: e::class.simpleName}")
        }
        if (result.ok) {
            mutex.withLock {
                commit(_entries.value.map { if (it.id == id) it.copy(undoneAtMillis = now(), undoNote = result.message) else it })
            }
        }
        return result
    }

    /** Undoes today's changes newest first, so a setting changed twice ends at its morning value. */
    suspend fun undoToday(): List<Pair<JournalEntry, UndoResult>> =
        today().filter { it.undoable }.sortedByDescending { it.id }.map { it to undo(it.id) }

    suspend fun clear() = mutex.withLock { commit(emptyList()) }

    private fun commit(list: List<JournalEntry>) {
        _entries.value = list
        runCatching { store.save(list) }
    }
}

/** Process-wide journal, created on first use. */
object Undo {
    @Volatile
    private var instance: UndoJournal? = null

    internal val restorers = HashMap<String, Restorer>()

    /** Registers how to undo journal entries whose kind starts with [kindPrefix]; cheap, safe from onAppCreate. */
    fun register(kindPrefix: String, restorer: Restorer) {
        synchronized(restorers) { restorers[kindPrefix] = restorer }
    }

    fun journal(context: Context): UndoJournal = instance ?: synchronized(this) {
        instance ?: UndoJournal(FileJournalStore(File(context.applicationContext.filesDir, "atom4_undo_journal.json"))).also { instance = it }
    }
}
