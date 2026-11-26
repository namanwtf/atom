package atom.core.legacy

import atom.core.io.deleteTreeNoFollow
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Removing what the previous app left on disk. It kept API keys unencrypted in `databases/atom.db`; Atom 4 keeps its
 * own copies in its Keystore-encrypted vault. So once an import run finished cleanly ([LegacyImportState.completedAt])
 * the old files are deleted [DELAY_MS] later, which leaves time to run the import again if something looks off, or
 * earlier from the backup screen. Without a clean import nothing is deleted automatically.
 *
 * Only the exact names below are touched, all relative to the app's data directory and all taken from the previous
 * app's own storage; none starts with `atom4`, the prefix of every Atom 4 store. Directories are removed with
 * [deleteTreeNoFollow], so a link inside them is removed as a link and never followed.
 */
object LegacyCleanup {
    /** How long the old files stay after a clean import. */
    const val DELAY_MS: Long = 7L * 24 * 60 * 60 * 1000

    /**
     * One leftover of the previous app, as a path relative to the data directory. A [memory] target is removed (and
     * counted) only once the old memory was imported, so it is never lost.
     */
    data class Target(val path: String, val directory: Boolean = false, val memory: Boolean = false)

    val DATABASE = Target("databases/" + LegacyFiles.DATABASE)
    val DATABASE_WAL = Target(DATABASE.path + "-wal")
    val DATABASE_SHM = Target(DATABASE.path + "-shm")
    val DATABASE_JOURNAL = Target(DATABASE.path + "-journal")

    /** Account sign-ins, one sealed file per provider. */
    val CREDENTIALS = Target("no_backup/" + LegacyFiles.CREDENTIALS_DIR, directory = true)

    /** The old settings DataStore (Atom 4's is `atom4_settings`), and the temp file a crashed write leaves. */
    val SETTINGS = Target("files/" + LegacyFiles.SETTINGS)
    val SETTINGS_TMP = Target(SETTINGS.path + ".tmp")

    /** Saved secrets (SharedPreferences), each with the `.bak` copy a crashed commit leaves. */
    val MCP_SECRETS = Target("shared_prefs/atom_mcp_secrets.xml")
    val SPEECH_SECRETS = Target("shared_prefs/atom_speech_secrets.xml")
    val IMAGE_GEN_SECRETS = Target("shared_prefs/atom_image_gen_secrets.xml")
    val WEB_TOOLS_SECRETS = Target("shared_prefs/atom_web_tools_secrets.xml")
    private val SECRETS = listOf(MCP_SECRETS, SPEECH_SECRETS, IMAGE_GEN_SECRETS, WEB_TOOLS_SECRETS)

    /** Long-term memory (an AtomicFile: the file, its backup and an unfinished write). */
    val MEMORY = Target("files/" + LegacyFiles.MEMORY, memory = true)
    private val MEMORY_FILES = listOf(MEMORY, Target(MEMORY.path + ".bak", memory = true), Target(MEMORY.path + ".new", memory = true))

    private fun bak(t: Target) = Target(t.path + ".bak")

    /** In deletion order: the database first, so the import offer goes away with it. */
    val TARGETS: List<Target> = listOf(DATABASE, DATABASE_WAL, DATABASE_SHM, DATABASE_JOURNAL, CREDENTIALS, SETTINGS, SETTINGS_TMP) +
        SECRETS.flatMap { listOf(it, bak(it)) } +
        MEMORY_FILES

    /** A previous-app Android Keystore key, deleted only once the files it sealed ([after]) are gone. */
    data class Key(val alias: String, val after: List<Target>)

    val KEYS: List<Key> = listOf(
        Key(LegacyFiles.CREDENTIALS_ALIAS, listOf(CREDENTIALS)),
        Key("atom_mcp_credentials_v1", listOf(MCP_SECRETS, bak(MCP_SECRETS))),
        Key("atom_speech_credentials_v1", listOf(SPEECH_SECRETS, bak(SPEECH_SECRETS))),
        Key("atom_image_gen_credentials_v1", listOf(IMAGE_GEN_SECRETS, bak(IMAGE_GEN_SECRETS))),
        Key("atom_web_tools_credentials_v1", listOf(WEB_TOOLS_SECRETS, bak(WEB_TOOLS_SECRETS))),
    )

    /**
     * Whether to remove the old files now, automatically: [DELAY_MS] after a clean import, and only once. Never
     * without a clean import, and never again after a removal was recorded.
     */
    fun shouldAutoClean(state: LegacyImportState?, now: Long): Boolean {
        val completed = state?.completedAt ?: return false
        return state.legacyCleanedAt == null && now - completed >= DELAY_MS
    }

    /** What the backup screen offers for the old files. */
    enum class Offer {
        /** No old file is left. */
        Hidden,

        /** Old files exist but no import has finished yet: disabled, "Import first". */
        ImportFirst,

        /** A clean import finished (or there is no database to import from): removing loses nothing. */
        Ready,

        /** Imports ran but none finished cleanly: allowed, with a warning that what was not imported is lost. */
        Unimported,
    }

    fun offer(oldFilesExist: Boolean, databaseExists: Boolean, state: LegacyImportState?): Offer = when {
        !oldFilesExist -> Offer.Hidden
        state?.completedAt != null -> Offer.Ready
        // Without the database nothing can be imported (sign-ins need its provider rows).
        !databaseExists -> Offer.Ready
        state?.report == null && (state?.attempts ?: 0) < LegacyImportPlan.MAX_AUTO_ATTEMPTS -> Offer.ImportFirst
        else -> Offer.Unimported
    }

    /** The targets to remove: the memory files only once the old memory was imported ([memoryImported]). */
    fun targets(memoryImported: Boolean): List<Target> = if (memoryImported) TARGETS else TARGETS.filter { !it.memory }

    /** The targets still present under [dataDir] (a dangling link counts as present). */
    fun existing(dataDir: File, memoryImported: Boolean): List<Target> =
        targets(memoryImported).filter { exists(File(dataDir, it.path)) }

    /**
     * Deletes the targets under [dataDir] and returns those still there afterwards (empty when all are gone).
     * Files are removed with [File.delete], directories with [deleteTreeNoFollow]; a missing one is skipped. The old
     * memory stays until it was imported ([memoryImported]).
     */
    fun deleteFiles(dataDir: File, memoryImported: Boolean): List<Target> {
        for (target in targets(memoryImported)) {
            val file = File(dataDir, target.path)
            if (!exists(file)) continue
            runCatching { if (target.directory) file.deleteTreeNoFollow() else file.delete() }
        }
        return existing(dataDir, memoryImported)
    }

    /** What a removal did, for the message shown after it. */
    enum class Removal {
        /** Every file and key is gone. */
        Done,

        /** As [Done], and the old memory file (already in Atom 4's memory) was among them. */
        DoneWithMemory,

        /** Something could not be removed; it is tried again later. */
        Partial,
    }

    /** Records the removal once; a later call keeps the first time. */
    fun markCleaned(state: LegacyImportState, now: Long): LegacyImportState =
        if (state.legacyCleanedAt != null) state else state.copy(legacyCleanedAt = now)

    private fun exists(file: File): Boolean = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
}
