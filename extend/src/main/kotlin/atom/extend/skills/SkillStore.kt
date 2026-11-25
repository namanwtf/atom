package atom.extend.skills

import atom.core.io.deleteTreeNoFollow
import atom.extend.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Read access to the skills bundled with the app. */
interface BuiltInSkills {
    fun names(): List<String>
    fun files(name: String): List<String>
    fun read(name: String, path: String): ByteArray?
}

data class InstallReport(val installed: List<String>, val skipped: List<String>)

/**
 * Built-in skills are read from the APK; user skills live in `user/<name>/`. Installs are transactional: new
 * skills are staged, existing ones moved aside, and a journal lets [recover] undo a half-finished install after
 * a crash.
 */
class SkillStore(private val root: File, private val builtIns: BuiltInSkills) {
    private val userDir = File(root, "user")
    private val stagingDir = File(root, ".staging")
    private val trashDir = File(root, ".trash")
    private val journalFile = File(root, "install-journal.json")
    private val stateFile = File(root, "state.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    @Serializable
    private data class State(val disabled: Set<String> = emptySet())

    @Serializable
    private data class JournalEntry(val name: String, val backup: String? = null)

    @Serializable
    private data class Journal(val entries: List<JournalEntry>)

    private val builtInManifests: Map<String, SkillManifest> by lazy {
        builtIns.names().mapNotNull { n ->
            builtIns.read(n, SkillManifest.FILE)?.let { SkillManifest.parse(it.toString(Charsets.UTF_8)).getOrNull() }?.let { n to it }
        }.toMap()
    }

    private val _skills = MutableStateFlow<List<SkillInfo>>(emptyList())
    val skills: StateFlow<List<SkillInfo>> = _skills.asStateFlow()

    init {
        recover()
        refresh()
    }

    fun isBuiltIn(name: String): Boolean = name in builtInManifests

    fun enabled(): List<SkillInfo> = _skills.value.filter { it.enabled }

    fun get(name: String): SkillInfo? = _skills.value.firstOrNull { it.name == name }

    fun userSkillDir(name: String): File = File(userDir, name)

    fun files(name: String): List<Pair<String, Long>> = when {
        isBuiltIn(name) -> builtIns.files(name).map { it to (builtIns.read(name, it)?.size?.toLong() ?: 0) }
        else -> userSkillDir(name).takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile }
            ?.map { it.relativeTo(userSkillDir(name)).path.replace(File.separatorChar, '/') to it.length() }
            ?.sortedBy { it.first }?.toList().orEmpty()
    }

    fun read(name: String, path: String): ByteArray? {
        val rel = SkillZip.safeRelativePath(path) ?: return null
        if (isBuiltIn(name)) return builtIns.read(name, rel)
        val dir = userSkillDir(name).canonicalFile
        val f = File(dir, rel).canonicalFile
        return if (f.path.startsWith(dir.path + File.separator) && f.isFile) f.readBytes() else null
    }

    fun setEnabled(name: String, enabled: Boolean) {
        val s = state()
        writeState(s.copy(disabled = if (enabled) s.disabled - name else s.disabled + name))
        refresh()
    }

    suspend fun delete(name: String): Boolean = mutex.withLock {
        if (isBuiltIn(name)) return@withLock false
        val dir = userSkillDir(name)
        if (!dir.exists()) return@withLock false
        val aside = File(trashDir, UUID.randomUUID().toString())
        trashDir.mkdirs()
        if (!dir.renameTo(aside)) dir.deleteTreeNoFollow() else aside.deleteTreeNoFollow()
        refresh()
        true
    }

    /** A fresh directory for staging one install; it is removed by [install] or by [recover]. */
    fun newStaging(): File = File(stagingDir, UUID.randomUUID().toString()).apply { mkdirs() }

    /**
     * Commits [staged] skills all at once or not at all. Built-in names are always skipped; existing user skills
     * are replaced only when [allowReplace] approves their names.
     */
    suspend fun install(staged: List<StagedSkill>, allowReplace: suspend (List<String>) -> Boolean): InstallReport = mutex.withLock {
        try {
            val dupes = staged.groupBy { it.manifest.name }.filterValues { it.size > 1 }.keys
            if (dupes.isNotEmpty()) throw SkillException("Two skills share the name ${dupes.joinToString()}.")
            val skipped = mutableListOf<String>()
            val builtIn = staged.filter { isBuiltIn(it.manifest.name) }
            skipped += builtIn.map { "${it.manifest.name} (built-in skills cannot be overwritten)" }
            var candidates = staged - builtIn.toSet()
            val existing = candidates.filter { userSkillDir(it.manifest.name).exists() }.map { it.manifest.name }
            if (existing.isNotEmpty() && !allowReplace(existing)) {
                skipped += existing.map { "$it (already installed; replacement declined)" }
                candidates = candidates.filter { it.manifest.name !in existing }
            }
            commit(candidates)
            val s = state()
            writeState(s.copy(disabled = s.disabled - candidates.map { it.manifest.name }.toSet()))
            InstallReport(candidates.map { it.manifest.name }, skipped)
        } finally {
            staged.forEach { stagingRoot(it.dir)?.deleteTreeNoFollow() }
            refresh()
        }
    }

    private fun stagingRoot(dir: File): File? {
        val base = stagingDir.canonicalFile
        var f: File? = dir.canonicalFile
        while (f != null && f.parentFile != base) f = f.parentFile
        return f
    }

    /** Removes a staging directory from [newStaging] that will not be installed. */
    fun discard(staging: File) {
        stagingRoot(staging)?.deleteTreeNoFollow()
    }

    private fun commit(staged: List<StagedSkill>) {
        if (staged.isEmpty()) return
        userDir.mkdirs()
        val tx = File(trashDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val entries = staged.map { s ->
            JournalEntry(s.manifest.name, File(tx, s.manifest.name).path.takeIf { userSkillDir(s.manifest.name).exists() })
        }
        Files.writeAtomic(journalFile, json.encodeToString(Journal.serializer(), Journal(entries)))
        try {
            staged.zip(entries).forEach { (s, e) ->
                val final = userSkillDir(s.manifest.name)
                if (e.backup != null && !final.renameTo(File(e.backup))) throw SkillException("Could not replace ${s.manifest.name}.")
                if (!s.dir.renameTo(final)) {
                    s.dir.copyRecursively(final, overwrite = true)
                }
            }
        } catch (e: Exception) {
            rollback(entries)
            journalFile.delete()
            tx.deleteTreeNoFollow()
            throw e as? SkillException ?: SkillException("Install failed and was undone: ${e.message}")
        }
        journalFile.delete()
        tx.deleteTreeNoFollow()
    }

    private fun rollback(entries: List<JournalEntry>) {
        for (e in entries) {
            val final = userSkillDir(e.name)
            if (e.backup == null) {
                final.deleteTreeNoFollow()
            } else {
                val backup = File(e.backup)
                if (backup.exists()) {
                    final.deleteTreeNoFollow()
                    backup.renameTo(final)
                }
            }
        }
    }

    /** Undoes an install interrupted by a crash and clears leftovers. Runs once when the store opens. */
    fun recover() {
        Files.readOrNull(journalFile)?.let { text ->
            runCatching { json.decodeFromString(Journal.serializer(), text) }.getOrNull()?.let { rollback(it.entries) }
            journalFile.delete()
        }
        stagingDir.deleteTreeNoFollow()
        trashDir.deleteTreeNoFollow()
    }

    fun refresh() {
        val disabled = state().disabled
        val builtIn = builtInManifests.values.map { SkillInfo(it, SkillOrigin.BuiltIn, it.name !in disabled) }
        val user = userDir.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { dir ->
            val manifest = File(dir, SkillManifest.FILE).takeIf { it.isFile }?.let { SkillManifest.parse(it.readText()).getOrNull() }
                ?: return@mapNotNull null
            if (manifest.name != dir.name) return@mapNotNull null
            val files = dir.walkTopDown().filter { it.isFile }.toList()
            SkillInfo(manifest, SkillOrigin.User, manifest.name !in disabled, files.sumOf { it.length() }, files.size)
        }.sortedBy { it.name }
        _skills.value = builtIn.sortedBy { it.name } + user
    }

    private fun state(): State =
        Files.readOrNull(stateFile)?.let { runCatching { json.decodeFromString(State.serializer(), it) }.getOrNull() } ?: State()

    private fun writeState(s: State) = Files.writeAtomic(stateFile, json.encodeToString(State.serializer(), s))
}
