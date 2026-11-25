package atom.app

import android.app.Application
import android.content.Context
import atom.app.agent.TaskRunner
import atom.app.data.Attachment
import atom.app.data.ConversationStore
import atom.app.data.KeyVault
import atom.app.data.RunJournal
import atom.app.data.RuntimePrefs
import atom.app.data.SettingsRepository
import atom.app.data.runtimeDataStore
import atom.app.data.settingsDataStore
import atom.app.data.vaultDataStore
import atom.app.data.ModelRoles
import atom.app.engine.EngineRouter
import atom.app.engine.ModelInfo
import atom.app.service.RunPower
import atom.app.service.TaskNotifications
import atom.device.ShellExecutor
import atom.device.ShellTier
import atom.device.Shells
import atom.device.power.PowerGovernor
import atom.models.ModelStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Something another entry point (notification, share sheet) wants the chat to do next. */
sealed interface ChatRequest {
    data class Open(val conversationId: String) : ChatRequest

    /** Items shared to Atom: start a new conversation with them in the composer. */
    data class Share(val text: String, val attachments: List<Attachment>, val rejected: Int = 0) : ChatRequest
}

/**
 * Hand-wired singletons. Everything heavy is lazy so a cold start (or a trim-memory callback) only pays
 * for what it touches.
 */
class AppGraph(private val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository(app.settingsDataStore)
    val vault = KeyVault(app.vaultDataStore)
    val conversations = ConversationStore(File(app.filesDir, "atom4_conversations"))
    val journal = RunJournal(File(app.filesDir, "atom4_run-journal"))
    val runtimePrefs = RuntimePrefs(app.runtimeDataStore)
    val notifications = TaskNotifications(app)

    val models: ModelStore by lazy { ModelStore.get(app) }
    val power: PowerGovernor by lazy { PowerGovernor(app) }
    val runPower: RunPower by lazy { RunPower(app, appScope) }

    val router: EngineRouter by lazy {
        EngineRouter(app, settings, vault, models = { models }, power = { power }, scope = appScope)
    }

    val tasks: TaskRunner by lazy { TaskRunner(app, this) }

    /** Reasoning levels, context window and labels of a model selection. */
    val modelInfo: ModelInfo by lazy { ModelInfo.get(app) }

    /** Per-role model choices (titles, assistant, schedules...). */
    val modelRoles: ModelRoles by lazy { ModelRoles.get(app) }

    private val _chatRequests = MutableStateFlow<List<ChatRequest>>(emptyList())

    /** Pending requests for the chat, consumed with [consumeChatRequest]. */
    val chatRequests: StateFlow<List<ChatRequest>> = _chatRequests.asStateFlow()

    fun postChatRequest(request: ChatRequest) = _chatRequests.update { it + request }

    fun consumeChatRequest(request: ChatRequest) = _chatRequests.update { it - request }

    /** True while an activity is started; used to decide whether a finished task deserves a notification. */
    @Volatile
    var uiVisible: Boolean = false

    private val shellMutex = Mutex()
    private var shell: ShellExecutor? = null
    private val _shellTier = MutableStateFlow<ShellTier?>(null)

    /** Null until the first detection has run. */
    val shellTier: StateFlow<ShellTier?> = _shellTier.asStateFlow()

    /** Detected lazily on first use (root and Shizuku probing is not free). */
    suspend fun shell(): ShellExecutor = shellMutex.withLock {
        shell ?: Shells.detect(app).also {
            shell = it
            _shellTier.value = it.tier
        }
    }

    /** Re-probes after the user grants root or Shizuku. */
    suspend fun redetectShell(): ShellTier = shellMutex.withLock {
        Shells.detect(app).also {
            shell = it
            _shellTier.value = it.tier
        }.tier
    }
}

val Context.graph: AppGraph get() = (applicationContext as AtomApp).graph
