package atom.extend.memory

import atom.extend.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

data class MemoryState(val text: String, val hash: String, val enabled: Boolean) {
    val shortHash: String get() = hash.take(12)
}

sealed interface MemoryEdit {
    data class Saved(val state: MemoryState) : MemoryEdit

    /** The file changed since [MemoryState.hash] was read; nothing was written. */
    data class Conflict(val current: MemoryState) : MemoryEdit
    data class Rejected(val reason: String) : MemoryEdit
}

/**
 * The single MEMORY.md. Every write goes through one lock and checks the content hash the writer started from,
 * so neither the editor nor the agent can silently overwrite a change it has not seen.
 */
class MemoryStore(dir: File) {
    val file = File(dir, "MEMORY.md")
    private val settingsFile = File(dir, "memory.json")
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Settings(val enabled: Boolean = true)

    private var stamp = -1L to -1L
    private val _state = MutableStateFlow(readDisk(settings().enabled))
    val state: StateFlow<MemoryState> = _state.asStateFlow()

    /** Current content, re-read when the file was changed behind our back (a backup restore, for example). */
    fun current(): MemoryState {
        val now = file.lastModified() to file.length()
        if (now != stamp) _state.value = readDisk(_state.value.enabled)
        return _state.value
    }

    fun setEnabled(enabled: Boolean) {
        Files.writeAtomic(settingsFile, json.encodeToString(Settings.serializer(), Settings(enabled)))
        _state.value = _state.value.copy(enabled = enabled)
    }

    suspend fun save(text: String, baseHash: String): MemoryEdit = edit(baseHash) { text }

    suspend fun append(entry: String, section: String?): MemoryEdit {
        if (entry.isBlank()) return MemoryEdit.Rejected("Nothing to append.")
        return edit(null) { MemoryText.append(it, entry, section) }
    }

    suspend fun update(oldText: String, newText: String, expectedHash: String?): MemoryEdit {
        if (oldText.isEmpty()) return MemoryEdit.Rejected("old_text must not be empty.")
        return mutex.withLock {
            val cur = current()
            if (expectedHash != null && !matches(cur.hash, expectedHash)) return@withLock MemoryEdit.Conflict(cur)
            when (val n = MemoryText.occurrences(cur.text, oldText)) {
                0 -> MemoryEdit.Rejected("old_text was not found. Read the memory again and copy the exact text.")
                1 -> write(cur.text.replaceFirst(oldText, newText))
                else -> MemoryEdit.Rejected("old_text appears $n times. Include more surrounding text so it is unique.")
            }
        }
    }

    suspend fun clear(expectedHash: String?): MemoryEdit = edit(expectedHash) { MemoryText.TEMPLATE }

    private suspend fun edit(expectedHash: String?, change: (String) -> String): MemoryEdit = mutex.withLock {
        val cur = current()
        if (expectedHash != null && !matches(cur.hash, expectedHash)) return@withLock MemoryEdit.Conflict(cur)
        write(change(cur.text))
    }

    private fun write(text: String): MemoryEdit {
        val size = MemoryText.byteSize(text)
        if (size > MemoryText.MAX_BYTES) {
            return MemoryEdit.Rejected("MEMORY.md would be ${size / 1024} KiB; the limit is 1 MiB. Shorten or remove old entries.")
        }
        Files.writeAtomic(file, text)
        _state.value = state(text, _state.value.enabled)
        stamp = file.lastModified() to file.length()
        return MemoryEdit.Saved(_state.value)
    }

    private fun readDisk(enabled: Boolean): MemoryState {
        val text = Files.readOrNull(file) ?: MemoryText.TEMPLATE.also { Files.writeAtomic(file, it) }
        stamp = file.lastModified() to file.length()
        return state(text, enabled)
    }

    private fun settings(): Settings =
        Files.readOrNull(settingsFile)?.let { runCatching { json.decodeFromString(Settings.serializer(), it) }.getOrNull() } ?: Settings()

    private fun state(text: String, enabled: Boolean) = MemoryState(text, Files.sha256(text), enabled)

    companion object {
        /** Full hashes, or a prefix of at least 8 characters as shown to the model. */
        fun matches(hash: String, given: String): Boolean {
            val g = given.trim().lowercase()
            return g.length >= 8 && hash.startsWith(g)
        }
    }
}
