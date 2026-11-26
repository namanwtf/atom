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

    /** One leftover of the previous app, as a path relative to the data directory. */
    data class Target(val path: String, val directory: Boolean = false)

    val DATABASE = Target("databases/" + LegacyFiles.DATABASE)
    val DATABASE_WAL = Target(DATABASE.path + "-wal")
    val DATABASE_SHM = Target(DATABASE.path + "-shm")
    val DATABASE_JOURNAL = Target(DATABASE.path + "-journal")

    /** Account sign-ins, one sealed file per provider. */
    val CREDENTIALS = Target("no_backup/" + LegacyFiles.CREDENTIALS_DIR, directory = true)

    /** The old settings DataStore (Atom 4's is `atom4_settings`). */
    val SETTINGS = Target("files/" + LegacyFiles.SETTINGS)
    val MCP_SECRETS = Target("shared_prefs/atom_mcp_secrets.xml")
    val SPEECH_SECRETS = Target("shared_prefs/atom_speech_secrets.xml")
    val IMAGE_GEN_SECRETS = Target("shared_prefs/atom_image_gen_secrets.xml")

    /** In deletion order: the database first, so the import offer goes away with it. */
    val TARGETS: List<Target> = listOf(
        DATABASE,
        DATABASE_WAL,
        DATABASE_SHM,
        DATABASE_JOURNAL,
        CREDENTIALS,
        SETTINGS,
        MCP_SECRETS,
        SPEECH_SECRETS,
        IMAGE_GEN_SECRETS,
    )

    /** A previous-app Android Keystore key, deleted only once the file it sealed ([after]) is gone. */
    data class Key(val alias: String, val after: Target)

    val KEYS: List<Key> = listOf(
        Key(LegacyFiles.CREDENTIALS_ALIAS, CREDENTIALS),
        Key("atom_mcp_credentials_v1", MCP_SECRETS),
        Key("atom_speech_credentials_v1", SPEECH_SECRETS),
        Key("atom_image_gen_credentials_v1", IMAGE_GEN_SECRETS),
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

    /** The targets still present under [dataDir] (a dangling link counts as present). */
    fun existing(dataDir: File): List<Target> = TARGETS.filter { exists(File(dataDir, it.path)) }

    /**
     * Deletes every target under [dataDir] and returns those still there afterwards (empty when all are gone).
     * Files are removed with [File.delete], directories with [deleteTreeNoFollow]; a missing one is skipped.
     */
    fun deleteFiles(dataDir: File): List<Target> {
        for (target in TARGETS) {
            val file = File(dataDir, target.path)
            if (!exists(file)) continue
            runCatching { if (target.directory) file.deleteTreeNoFollow() else file.delete() }
        }
        return existing(dataDir)
    }

    /** Records the removal once; a later call keeps the first time. */
    fun markCleaned(state: LegacyImportState, now: Long): LegacyImportState =
        if (state.legacyCleanedAt != null) state else state.copy(legacyCleanedAt = now)

    private fun exists(file: File): Boolean = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
}
