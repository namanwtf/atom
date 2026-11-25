package atom.ui.feature

import kotlinx.serialization.json.JsonElement

/**
 * One module's part of the manual data backup (Settings > Data backup). The backup is one JSON file; each
 * contributor owns the section stored under its [key].
 *
 * Rules every contributor must follow:
 * - [export] returns the module's complete current data. It is also used, in memory, as the rollback snapshot
 *   taken right before an import, so it must round-trip: `import(export())` restores the exact same state.
 * - [validate] checks a section without writing anything. Return null when it can be imported, or a short
 *   user-facing reason. Every contributor validates before any contributor imports.
 * - [import] replaces the module's data with [data] (already validated). Throw on failure; the backup screen
 *   then rolls every already-imported contributor back to its snapshot.
 * - Never include speech-service credentials, OAuth / subscription tokens, MCP tokens or anything sealed to this
 *   device's Keystore that cannot be re-created elsewhere. Subscription sign-ins are restored disabled: the user
 *   signs in again after an import.
 * - Provider API keys MAY be included (the user asked for them); then set [containsSecrets] = true so the export
 *   shows a warning that the file holds keys in plain text.
 * - Keep it cheap to register: no I/O until a method is called.
 */
interface BackupContributor {
    /** Stable section key in the file, e.g. "conversations", "providers". Never change it once shipped. */
    val key: String

    /** @StringRes label shown in the import preview, e.g. "Conversations". */
    val label: Int

    suspend fun export(): JsonElement

    /** null = ok; otherwise a short reason this section cannot be imported. Must not write anything. */
    suspend fun validate(data: JsonElement): String?

    suspend fun import(data: JsonElement)

    /** True when [export] may include provider API keys or other secrets. */
    val containsSecrets: Boolean
}

/** Registry of backup sections. Modules register from AtomFeature.onAppCreate (or the app does, at startup). */
object Backups {
    private val lock = Any()
    private val byKey = LinkedHashMap<String, BackupContributor>()

    /** Registers [c]; a second contributor with the same key replaces the first. */
    fun register(c: BackupContributor) {
        require(c.key.isNotBlank()) { "backup key must not be blank" }
        synchronized(lock) { byKey[c.key] = c }
    }

    /** Snapshot of the registered contributors, in registration order. */
    val all: List<BackupContributor>
        get() = synchronized(lock) { byKey.values.toList() }
}
