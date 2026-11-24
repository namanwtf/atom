package atom.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import atom.app.AppGraph
import atom.app.agent.PendingApproval
import atom.app.agent.TaskPhase
import atom.app.agent.Transcript
import atom.app.agent.TranscriptItem
import atom.app.data.Conversation
import atom.app.data.ConversationSummary
import atom.app.data.EngineSelection
import atom.app.engine.ProviderResolver
import atom.models.InstalledModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the engine chip shows. */
sealed interface EngineInfo {
    data object None : EngineInfo
    data class Cloud(val provider: String, val model: String) : EngineInfo
    data class Local(val name: String?, val missing: Boolean) : EngineInfo

    /** Routed engine of the running task (may be a fallback). */
    data class Live(val label: String, val onDevice: Boolean) : EngineInfo
}

data class ChatUiState(
    val conversationId: String? = null,
    val title: String? = null,
    val items: List<TranscriptItem> = emptyList(),
    val running: Boolean = false,
    val phase: TaskPhase? = null,
    val currentTool: String? = null,
    val pending: PendingApproval? = null,
    val notice: String? = null,
    val error: String? = null,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val engine: EngineInfo = EngineInfo.None,
    val busyElsewhere: Boolean = false,
)

class ChatViewModel(private val graph: AppGraph) : ViewModel() {
    private val runner = graph.tasks
    private val selectedId = MutableStateFlow<String?>(null)
    private val loaded = MutableStateFlow<Conversation?>(null)

    val summaries: StateFlow<List<ConversationSummary>> =
        graph.conversations.summaries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val engineInfo = combine(graph.settings.settings, graph.models.installed) { s, installed ->
        describe(s.selection, installed)
    }

    // Converting a stored conversation is O(messages); do it once per conversation instance, not per frame.
    private var memoConversation: Conversation? = null
    private var memoItems: List<TranscriptItem> = emptyList()

    val state: StateFlow<ChatUiState> = combine(selectedId, loaded, runner.live, engineInfo) { id, stored, live, engine ->
        val mine = live != null && id != null && live.conversation.id == id
        val conversation = if (mine) live.conversation else stored?.takeIf { it.id == id }
        val running = mine && live.phase.active
        val base = itemsOf(conversation)
        ChatUiState(
            conversationId = id,
            title = conversation?.title,
            items = if (running) base + live.items else base,
            running = running,
            phase = if (mine) live.phase else null,
            currentTool = if (running) live.currentTool else null,
            pending = if (running) live.pending else null,
            notice = if (mine) live.notice else null,
            error = if (mine && live.phase == TaskPhase.Failed) live.error else null,
            inputTokens = if (mine) live.inputTokens else 0,
            outputTokens = if (mine) live.outputTokens else 0,
            engine = if (running && live.engineLabel != null) EngineInfo.Live(live.engineLabel, live.onDevice) else engine,
            busyElsewhere = live?.phase?.active == true && !mine,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    init {
        // Reattach to a task that is still running (e.g. after the activity was recreated).
        runner.live.value?.let { if (it.phase.active) selectedId.value = it.conversation.id }
    }

    fun open(id: String) {
        if (id == selectedId.value) return
        retainFinished()
        selectedId.value = id
        loaded.value = null
        val live = runner.live.value
        if (live != null && live.conversation.id == id) return
        viewModelScope.launch { loaded.value = graph.conversations.load(id) }
    }

    fun newChat() {
        retainFinished()
        selectedId.value = null
        loaded.value = null
    }

    fun send(text: String): Boolean {
        val base = currentConversation()
        retainFinished()
        val started = runner.start(base, text)
        if (started) selectedId.value = runner.live.value?.conversation?.id
        return started
    }

    fun stop() = runner.stop()

    fun answer(id: Long, approved: Boolean) {
        runner.answer(id, approved)
        graph.notifications.cancelApproval()
    }

    fun delete(id: String) {
        val live = runner.live.value
        if (live != null && live.conversation.id == id && live.phase.active) return
        viewModelScope.launch {
            if (live?.conversation?.id == id) runner.clearFinished()
            graph.conversations.delete(id)
            if (selectedId.value == id) newChat()
        }
    }

    fun dismissBanner() = retainFinished()

    private fun currentConversation(): Conversation? {
        val id = selectedId.value ?: return null
        val live = runner.live.value
        return if (live != null && live.conversation.id == id) live.conversation else loaded.value?.takeIf { it.id == id }
    }

    /** Keeps a finished task's conversation before its live state is cleared. */
    private fun retainFinished() {
        val live = runner.live.value ?: return
        if (live.phase.active) return
        if (live.conversation.id == selectedId.value) loaded.value = live.conversation
        runner.clearFinished()
    }

    private fun itemsOf(conversation: Conversation?): List<TranscriptItem> {
        if (conversation == null) return emptyList()
        if (conversation !== memoConversation) {
            memoConversation = conversation
            memoItems = Transcript.of(conversation)
        }
        return memoItems
    }

    private fun describe(selection: EngineSelection?, installed: List<InstalledModel>): EngineInfo = when (selection) {
        null -> EngineInfo.None
        is EngineSelection.Cloud -> EngineInfo.Cloud(
            provider = ProviderResolver.resolve(selection.providerId, selection.baseUrl, selection.model)?.displayName
                ?: selection.providerId,
            model = selection.model,
        )
        is EngineSelection.Local -> {
            val model = installed.firstOrNull { it.id == selection.installedModelId }
            EngineInfo.Local(model?.name, missing = model == null)
        }
    }
}
