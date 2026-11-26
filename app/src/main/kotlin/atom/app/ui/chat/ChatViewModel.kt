package atom.app.ui.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import atom.app.AppGraph
import atom.app.ChatRequest
import atom.app.agent.ChatItem
import atom.app.agent.CompactResult
import atom.app.agent.LiveRun
import atom.app.agent.PendingApproval
import atom.app.agent.RetryNotice
import atom.app.agent.RunOutcome
import atom.app.agent.Transcript
import atom.app.data.Attachment
import atom.app.data.AttachmentKind
import atom.app.data.Attachments
import atom.app.data.Conversation
import atom.app.data.ConversationExport
import atom.app.data.ConversationMatch
import atom.app.data.ConversationSummary
import atom.app.data.EngineSelection
import atom.app.data.ReadingPosition
import atom.app.data.TitleSource
import atom.core.agent.SessionStats
import atom.core.llm.ChatMessage
import atom.core.llm.ReasoningEffort
import atom.core.llm.ReasoningSupport
import atom.core.runtime.QueuedMessage
import atom.ui.feature.RunPhase
import atom.app.data.StoredMessage
import atom.characters.model.CandidateSet
import atom.characters.model.Character
import atom.llm.cloud.ProviderBrands
import atom.characters.model.ChatBinding
import atom.characters.session.CharacterSession
import atom.core.llm.Role
import atom.extend.subagent.SubagentRun
import atom.extend.subagent.SubagentRuns
import atom.voice.tts.ReadAloud
import atom.voice.tts.ReadAloudSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the model capsule shows. [ready] is false when the selection cannot be used (no key, no sign-in, provider
 * off, model removed): the capsule then reads "Not selected" and sending is blocked. [logo] is a brand key for
 * the provider logo, or null for the fallback mark.
 */
data class EngineInfo(
    val name: String?,
    val onDevice: Boolean,
    val missing: Boolean,
    val live: Boolean = false,
    val ready: Boolean = true,
    val logo: String? = null,
    /** Why the selection is not usable, when it is not. */
    val reason: NotReady? = null,
    /** The provider's display name, for "sign in to …" and "enter the key for … again". */
    val providerName: String? = null,
)

/** Why a model selection cannot be used yet. */
enum class NotReady {
    /** No usable selection at all, or the provider has no key. */
    Configure,

    /** An account provider (ChatGPT, Grok) that is not signed in. */
    SignIn,

    /** The stored key could not be decrypted (for example after a restore to another phone). */
    KeyUnreadable,

    /** An on-device model is selected but the optional on-device AI pack is not installed (or must be updated). */
    NeedsPack,
}

/** The reasoning chip and effort picker for the current conversation's model. */
data class ReasoningUi(val support: ReasoningSupport, val enabled: Boolean, val effort: ReasoningEffort?)

data class ContextInfo(val usedTokens: Int, val windowTokens: Int?) {
    val fraction: Float? get() = windowTokens?.takeIf { it > 0 }?.let { (usedTokens.toFloat() / it).coerceIn(0f, 1f) }
}

/** Editing a past user message: sending replaces it and everything after it. */
data class EditState(val index: Int, val original: String)

data class ChatUiState(
    val conversationId: String? = null,
    val title: String? = null,
    val items: List<ChatItem> = emptyList(),
    val running: Boolean = false,
    val phase: RunPhase? = null,
    val outcome: RunOutcome? = null,
    val pending: PendingApproval? = null,
    val retry: RetryNotice? = null,
    val notice: String? = null,
    val compacting: Boolean = false,
    val currentTool: String? = null,
    /** A provider-hosted tool running right now ("Running Web search"). */
    val hostedTool: atom.core.llm.HostedToolKind? = null,
    val engine: EngineInfo = EngineInfo(null, onDevice = false, missing = false),
    val reasoning: ReasoningUi = ReasoningUi(ReasoningSupport.None, enabled = false, effort = null),
    val queued: List<QueuedMessage> = emptyList(),
    val context: ContextInfo? = null,
    val stats: SessionStats = SessionStats(),
    val pinned: Boolean = false,
    val canEdit: Boolean = true,
    val selection: EngineSelection? = null,
    /** The live or last run of this conversation (subagents are filed under it). */
    val runId: String? = null,
    /** A character chat: regenerate makes switchable candidates, edits keep later turns. */
    val characterChat: Boolean = false,
    /** "<name> is replying…" while a character reply streams. */
    val replyingLabel: String? = null,
    /** Reply candidates by stored message index (character chats). */
    val candidates: Map<Int, CandidateSet> = emptyMap(),
    /** The character of a character chat (its avatar and name on the empty state and in the composer). */
    val character: Character? = null,
    /** The saved conversation could not be read; the chat offers a retry. */
    val loadFailed: Boolean = false,
    /** The model capsule can open the picker: some provider or on-device model exists. */
    val hasProviders: Boolean = true,
)

/** A drawer row: the stored summary plus what a live run is doing right now. */
data class DrawerRow(
    val summary: ConversationSummary,
    val running: Boolean,
    val activity: DrawerActivity?,
    val snippet: String? = null,
    /** Character chats: the character, for the row's avatar and second line. */
    val character: Character? = null,
)

sealed interface DrawerActivity {
    data object Reasoning : DrawerActivity
    data class Tool(val name: String) : DrawerActivity
    data class Answering(val text: String) : DrawerActivity
    data object Waiting : DrawerActivity
}

sealed interface ChatEvent {
    data class Toast(val message: Int, val arg: String? = null, val arg2: String? = null) : ChatEvent

    /** Sending was blocked because no usable model is selected. */
    data object NeedsModel : ChatEvent
    data class Compacted(val result: CompactResult) : ChatEvent
}

private data class Selected(
    val id: String?,
    val stored: Conversation?,
    val draft: Conversation,
    val bindings: Map<String, ChatBinding>,
    val loadFailed: String?,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ChatViewModel(private val graph: AppGraph) : ViewModel() {
    private val runner = graph.tasks
    private val selectedId = MutableStateFlow<String?>(null)
    private val loaded = MutableStateFlow<Conversation?>(null)
    private val loadFailed = MutableStateFlow<String?>(null)

    /** Model and reasoning chosen before the first message of a new conversation. */
    private val draft = MutableStateFlow(Conversation.empty())

    private val _attachments = MutableStateFlow<List<Attachment>>(emptyList())
    val attachments: StateFlow<List<Attachment>> = _attachments.asStateFlow()

    private val _editing = MutableStateFlow<EditState?>(null)
    val editing: StateFlow<EditState?> = _editing.asStateFlow()

    /** Text to put in the composer (share, edit); the UI takes it with [consumePrefill]. */
    private val _prefill = MutableStateFlow<String?>(null)
    val prefill: StateFlow<String?> = _prefill.asStateFlow()

    private val _events = MutableStateFlow<List<ChatEvent>>(emptyList())
    val events: StateFlow<List<ChatEvent>> = _events.asStateFlow()

    /** Where to scroll when a conversation opens; taken once by the UI. */
    private val _restore = MutableStateFlow<Pair<String, ReadingPosition>?>(null)
    val restore: StateFlow<Pair<String, ReadingPosition>?> = _restore.asStateFlow()

    val search = MutableStateFlow("")

    private val summaries: StateFlow<List<ConversationSummary>> =
        graph.conversations.summaries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val matches: StateFlow<List<ConversationMatch>?> = search
        .debounce(220)
        .map { it.trim() }
        .distinctUntilChanged()
        .mapLatest { q -> if (q.isEmpty()) null else graph.conversations.search(q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val bindings: StateFlow<Map<String, ChatBinding>> =
        runCatching { CharacterSession.bindings }.getOrElse { MutableStateFlow(emptyMap()) }

    val drawer: StateFlow<List<DrawerRow>> = combine(summaries, runner.liveRuns, matches, bindings) { list, runs, found, b ->
        val rows = list.map { s -> rowFor(s, runs[s.id], b[s.id]?.snapshot) }
        val known = list.map { it.id }.toSet()
        // A brand-new conversation that has not been saved into the index yet.
        val extra = runs.values.filter { it.conversation.id !in known && it.active }.map { run ->
            rowFor(ConversationSummary(run.conversation.id, run.conversation.title, run.startedAt), run, b[run.conversation.id]?.snapshot)
        }
        val all = extra + rows
        if (found == null) all else {
            val snippets = found.associate { it.id to it.snippet }
            all.filter { it.summary.id in snippets }.map { it.copy(snippet = snippets[it.summary.id]?.ifBlank { null }) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val queue = selectedId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else runner.queue(id).items }

    private val settingsFlow = graph.settings.settings

    /** Null until the provider list, key presence and installed models have loaded (treated as usable). */
    private val readiness: StateFlow<Readiness?> = Readiness.of(graph)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Subagent runs of the selected conversation's current or last run (in memory only). */
    val subagents: StateFlow<List<SubagentRun>> = combine(selectedId, runner.liveRuns, SubagentRuns.runs) { id, runs, all ->
        val runId = id?.let { runs[it]?.runId } ?: return@combine emptyList()
        all.filter { it.parentTaskId == runId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Converting a stored conversation is O(messages); redo it only when the record or the stream changes.
    private var memoKey: Any? = null
    private var memoItems: List<ChatItem> = emptyList()

    val state: StateFlow<ChatUiState> = combine(
        combine(selectedId, loaded, draft, bindings, loadFailed) { id, stored, d, b, failed -> Selected(id, stored, d, b, failed) },
        runner.liveRuns,
        settingsFlow,
        queue,
        readiness,
    ) { (id, stored, d, b, failed), runs, settings, queued, ready ->
        val live = id?.let { runs[it] }
        // A finished run's copy is newer than the file until the saved version has been read back.
        val conversation: Conversation = when {
            live != null && live.active -> live.conversation
            live != null && (stored == null || stored.id != id || live.conversation.updatedAt > stored.updatedAt) -> live.conversation
            stored != null && stored.id == id -> stored
            live != null -> live.conversation
            else -> d
        }
        val running = live?.active == true
        val selection = conversation.selection ?: settings.selection
        val info = graph.modelInfo
        val support = runCatching { info.reasoning(selection) }.getOrNull()
            ?.let { ReasoningSupport.of(it.supported, it.levels) } ?: ReasoningSupport.None
        val enabled = conversation.reasoningEnabled ?: settings.reasoningByDefault
        val effort = support.snap(conversation.reasoningEffort ?: settings.defaultReasoning)
        ChatUiState(
            conversationId = id,
            title = conversation.title.takeIf { id != null },
            items = itemsOf(conversation, live),
            running = running,
            phase = live?.phase,
            outcome = live?.outcome?.takeIf { !running },
            pending = live?.pending?.takeIf { running },
            retry = live?.retry?.takeIf { running },
            notice = live?.notice,
            compacting = live?.compacting == true,
            currentTool = live?.currentTool?.takeIf { running },
            hostedTool = live?.hostedTool?.takeIf { running },
            engine = engineInfo(selection, live, ready),
            reasoning = ReasoningUi(support, enabled, effort),
            queued = queued,
            context = (conversation.contextTokens)?.let { ContextInfo(it, conversation.contextWindow) },
            stats = conversation.stats,
            pinned = conversation.pinned,
            canEdit = !running,
            selection = selection,
            runId = live?.runId,
            characterChat = id != null && b.containsKey(id),
            replyingLabel = live?.replyingLabel?.takeIf { running },
            candidates = if (id != null) candidatesOf(conversation, b[id]) else emptyMap(),
            character = id?.let { b[it]?.snapshot },
            loadFailed = id != null && failed == id && stored?.id != id && live == null,
            hasProviders = ready == null || ready.providers.any { it.enabled } || (ready.packReady && ready.installed.isNotEmpty()),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    init {
        // Reattach to a run that is still going (e.g. after the activity was recreated).
        runner.live.value?.let { if (it.active) selectedId.value = it.conversation.id }
        viewModelScope.launch {
            graph.conversations.changes.collect { changed ->
                if (changed != selectedId.value) return@collect
                loaded.value = graph.conversations.load(changed)
            }
        }
        viewModelScope.launch {
            graph.chatRequests.collect { requests -> requests.forEach { handle(it) } }
        }
        viewModelScope.launch { runCatching { CharacterSession.store.load() } }
        // Voice smart correction and other helpers follow the conversation on screen (null for a new chat).
        viewModelScope.launch { selectedId.collect { graph.focusedConversationId = it } }
    }

    /**
     * Share-to-Atom: starts a new conversation with [text] in the composer and [uris] attached (images as images,
     * anything else as a path). Call from the activity that received the share, while its URI grants are valid.
     */
    fun prefillShared(context: Context, text: String, uris: List<Uri>) {
        newChat()
        if (text.isNotBlank()) _prefill.value = text
        if (uris.isEmpty()) return
        val app = context.applicationContext
        val mimes = uris.map { runCatching { app.contentResolver.getType(it) }.getOrNull().orEmpty() }
        val images = uris.filterIndexed { i, _ -> mimes[i].startsWith("image/") }
        addImages(app, images)
        addFiles(app, uris - images.toSet())
    }

    private fun handle(request: ChatRequest) {
        graph.consumeChatRequest(request)
        when (request) {
            is ChatRequest.Open -> open(request.conversationId)
            is ChatRequest.Share -> {
                newChat()
                _attachments.value = request.attachments
                if (request.text.isNotBlank()) _prefill.value = request.text
                if (request.rejected > 0) emit(ChatEvent.Toast(atom.app.R.string.attach_some_failed, request.rejected.toString()))
            }
        }
    }

    // ------------------------------------------------------------------ Navigation

    fun open(id: String) {
        if (id == selectedId.value) return
        leaveCurrent()
        selectedId.value = id
        loaded.value = null
        load(id)
    }

    private fun load(id: String) {
        loadFailed.value = null
        viewModelScope.launch {
            val c = runCatching { graph.conversations.load(id) }.getOrNull() ?: startCharacterChat(id)
            if (selectedId.value != id) return@launch
            loaded.value = c
            // A conversation the index lists but whose file could not be read: offer a retry, not a blank chat.
            if (c == null && !runner.isRunning(id) && summaries.value.any { it.id == id }) loadFailed.value = id
            graph.runtimePrefs.readingPosition(id)?.let { _restore.value = id to it }
        }
    }

    /** "Load failed. Tap to retry". */
    fun retryLoad() {
        selectedId.value?.let(::load)
    }

    /** A character chat opened for the first time: "The story begins here", then the chosen greeting. */
    private suspend fun startCharacterChat(id: String): Conversation? {
        if (runner.isRunning(id)) return null
        val opening = runCatching { CharacterSession.opening(id) }.getOrNull() ?: return null
        val now = System.currentTimeMillis()
        val c = Conversation(
            id = id,
            title = opening.characterName,
            createdAt = now,
            updatedAt = now,
            messages = listOf(
                StoredMessage.notice(opening.marker, now),
                StoredMessage(ChatMessage.assistant(opening.text), createdAt = now + 1),
            ),
            titleSource = TitleSource.User,
        )
        graph.conversations.save(c)
        return c
    }

    fun newChat() {
        leaveCurrent()
        selectedId.value = null
        loaded.value = null
        draft.value = Conversation.empty()
    }

    private fun leaveCurrent() {
        _editing.value = null
        selectedId.value?.let { runner.clearFinished(it) }
    }

    fun consumeRestore() {
        _restore.value = null
    }

    fun savePosition(conversationId: String, itemKey: String?, offset: Int) {
        viewModelScope.launch {
            graph.runtimePrefs.setReadingPosition(conversationId, itemKey?.let { ReadingPosition(it, offset) })
        }
    }

    fun consumePrefill() {
        _prefill.value = null
    }

    fun consumeEvent(event: ChatEvent) = _events.update { it - event }

    private fun emit(event: ChatEvent) = _events.update { it + event }

    // ------------------------------------------------------------------ Sending

    /** Returns true when the text was taken (sent, queued or used for an edit). */
    fun send(text: String): Boolean {
        val atts = _attachments.value
        if (text.isBlank() && atts.isEmpty()) return false
        val engine = state.value.engine
        if (!engine.live && (state.value.selection == null || engine.missing || !engine.ready)) {
            emit(ChatEvent.NeedsModel)
            return false
        }
        val edit = _editing.value
        val current = currentConversation()
        if (edit != null && current != null) {
            if (runner.isRunning(current.id)) return false
            val original = current.messages.getOrNull(edit.index) ?: return false
            val replaced = original.copy(
                message = ChatMessage.user(text.trim()),
                attachments = original.attachments + atts,
                createdAt = System.currentTimeMillis(),
                edited = true,
            )
            val truncated = current.copy(messages = current.messages.take(edit.index) + replaced)
            _editing.value = null
            _attachments.value = emptyList()
            viewModelScope.launch {
                graph.conversations.save(truncated)
                runner.resend(truncated)
            }
            return true
        }
        val id = runner.send(current ?: draft.value, text, atts) ?: return false
        _attachments.value = emptyList()
        if (selectedId.value != id) {
            selectedId.value = id
            loaded.value = null
        }
        return true
    }

    fun stop() {
        selectedId.value?.let(runner::stop)
    }

    fun pause() {
        selectedId.value?.let(runner::pause)
    }

    fun resume() {
        selectedId.value?.let(runner::resume)
    }

    fun answer(id: Long, approved: Boolean) {
        runner.answer(id, approved)
        graph.notifications.cancelApproval()
    }

    fun removeQueued(id: Long) {
        selectedId.value?.let { runner.removeQueued(it, id) }
    }

    fun sendNow(id: Long) {
        selectedId.value?.let { runner.sendNow(it, id) }
    }

    // ------------------------------------------------------------------ Attachments

    fun addImages(context: Context, uris: List<Uri>) {
        viewModelScope.launch {
            var tooMany = false
            for (uri in uris) {
                if (_attachments.value.count { it.kind == AttachmentKind.Image } >= Attachments.MAX_IMAGES) {
                    tooMany = true
                    break
                }
                when (val r = Attachments.importImage(context, uri)) {
                    is Attachments.Outcome.Added -> _attachments.update { it + r.attachment }
                    is Attachments.Outcome.Rejected -> emit(
                        ChatEvent.Toast(if (r.reason == Attachments.Reason.TooLarge) atom.app.R.string.attach_too_large else atom.app.R.string.attach_unreadable),
                    )
                }
            }
            if (tooMany) emit(ChatEvent.Toast(atom.app.R.string.attach_image_limit, Attachments.MAX_IMAGES.toString()))
        }
    }

    fun addFiles(context: Context, uris: List<Uri>) {
        val startedIn = selectedId.value
        viewModelScope.launch {
            val added = mutableListOf<Attachment>()
            var failed = 0
            for (uri in uris) {
                when (val r = Attachments.importFile(context, uri)) {
                    is Attachments.Outcome.Added -> added += r.attachment
                    is Attachments.Outcome.Rejected -> failed++
                }
            }
            // The picker returns asynchronously; a reference picked for another conversation is not attached here.
            if (selectedId.value != startedIn) {
                // Nothing will send them, so the copies made for them go too.
                withContext(Dispatchers.IO) { Attachments.discardCopies(context, added) }
                if (added.isNotEmpty() || failed > 0) emit(ChatEvent.Toast(atom.app.R.string.attach_switched))
                return@launch
            }
            _attachments.update { it + added }
            when {
                added.isEmpty() && failed > 0 -> emit(ChatEvent.Toast(atom.app.R.string.attach_unreadable))
                failed > 0 -> emit(ChatEvent.Toast(atom.app.R.string.attach_partial, added.size.toString(), failed.toString()))
            }
        }
    }

    fun addFolder(uri: Uri) {
        val folder = Attachments.importFolder(uri)
        if (folder == null) {
            emit(ChatEvent.Toast(atom.app.R.string.attach_folder_unsupported))
        } else {
            _attachments.update { it + folder }
            emit(ChatEvent.Toast(atom.app.R.string.attach_path_added))
        }
    }

    fun addPath(path: String): Boolean {
        val a = Attachments.fromPath(path) ?: return false
        _attachments.update { it + a }
        emit(ChatEvent.Toast(atom.app.R.string.attach_path_added))
        return true
    }

    /** "Continue editing" from the image viewer: the picture goes back into the composer as a new attachment. */
    fun reattachImage(context: Context, path: String) {
        viewModelScope.launch {
            if (_attachments.value.count { it.kind == AttachmentKind.Image } >= Attachments.MAX_IMAGES) {
                emit(ChatEvent.Toast(atom.app.R.string.attach_image_limit, Attachments.MAX_IMAGES.toString()))
                return@launch
            }
            when (val r = Attachments.importImage(context, Uri.fromFile(java.io.File(path)))) {
                is Attachments.Outcome.Added -> {
                    _attachments.update { it + r.attachment }
                    emit(ChatEvent.Toast(atom.app.R.string.image_reattached))
                }
                is Attachments.Outcome.Rejected -> emit(ChatEvent.Toast(atom.app.R.string.attach_unreadable))
            }
        }
    }

    fun removeAttachment(a: Attachment) {
        _attachments.update { it - a }
        if (a.kind == AttachmentKind.Image) Attachments.deleteImages(listOf(a))
    }

    // ------------------------------------------------------------------ Model and reasoning

    /** For the model picker: the chosen model is saved on this conversation. */
    fun setModel(selection: EngineSelection) = editMeta { it.copy(selection = selection) }

    fun setReasoningEnabled(enabled: Boolean) = editMeta { it.copy(reasoningEnabled = enabled) }

    /** [effort] null means "model default"; [ReasoningEffort.Off] switches reasoning off. */
    fun setEffort(effort: ReasoningEffort?) = editMeta {
        if (effort == ReasoningEffort.Off) it.copy(reasoningEnabled = false) else it.copy(reasoningEnabled = true, reasoningEffort = effort)
    }

    private fun editMeta(change: (Conversation) -> Conversation) {
        val id = selectedId.value
        if (id == null) {
            draft.update(change)
            return
        }
        viewModelScope.launch {
            val saved = runner.editConversation(id, change)
            if (saved == null) draft.update(change) else if (!runner.isRunning(id)) loaded.value = saved
        }
    }

    // ------------------------------------------------------------------ Conversation actions

    fun togglePin(id: String) {
        viewModelScope.launch { runner.editConversation(id) { it.copy(pinned = !it.pinned) } }
    }

    fun rename(id: String, title: String) {
        val clean = title.trim().take(120)
        if (clean.isEmpty()) return
        viewModelScope.launch { runner.editConversation(id) { it.copy(title = clean, titleSource = TitleSource.User) } }
    }

    /** Deletes [id]; a running conversation is stopped and saved first and kept if saving fails. */
    fun delete(id: String) {
        viewModelScope.launch {
            if (runner.isRunning(id) && !runner.stopAndSave(id)) {
                emit(ChatEvent.Toast(atom.app.R.string.delete_save_failed))
                return@launch
            }
            runner.clearFinished(id)
            runner.queue(id).clear()
            runCatching { CharacterSession.forget(id) }
            graph.conversations.load(id)?.let { c -> Attachments.deleteImages(c.messages.flatMap { it.attachments }) }
            graph.conversations.delete(id)
            graph.runtimePrefs.setReadingPosition(id, null)
            if (selectedId.value == id) newChat()
        }
    }

    suspend fun exportMarkdown(id: String): Pair<String, String>? {
        val c = runner.liveRuns.value[id]?.takeIf { it.active }?.conversation ?: graph.conversations.load(id) ?: return null
        return ConversationExport.fileName(c) to ConversationExport.markdown(c)
    }

    fun compact() {
        val id = selectedId.value ?: return
        viewModelScope.launch { emit(ChatEvent.Compacted(runner.compact(id))) }
    }

    // ------------------------------------------------------------------ Message actions

    /** Visible user turns after stored index [index]: what regenerating or deleting there removes. */
    fun laterTurns(index: Int): Int = currentConversation()?.messages?.drop(index + 1)?.count { it.isVisibleUser } ?: 0

    fun regenerate(turn: ChatItem.Turn) {
        val c = currentConversation() ?: return
        if (runner.isRunning(c.id)) return
        if (state.value.characterChat) {
            turn.answerIndex?.let { runner.regenerateCharacterReply(c, it) }
            return
        }
        val u = turn.userIndex ?: return
        val truncated = c.copy(messages = c.messages.take(u + 1))
        viewModelScope.launch {
            graph.conversations.save(truncated)
            runner.resend(truncated)
        }
    }

    fun startEdit(user: ChatItem.User) {
        _editing.value = EditState(user.index, user.text)
        _prefill.value = user.text
    }

    fun cancelEdit() {
        _editing.value = null
        _prefill.value = ""
    }

    /** Removes the message at stored [index] and every later turn. */
    fun deleteFrom(index: Int) {
        val c = currentConversation() ?: return
        if (runner.isRunning(c.id)) return
        val removed = c.messages.drop(index)
        viewModelScope.launch {
            Attachments.deleteImages(removed.flatMap { it.attachments })
            graph.conversations.save(c.copy(messages = c.messages.take(index), updatedAt = System.currentTimeMillis()))
        }
    }

    /** A new conversation with the history up to the end of [turn], named "<title> · Branch". */
    fun branch(turn: ChatItem.Turn, branchSuffix: String) {
        val c = currentConversation() ?: return
        val end = turn.answerIndex ?: return
        val now = System.currentTimeMillis()
        val copy = Conversation(
            id = Conversation.newId(),
            title = (c.title.ifBlank { "Conversation" }) + " · " + branchSuffix,
            createdAt = now,
            updatedAt = now,
            messages = c.messages.take(end + 1).map { m -> m.copy(attachments = m.attachments.filter { it.kind != AttachmentKind.Image }) },
            titleSource = TitleSource.User,
            selection = c.selection,
            reasoningEnabled = c.reasoningEnabled,
            reasoningEffort = c.reasoningEffort,
        )
        viewModelScope.launch {
            graph.conversations.save(copy)
            open(copy.id)
        }
    }

    /** Character chats: shows reply candidate [index] for the message at stored [answerIndex]. */
    fun selectCandidate(answerIndex: Int, index: Int) {
        val id = selectedId.value ?: return
        viewModelScope.launch {
            if (runner.selectCandidate(id, answerIndex, index)) loaded.value = graph.conversations.load(id)
        }
    }

    /** Character chats: edits a user or character message in place; later turns stay. */
    fun editInPlace(index: Int, text: String) {
        val id = selectedId.value ?: return
        viewModelScope.launch {
            if (runner.editInPlace(id, index, text)) loaded.value = graph.conversations.load(id)
        }
    }

    /** Reads an answer aloud (Markdown filtered by the speech settings); [messageId] tags it for a stop button. */
    fun readAloud(text: String, messageId: String) {
        ReadAloud.speak(text, ReadAloudSource.Chat, messageId)
    }

    fun stopReading() = ReadAloud.stop()

    /** Raw text of an answer or message, Markdown and LaTeX source kept. */
    fun textAt(index: Int?): String? = index?.let { currentConversation()?.messages?.getOrNull(it)?.message?.text }

    // ------------------------------------------------------------------ Helpers

    private fun currentConversation(): Conversation? {
        val id = selectedId.value ?: return null
        val live = runner.liveRuns.value[id]
        if (live != null && live.active) return live.conversation
        return loaded.value?.takeIf { it.id == id } ?: live?.conversation
    }

    private fun itemsOf(conversation: Conversation, live: LiveRun?): List<ChatItem> {
        val stream = live?.takeIf { it.active }?.stream
        val key = conversation.messages to stream
        if (key != memoKey) {
            memoKey = key
            memoItems = Transcript.of(conversation.messages, stream)
        }
        return memoItems
    }

    private fun candidatesOf(conversation: Conversation, binding: ChatBinding?): Map<Int, CandidateSet> {
        if (binding == null || binding.candidates.isEmpty()) return emptyMap()
        val out = HashMap<Int, CandidateSet>()
        conversation.messages.forEachIndexed { i, m ->
            if (m.message.role == Role.Assistant) binding.candidates[m.createdAt.toString()]?.takeIf { it.texts.size > 1 }?.let { out[i] = it }
        }
        return out
    }

    private fun engineInfo(selection: EngineSelection?, live: LiveRun?, ready: Readiness?): EngineInfo {
        val logo = selection?.let { logoKey(it) }
        if (live != null && live.active && live.engineLabel != null) {
            return EngineInfo(live.engineLabel, live.onDevice, missing = false, live = true, ready = true, logo = logo)
        }
        if (selection == null) return EngineInfo(null, onDevice = false, missing = false, ready = false, reason = NotReady.Configure)
        val info = graph.modelInfo
        val name = runCatching { info.modelName(selection) }.getOrNull()
        val exists = runCatching { info.exists(selection) }.getOrDefault(true)
        val reason = notReadyReason(selection, ready, info)
        val cloud = selection as? EngineSelection.Cloud
        val provider = cloud?.let { runCatching { info.provider(it) }.getOrNull() }
        return EngineInfo(
            name,
            selection is EngineSelection.Local,
            missing = !exists,
            ready = reason == null,
            logo = logo,
            reason = reason,
            providerName = provider?.displayName,
        )
    }

    private fun logoKey(selection: EngineSelection): String? = runCatching {
        when (selection) {
            is EngineSelection.Cloud -> {
                val provider = graph.modelInfo.provider(selection)
                val modelName = provider?.model(selection.model)?.displayName
                ProviderBrands.key(provider?.presetId, selection.model) ?: modelName?.let { ProviderBrands.modelKey(it) }
            }
            is EngineSelection.Local -> ProviderBrands.key(null, model = graph.modelInfo.modelName(selection))
        }
    }.getOrNull()

    private fun rowFor(s: ConversationSummary, run: LiveRun?, character: Character? = null): DrawerRow {
        val active = run?.active == true
        val activity = when {
            !active -> null
            run.pending != null -> DrawerActivity.Waiting
            run.currentTool != null -> DrawerActivity.Tool(run.currentTool)
            run.stream.text.isNotEmpty() -> DrawerActivity.Answering(run.stream.text.takeLast(80).replace('\n', ' '))
            else -> DrawerActivity.Reasoning
        }
        val title = run?.conversation?.title?.takeIf { it.isNotBlank() } ?: s.title
        return DrawerRow(s.copy(title = title), active, activity, character = character)
    }

    override fun onCleared() {
        if (graph.focusedConversationId == selectedId.value) graph.focusedConversationId = null
        selectedId.value?.let { runner.clearFinished(it) }
        super.onCleared()
    }
}
