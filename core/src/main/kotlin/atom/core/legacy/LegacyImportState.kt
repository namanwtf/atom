package atom.core.legacy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the importer already brought over from the previous app (`files/atom4_legacy_import.json`). Every old item
 * is imported at most once: a re-run skips what is recorded here, so it never duplicates anything, and something the
 * user deleted in Atom 4 after the import does not come back.
 */
@Serializable
data class LegacyImportState(
    val version: Int = 1,
    /** Set when a run finished; until then the import runs again at the next start. */
    val completedAt: Long? = null,
    /** Old provider id to Atom 4 provider id. */
    val providers: Map<String, String> = emptyMap(),
    /** Old provider ids whose API key was handled (imported, or Atom 4 already had one). */
    val keys: Set<String> = emptySet(),
    /** Old provider ids whose sign-in was imported (or Atom 4 was already signed in). */
    val signIns: Set<String> = emptySet(),
    /** Old conversation id to Atom 4 conversation id. */
    val conversations: Map<String, String> = emptyMap(),
    val selectionApplied: Boolean = false,
    val report: LegacyImportReport? = null,
    /** False while the result of the last run still waits to be shown once. */
    val noticeShown: Boolean = true,
) {
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        /** Null for a missing or unreadable marker (which counts as "never imported"). */
        fun decode(text: String?): LegacyImportState? =
            text?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/** The outcome of one run, shown once as a notice and again after a manual run. */
@Serializable
data class LegacyImportReport(
    val at: Long = 0,
    val conversations: Int = 0,
    val providers: Int = 0,
    val keys: Int = 0,
    val signIns: Int = 0,
    /** Character chats, imported as normal chats. */
    val characterChats: Int = 0,
    /** Display names of providers whose sign-in could not be carried over. */
    val signInAgain: List<String> = emptyList(),
    val failedProviders: Int = 0,
    val failedConversations: Int = 0,
    val damagedMessages: Int = 0,
    val skippedImages: Int = 0,
    val selectionImported: Boolean = false,
    /** The old database was missing, empty or too old. */
    val nothingFound: Boolean = false,
    /** Set when the run stopped early; what was imported before stays imported. */
    val error: String? = null,
)

/** The importer's decisions, kept free of Android so they can be tested. */
object LegacyImportPlan {
    /** Runs automatically once: when the old database exists and no run has finished yet. */
    fun shouldAutoRun(databaseExists: Boolean, state: LegacyImportState?): Boolean =
        databaseExists && state?.completedAt == null

    enum class ConversationAction { Import, AlreadyImported, ExistsInAtom4 }

    /**
     * Whether to import old conversation [oldId] and under which Atom 4 id. The old id is kept when Atom 4 accepts
     * it ([validId]), so a run interrupted before its marker was written still finds what it saved.
     */
    fun conversation(
        state: LegacyImportState,
        oldId: String,
        validId: (String) -> Boolean,
        exists: (String) -> Boolean,
        newId: () -> String,
    ): Pair<ConversationAction, String> {
        state.conversations[oldId]?.let { return ConversationAction.AlreadyImported to it }
        val target = if (validId(oldId)) oldId else newId()
        return if (exists(target)) ConversationAction.ExistsInAtom4 to target else ConversationAction.Import to target
    }

    /** An old key is added only when Atom 4 has none for that provider (never overwritten) and it was not handled. */
    fun shouldImportKey(state: LegacyImportState, oldProviderId: String, oldKey: String?, atom4HasKey: Boolean): Boolean =
        !oldKey.isNullOrBlank() && oldProviderId !in state.keys && !atom4HasKey

    fun shouldImportSignIn(state: LegacyImportState, row: LegacyProviderRow, atom4SignedIn: Boolean): Boolean =
        row.isOAuth && row.id !in state.signIns && !atom4SignedIn

    /** The old default model is applied once, and only while the user has not picked one in Atom 4. */
    fun shouldApplySelection(state: LegacyImportState, chosenInAtom4: Boolean): Boolean =
        !state.selectionApplied && !chosenInAtom4

    /**
     * The system prompt the old app seeded into its built-in providers, guessed as the prompt most built-in rows
     * share (at least two). Rows still holding it get Atom 4's own default instead; edited prompts are kept.
     */
    fun seededPrompt(rows: List<LegacyProviderRow>): String? =
        rows.filter { it.builtIn }
            .mapNotNull { it.systemPrompt?.trim()?.takeIf { p -> p.isNotEmpty() } }
            .groupingBy { it }
            .eachCount()
            .filter { it.value >= 2 }
            .maxByOrNull { it.value }
            ?.key
}
