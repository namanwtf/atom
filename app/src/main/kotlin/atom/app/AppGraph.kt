package atom.app

import android.app.Application
import android.content.Context
import atom.app.agent.TaskRunner
import atom.app.data.ConversationStore
import atom.app.data.KeyVault
import atom.app.data.SettingsRepository
import atom.app.data.settingsDataStore
import atom.app.data.vaultDataStore
import atom.app.engine.EngineRouter
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Hand-wired singletons. Everything heavy is lazy so a cold start (or a trim-memory callback) only pays
 * for what it touches.
 */
class AppGraph(private val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository(app.settingsDataStore)
    val vault = KeyVault(app.vaultDataStore)
    val conversations = ConversationStore(File(app.filesDir, "conversations"))
    val notifications = TaskNotifications(app)

    val models: ModelStore by lazy { ModelStore.get(app) }
    val power: PowerGovernor by lazy { PowerGovernor(app) }

    val router: EngineRouter by lazy {
        EngineRouter(app, settings, vault, models = { models }, power = { power }, scope = appScope)
    }

    val tasks: TaskRunner by lazy { TaskRunner(app, this) }

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
