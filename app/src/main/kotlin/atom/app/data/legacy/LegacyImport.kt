package atom.app.data.legacy

import android.content.Context
import atom.app.AppGraph
import atom.app.R
import atom.app.data.Attachment
import atom.app.data.AttachmentKind
import atom.app.data.Attachments
import atom.app.data.Conversation
import atom.app.data.ConversationStore
import atom.app.data.EngineSelection
import atom.app.data.StoredMessage
import atom.app.data.TitleSource
import atom.core.io.deleteTreeNoFollow
import atom.core.legacy.ImportedConversation
import atom.core.legacy.ImportedEntry
import atom.core.legacy.LegacyConversations
import atom.core.legacy.LegacyFiles
import atom.core.legacy.LegacyImportPlan
import atom.core.legacy.LegacyImportReport
import atom.core.legacy.LegacyImportState
import atom.core.legacy.LegacyJournal
import atom.core.legacy.LegacyModelRow
import atom.core.legacy.LegacyProviderRow
import atom.core.legacy.LegacySelection
import atom.llm.cloud.OAuthTokens
import atom.llm.cloud.ProviderConfig
import atom.llm.cloud.legacy.LegacyProviders
import java.io.File
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * One-time import from the previous Atom app, which Atom 4 installs over (same package and signing key, so its data
 * directory and Keystore keys are still readable). Brings over provider settings and API keys, account sign-ins
 * where the old tokens are still usable, the default model and all conversations, through Atom 4's own stores.
 *
 * Runs once in the background on the first start that finds `databases/atom.db`, and again on request from the
 * backup screen. Every old item is imported at most once (see [LegacyImportState]); nothing the user set up in Atom 4
 * is overwritten, and no old file is changed or deleted. Keys and tokens are never logged and nothing leaves the
 * device. What was imported is recorded in `files/atom4_legacy_import.json`.
 */
object LegacyImport {
    private const val MARKER = "atom4_legacy_import.json"
    private const val MARKER_EVERY = 20
    private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024

    private val mutex = Mutex()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { encodeDefaults = false }

    private val _notice = MutableStateFlow<String?>(null)

    /** The result of an automatic run, shown once on the chat screen until dismissed. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** True when the previous app's database is on this device. */
    fun available(context: Context): Boolean = LegacyDatabase.source(context).isFile

    /** At app start, after the graph is ready: imports once, or brings back a result notice not yet seen. */
    suspend fun onAppStart(context: Context, graph: AppGraph) {
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val state = readState(app)
            if (LegacyImportPlan.shouldAutoRun(available(app), state)) {
                // Counted before the run, so a run that crashes the app still uses up one of its attempts.
                mutex.withLock {
                    val current = readState(app) ?: LegacyImportState()
                    writeState(app, current.copy(attempts = current.attempts + 1))
                }
                run(app, graph, manual = false)
            } else if (state?.noticeShown == false) {
                state.report?.let { _notice.value = describe(app, it) }
            }
            Unit
        }
    }

    fun dismissNotice(context: Context) {
        _notice.value = null
        val app = context.applicationContext
        io.launch { mutex.withLock { readState(app)?.let { writeState(app, it.copy(noticeShown = true)) } } }
    }

    /**
     * Runs the import (again) and returns its report, or null when a run is already in progress. A manual run shows
     * its result itself, so it posts no notice.
     */
    suspend fun run(context: Context, graph: AppGraph, manual: Boolean): LegacyImportReport? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if (!mutex.tryLock()) return@withContext null
        try {
            val (state, report) = importLocked(app, graph)
            // Finished only without an error or a failed item; otherwise the next start tries again (a few times).
            val completed = if (LegacyImportPlan.isComplete(report)) report.at else null
            writeState(app, state.copy(completedAt = completed ?: state.completedAt, report = report, noticeShown = manual))
            _notice.value = if (manual) null else describe(app, report)
            report
        } finally {
            mutex.unlock()
        }
    }

    /**
     * A manual run from the backup screen. It runs in the app's scope, so leaving the screen does not stop it; the
     * caller waits for the text. If the caller goes away first, the result is shown as the notice instead.
     */
    suspend fun runManually(context: Context, graph: AppGraph): String {
        val app = context.applicationContext
        val job = graph.appScope.async(Dispatchers.IO) { run(app, graph, manual = true) }
        val report = try {
            job.await()
        } catch (e: CancellationException) {
            graph.appScope.launch {
                val done = runCatching { job.await() }.getOrNull() ?: return@launch
                mutex.withLock { readState(app)?.let { writeState(app, it.copy(noticeShown = false)) } }
                _notice.value = describe(app, done)
            }
            throw e
        }
        return report?.let { describe(app, it) } ?: app.getString(R.string.import_busy)
    }

    /** The report as the notice and the backup screen show it. */
    fun describe(context: Context, r: LegacyImportReport): String {
        if (r.nothingFound && r.error == null) return context.getString(R.string.import_nothing)
        val res = context.resources
        fun count(id: Int, n: Int) = res.getQuantityString(id, n, n)
        val lines = ArrayList<String>()
        if (!r.nothingFound) {
            lines += context.getString(
                R.string.import_summary,
                count(R.plurals.import_conversations, r.conversations),
                count(R.plurals.import_providers, r.providers),
                count(R.plurals.import_keys, r.keys),
                count(R.plurals.import_sign_ins, r.signIns),
            )
        }
        if (r.selectionImported) lines += context.getString(R.string.import_selection)
        if (r.signInAgain.isNotEmpty()) lines += context.getString(R.string.import_sign_in_again, r.signInAgain.distinct().joinToString(", "))
        if (r.failedProviders > 0) lines += count(R.plurals.import_failed_providers, r.failedProviders)
        if (r.failedConversations > 0) lines += count(R.plurals.import_failed_conversations, r.failedConversations)
        if (r.withoutToolDetails > 0) lines += count(R.plurals.import_without_tool_details, r.withoutToolDetails)
        if (r.truncatedMessages > 0) lines += count(R.plurals.import_truncated, r.truncatedMessages)
        if (r.damagedMessages > 0) lines += count(R.plurals.import_damaged, r.damagedMessages)
        if (r.skippedImages > 0) lines += count(R.plurals.import_images_skipped, r.skippedImages)
        if (r.characterChats > 0) lines += count(R.plurals.import_characters, r.characterChats)
        r.error?.let { lines += context.getString(R.string.import_error, it) }
        lines += context.getString(R.string.import_not_included)
        return lines.joinToString("\n")
    }

    // ---------------------------------------------------------------- the run

    private class Counts {
        var conversations = 0
        var providers = 0
        var keys = 0
        var signIns = 0
        var characterChats = 0
        val signInAgain = ArrayList<String>()
        var failedProviders = 0
        var failedConversations = 0
        var damaged = 0
        var truncated = 0
        var withoutToolDetails = 0
        var skippedImages = 0
        var selection = false
    }

    private suspend fun importLocked(app: Context, graph: AppGraph): Pair<LegacyImportState, LegacyImportReport> {
        var state = readState(app) ?: LegacyImportState()
        val counts = Counts()
        val now = System.currentTimeMillis()
        // A previous run that was killed may have left its copy behind.
        runCatching { LegacyDatabase.tempRoot(app).deleteTreeNoFollow() }
        var error: String? = null
        var nothing = false
        try {
            val db = LegacyDatabase.open(app)
            nothing = db == null
            db?.use { legacy ->
                // Below version 6 the old app reset its database on start; there is nothing to import.
                nothing = legacy.version < 6
                if (!nothing) {
                    val providerRows = legacy.providers().sortedBy { p -> p.sortOrder }
                    val modelRows = legacy.models()
                    val selection = LegacySelection.fromFile(readSettings(app))
                    state = importProviders(app, graph, state, providerRows, modelRows, selection, counts, now)
                    // Its own guard: a problem with the default model must not keep the conversations out.
                    state = try {
                        importSelection(graph, state, providerRows, modelRows, selection, counts)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        state
                    }
                    state = importConversations(app, graph, legacy, state, modelRows, counts)
                }
                Unit
            }
        } catch (e: CancellationException) {
            writeState(app, state)
            throw e
        } catch (e: LegacyDatabase.NotEnoughSpaceException) {
            error = app.getString(R.string.import_error_space)
        } catch (e: Exception) {
            error = e.message ?: e::class.simpleName ?: "error"
        } finally {
            runCatching { LegacyDatabase.tempRoot(app).deleteTreeNoFollow() }
        }
        val report = LegacyImportReport(
            at = now,
            conversations = counts.conversations,
            providers = counts.providers,
            keys = counts.keys,
            signIns = counts.signIns,
            characterChats = counts.characterChats,
            signInAgain = counts.signInAgain,
            failedProviders = counts.failedProviders,
            failedConversations = counts.failedConversations,
            damagedMessages = counts.damaged,
            truncatedMessages = counts.truncated,
            withoutToolDetails = counts.withoutToolDetails,
            skippedImages = counts.skippedImages,
            selectionImported = counts.selection,
            nothingFound = nothing,
            error = error,
        )
        return state to report
    }

    private suspend fun importProviders(
        app: Context,
        graph: AppGraph,
        initial: LegacyImportState,
        rows: List<LegacyProviderRow>,
        models: List<LegacyModelRow>,
        selection: LegacySelection,
        counts: Counts,
        now: Long,
    ): LegacyImportState {
        var state = initial
        val store = graph.providers
        val vault = graph.vault
        val seeded = LegacyImportPlan.seededPrompt(rows)
        val removed = store.removedPresets()
        for (row in rows) {
            currentCoroutineContext().ensureActive()
            try {
                var newId = state.providers[row.id]
                if (newId == null) {
                    val imported = LegacyProviders.toConfig(row, models, seeded, selection.perProvider[row.id])
                    val target = findTarget(store.all(), imported)
                    // A built-in the user removed in Atom 4 stays removed (and its old key with it).
                    if (target == null && imported.presetId != null && imported.presetId in removed) continue
                    val id = if (target != null) {
                        // Presence only: nothing is decrypted to decide this.
                        val configured = target.id in vault.savedProviders.first() ||
                            target.id in vault.signedInProviders.first() ||
                            target.id in vault.unreadable.first()
                        store.update(target.id) { LegacyProviders.merge(it, imported, configured) }
                        target.id
                    } else {
                        store.add(imported).id
                    }
                    counts.providers++
                    state = state.copy(providers = state.providers + (row.id to id))
                    newId = id
                }
                val providerId: String = newId ?: continue
                // Deleted in Atom 4 since an earlier run: leave it deleted.
                val config = store.get(providerId) ?: continue
                val key = row.usableApiKey
                if (key != null && row.id !in state.keys) {
                    val hasKey = providerId in vault.savedProviders.first()
                    if (LegacyImportPlan.shouldImportKey(state, row.id, key, hasKey)) {
                        vault.setApiKey(providerId, key)
                        counts.keys++
                    }
                    state = state.copy(keys = state.keys + row.id)
                }
                if (row.isOAuth && row.id !in state.signIns) {
                    // Already signed in, or set up with an API key in Atom 4: that setup is kept as it is.
                    val signedIn = providerId in vault.signedInProviders.first() || providerId in vault.savedProviders.first()
                    if (!LegacyImportPlan.shouldImportSignIn(state, row, signedIn)) {
                        state = state.copy(signIns = state.signIns + row.id)
                    } else if (importSignIn(app, graph, row, config, now)) {
                        counts.signIns++
                        state = state.copy(signIns = state.signIns + row.id)
                    } else {
                        counts.signInAgain += config.displayName
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                counts.failedProviders++
            }
        }
        writeState(app, state)
        return state
    }

    /** The Atom 4 provider an imported one folds into: the same preset, or a custom one at the same endpoint. */
    private fun findTarget(current: List<ProviderConfig>, imported: ProviderConfig): ProviderConfig? {
        val preset = imported.presetId
        if (preset != null) return current.firstOrNull { it.id == preset } ?: current.firstOrNull { it.presetId == preset }
        fun norm(url: String) = url.trim().trimEnd('/').lowercase()
        return current.firstOrNull {
            it.presetId == null && it.wire == imported.wire && norm(it.baseUrl) == norm(imported.baseUrl) &&
                it.displayName.trim().equals(imported.displayName.trim(), ignoreCase = true)
        }
    }

    /** True when the old sign-in was usable and is now stored for [config]. */
    private suspend fun importSignIn(app: Context, graph: AppGraph, row: LegacyProviderRow, config: ProviderConfig, now: Long): Boolean {
        val read = LegacyCredentials.read(app, row.id) as? LegacyCredentials.Read.Plain ?: return false
        val signIn = LegacyProviders.signIn(read.json, row, now) as? LegacyProviders.SignIn.Usable ?: return false
        graph.vault.setOauthTokens(config.id, json.encodeToString(OAuthTokens.serializer(), signIn.tokens))
        graph.providers.update(config.id) { it.copy(auth = signIn.method) }
        return true
    }

    private suspend fun importSelection(
        graph: AppGraph,
        initial: LegacyImportState,
        rows: List<LegacyProviderRow>,
        models: List<LegacyModelRow>,
        selection: LegacySelection,
        counts: Counts,
    ): LegacyImportState {
        val chosen = graph.settings.current().selectionExplicit
        if (!LegacyImportPlan.shouldApplySelection(initial, chosen)) return initial
        val configs = graph.providers.all().associateBy { it.id }
        val old = selection.resolve(rows, models)
        val target = LegacySelection.target(old?.id, models, initial.providers) { p, m -> configs[p]?.model(m) != null }
        if (target != null) {
            graph.settings.setSelection(EngineSelection.Cloud(target.first, target.second))
            counts.selection = true
        }
        return initial.copy(selectionApplied = true)
    }

    private suspend fun importConversations(
        app: Context,
        graph: AppGraph,
        db: LegacyDatabase,
        initial: LegacyImportState,
        models: List<LegacyModelRow>,
        counts: Counts,
    ): LegacyImportState {
        var state = initial
        val store = graph.conversations
        val configs = graph.providers.all().associateBy { it.id }
        val existing = store.summaries.first().mapTo(HashSet()) { it.id }
        var unsaved = 0
        for (oldId in db.conversationIds()) {
            currentCoroutineContext().ensureActive()
            val (action, target) = LegacyImportPlan.conversation(
                state,
                oldId,
                validId = { ConversationStore.isValidId(it) },
                exists = { it in existing },
                newId = { Conversation.newId() },
            )
            when (action) {
                LegacyImportPlan.ConversationAction.AlreadyImported -> continue
                LegacyImportPlan.ConversationAction.ExistsInAtom4 -> state = state.copy(conversations = state.conversations + (oldId to target))
                LegacyImportPlan.ConversationAction.Import -> {
                    val saved = ArrayList<String>()
                    try {
                        val row = db.conversation(oldId) ?: continue
                        val messages = db.messages(oldId, LegacyConversations.MAX_MESSAGE_CHARS) { image ->
                            saveImage(app, image)?.also { saved += it }
                        }
                        val journal = try {
                            db.journal(oldId, LegacyConversations.MAX_MESSAGE_CHARS)
                        } catch (e: OutOfMemoryError) {
                            // A single giant transcript entry: the timeline is still imported, with previews only.
                            LegacyJournal.EMPTY
                        }
                        val mapped = LegacyConversations.map(row, messages, journal)
                        val selection = LegacySelection.target(mapped.modelRowId, models, state.providers) { p, m -> configs[p]?.model(m) != null }
                            ?.let { (p, m) -> EngineSelection.Cloud(p, m) }
                        store.save(toConversation(app, target, mapped, selection))
                        existing += target
                        counts.conversations++
                        if (mapped.isCharacterChat) counts.characterChats++
                        counts.damaged += mapped.damagedRows
                        counts.truncated += mapped.truncatedEntries
                        if (mapped.toolRowsWithoutDetails > 0) counts.withoutToolDetails++
                        counts.skippedImages += messages.sumOf { it.droppedImages }
                        state = state.copy(conversations = state.conversations + (oldId to target))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Attachments.deleteImages(saved.map { Attachment(AttachmentKind.Image, it, "") })
                        counts.failedConversations++
                    } catch (e: OutOfMemoryError) {
                        // One oversized conversation should not stop the rest.
                        Attachments.deleteImages(saved.map { Attachment(AttachmentKind.Image, it, "") })
                        counts.failedConversations++
                    }
                }
            }
            if (++unsaved >= MARKER_EVERY) {
                writeState(app, state)
                unsaved = 0
            }
        }
        writeState(app, state)
        return state
    }

    /** A local copy of one inline image (`data:image/...;base64,...`, or a file path that still exists), or null. */
    private fun saveImage(app: Context, image: String): String? {
        val bytes = runCatching { imageBytes(image) }.getOrNull() ?: return null
        return runCatching { Attachments.saveImageBytes(app, bytes) }.getOrNull()?.path
    }

    private fun imageBytes(image: String): ByteArray? {
        val value = image.trim()
        if (value.startsWith("data:", ignoreCase = true)) {
            val comma = value.indexOf(',')
            if (comma < 0 || !value.substring(0, comma).contains(";base64", ignoreCase = true)) return null
            if ((value.length - comma) / 4L * 3 > MAX_IMAGE_BYTES) return null
            return Base64.getMimeDecoder().decode(value.substring(comma + 1))
        }
        val file = File(value)
        return if (value.startsWith("/") && file.isFile && file.length() <= MAX_IMAGE_BYTES) file.readBytes() else null
    }

    private fun toConversation(app: Context, id: String, c: ImportedConversation, selection: EngineSelection.Cloud?): Conversation =
        Conversation(
            id = id,
            title = c.title,
            createdAt = c.createdAt,
            updatedAt = c.updatedAt,
            messages = c.entries.map { toStored(app, it) },
            pinned = c.pinned,
            titleSource = if (c.titleIsPlaceholder) TitleSource.Placeholder else TitleSource.Generated,
            selection = selection,
            reasoningEnabled = c.reasoningEnabled,
            reasoningEffort = c.reasoningEffort,
        )

    private fun toStored(app: Context, e: ImportedEntry): StoredMessage = when (e) {
        is ImportedEntry.Message -> StoredMessage(
            message = e.message,
            isError = e.isError,
            reasoning = e.reasoning,
            reasoningMs = e.reasoningMs,
            attachments = e.imagePaths.map { path ->
                val file = File(path)
                Attachment(AttachmentKind.Image, path, file.name, file.length().takeIf { it > 0 })
            },
            createdAt = e.createdAt,
            edited = e.edited,
        )
        is ImportedEntry.Notice -> {
            val base = when (e.code) {
                "stopped" -> app.getString(R.string.run_stopped)
                "interrupted" -> app.getString(R.string.import_notice_interrupted)
                "empty_result" -> app.getString(R.string.run_no_text)
                "runtime_failed" -> app.getString(R.string.run_failed)
                "model_retry" -> app.getString(R.string.import_notice_retry)
                else -> app.getString(R.string.import_notice_compaction)
            }
            val text = e.detail?.let { app.getString(R.string.import_notice_detail, base, it) } ?: base
            StoredMessage.notice(text, e.createdAt).copy(isError = e.isError)
        }
    }

    // ---------------------------------------------------------------- files

    private fun markerFile(app: Context) = File(app.filesDir, MARKER)

    private fun readState(app: Context): LegacyImportState? =
        runCatching { markerFile(app).takeIf { it.isFile }?.readText() }.getOrNull()?.let(LegacyImportState::decode)

    private fun writeState(app: Context, state: LegacyImportState) {
        runCatching {
            val target = markerFile(app)
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(state.encode())
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        }
    }

    /** The old DataStore `atom_settings` file (read only), or null when it is missing. */
    private fun readSettings(app: Context): ByteArray? =
        runCatching { File(app.filesDir, LegacyFiles.SETTINGS).takeIf { it.isFile && it.length() < 4 * 1024 * 1024 }?.readBytes() }.getOrNull()
}
