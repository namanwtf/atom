package atom.app.ui.models

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import atom.app.AppGraph
import atom.app.data.AppSettings
import atom.app.data.EngineSelection
import atom.app.data.LocalBackend
import atom.app.data.ModelRole
import atom.app.data.ModelRoles
import atom.app.data.ProviderStore
import atom.app.data.RoleSetting
import atom.app.engine.AccountState
import atom.app.engine.ModelInfo
import atom.app.engine.SubscriptionAuth
import atom.app.ui.common.marketedRamGb
import atom.core.llm.ChatMessage
import atom.core.llm.LlmEvent
import atom.core.llm.LlmRequest
import atom.core.llm.ReasoningEffort
import atom.llm.cloud.AuthMethod
import atom.llm.cloud.CatalogProvider
import atom.llm.cloud.CloudEngines
import atom.llm.cloud.EngineOptions
import atom.llm.cloud.HeaderEntry
import atom.llm.cloud.ModelConfig
import atom.llm.cloud.ModelsDev
import atom.llm.cloud.PresetCatalog
import atom.llm.cloud.ProviderConfig
import atom.llm.cloud.RemoteModel
import atom.llm.cloud.Wire
import atom.models.CatalogModel
import atom.models.DownloadState
import atom.models.InstalledModel
import atom.models.ModelCatalog
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Ok(val reply: String, val millis: Long) : TestState
    data class Failed(val message: String) : TestState
}

/** Result of pulling a provider's remote model list: what is new compared with the configured models. */
data class PullState(
    val loading: Boolean = false,
    val error: String? = null,
    val fresh: List<RemoteModel>? = null,
)

sealed interface CatalogState {
    data object Idle : CatalogState
    data object Loading : CatalogState
    data class Ready(val providers: List<CatalogProvider>, val fetchedAt: Long) : CatalogState
    data class Failed(val message: String) : CatalogState
}

/** Which kind of provider "Add new provider" creates. */
enum class NewProviderKind { OpenAiCompatible, Anthropic, Gemini }

class ModelsViewModel(context: Context, private val graph: AppGraph) : ViewModel() {
    private val app = context.applicationContext
    private val store = graph.models
    private val providerStore = ProviderStore.get(app)
    private val roles = ModelRoles.get(app)
    private val accounts = SubscriptionAuth.get(app)
    val info: ModelInfo = ModelInfo.get(app)

    val catalog: List<CatalogModel> = ModelCatalog.curated
    val deviceRamGb: Int = run {
        val mem = ActivityManager.MemoryInfo()
        app.getSystemService(ActivityManager::class.java)?.getMemoryInfo(mem)
        marketedRamGb(mem.totalMem)
    }

    val installed: StateFlow<List<InstalledModel>> = store.installed
    val settings: StateFlow<AppSettings?> =
        graph.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val savedKeys: StateFlow<Set<String>> =
        graph.vault.savedProviders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    val unreadable: StateFlow<Set<String>> =
        graph.vault.unreadable.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    val signedIn: StateFlow<Set<String>> =
        graph.vault.signedInProviders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    val hasHfToken: StateFlow<Boolean> =
        graph.vault.hasHfToken.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val providers: StateFlow<List<ProviderConfig>> = providerStore.providers
    val roleSettings: StateFlow<Map<ModelRole, RoleSetting>> =
        roles.roles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    val accountStates: StateFlow<Map<String, AccountState>> = accounts.states

    private val _linkModels = MutableStateFlow<List<CatalogModel>>(emptyList())
    val linkModels: StateFlow<List<CatalogModel>> = _linkModels.asStateFlow()
    private val _linkError = MutableStateFlow<String?>(null)
    val linkError: StateFlow<String?> = _linkError.asStateFlow()

    private val _tests = MutableStateFlow<Map<String, TestState>>(emptyMap())
    val tests: StateFlow<Map<String, TestState>> = _tests.asStateFlow()
    private val _pulls = MutableStateFlow<Map<String, PullState>>(emptyMap())
    val pulls: StateFlow<Map<String, PullState>> = _pulls.asStateFlow()
    private val _catalogState = MutableStateFlow<CatalogState>(CatalogState.Idle)
    val catalogState: StateFlow<CatalogState> = _catalogState.asStateFlow()

    /** The decrypted key of the provider whose screen is open; cleared when it closes. */
    private val _keyDraft = MutableStateFlow<Pair<String, String>?>(null)
    val keyDraft: StateFlow<Pair<String, String>?> = _keyDraft.asStateFlow()

    private val autoUseJobs = mutableMapOf<String, Job>()
    private val testJobs = mutableMapOf<String, Job>()

    // ------------------------------------------------------------ On-device

    fun progress(modelId: String): Flow<DownloadState> = store.progress(modelId)

    fun download(model: CatalogModel) {
        viewModelScope.launch {
            val s = graph.settings.current()
            store.download(model, hfToken = graph.vault.hfToken(), wifiOnly = s.wifiOnlyDownloads)
            if (!s.selectionExplicit) watchAndAutoUse(model.id)
        }
    }

    /** First model downloaded before the user chose anything: make it active when it lands. */
    private fun watchAndAutoUse(modelId: String) {
        autoUseJobs[modelId]?.cancel()
        autoUseJobs[modelId] = viewModelScope.launch {
            val end = store.progress(modelId).first { it is DownloadState.Done || it is DownloadState.Failed }
            if (end is DownloadState.Done && !graph.settings.current().selectionExplicit) {
                graph.settings.setSelection(EngineSelection.Local(modelId))
            }
        }
    }

    fun cancel(modelId: String) {
        autoUseJobs.remove(modelId)?.cancel()
        store.cancel(modelId)
    }

    fun use(modelId: String) {
        viewModelScope.launch {
            val current = graph.settings.current().selection
            val backend = (current as? EngineSelection.Local)?.takeIf { it.installedModelId == modelId }?.backend
            graph.settings.setSelection(EngineSelection.Local(modelId, backend ?: LocalBackend.Auto))
        }
    }

    fun delete(modelId: String) {
        viewModelScope.launch { store.delete(modelId) }
    }

    fun addFromLink(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        val model = store.modelFromUrl(trimmed).getOrElse {
            _linkError.value = it.message ?: it::class.simpleName
            return false
        }
        _linkError.value = null
        _linkModels.update { list -> listOf(model) + list.filterNot { it.id == model.id } }
        if (!model.gated || hasHfToken.value) download(model)
        return true
    }

    fun setWifiOnly(value: Boolean) {
        viewModelScope.launch { graph.settings.setWifiOnly(value) }
    }

    fun saveHfToken(token: String?) {
        viewModelScope.launch { graph.vault.setHfToken(token) }
    }

    // ------------------------------------------------------------ Defaults

    fun setDefault(selection: EngineSelection) {
        viewModelScope.launch {
            graph.settings.setSelection(selection)
            if (selection is EngineSelection.Cloud) {
                providerStore.update(selection.providerId) { it.copy(enabled = true, defaultModel = selection.model) }
            }
        }
    }

    fun setFallback(selection: EngineSelection.Cloud?) {
        viewModelScope.launch { graph.settings.setFallback(selection) }
    }

    fun setReasoningByDefault(enabled: Boolean) {
        viewModelScope.launch { graph.settings.setReasoningByDefault(enabled) }
    }

    fun setDefaultReasoning(effort: ReasoningEffort?) {
        viewModelScope.launch { graph.settings.setDefaultReasoning(effort) }
    }

    fun setAutoTitles(enabled: Boolean) {
        viewModelScope.launch { graph.settings.setAutoTitles(enabled) }
    }

    fun setRole(role: ModelRole, setting: RoleSetting) {
        viewModelScope.launch { roles.set(role, setting) }
    }

    // ------------------------------------------------------------ Providers

    fun edit(providerId: String, change: (ProviderConfig) -> ProviderConfig) {
        viewModelScope.launch { providerStore.update(providerId, change) }
    }

    fun openProvider(providerId: String) {
        accounts.refreshStatus(providerId)
        viewModelScope.launch { _keyDraft.value = providerId to graph.vault.apiKey(providerId).orEmpty() }
    }

    fun closeProvider() {
        _keyDraft.value = null
    }

    fun editKey(providerId: String, key: String) {
        _keyDraft.value = providerId to key
    }

    fun saveKey(providerId: String) {
        val key = _keyDraft.value?.takeIf { it.first == providerId }?.second ?: return
        viewModelScope.launch {
            graph.vault.setApiKey(providerId, key.ifBlank { null })
            _tests.update { it - providerId }
        }
    }

    fun setAuth(providerId: String, method: AuthMethod) = edit(providerId) { it.copy(auth = method) }

    fun signIn(providerId: String, method: AuthMethod, reauthorize: Boolean = false) = accounts.signIn(providerId, method, reauthorize)

    fun cancelSignIn(providerId: String) = accounts.cancel(providerId)

    fun signOut(providerId: String) = accounts.signOut(providerId)

    fun syncAccountModels(providerId: String) = accounts.syncModelsNow(providerId)

    fun openUrl(url: String) = accounts.openBrowser(url)

    fun addProvider(kind: NewProviderKind, name: String, baseUrl: String, wire: Wire, key: String, project: String, location: String, onAdded: (String) -> Unit) {
        viewModelScope.launch {
            val trimmedUrl = baseUrl.trim().trimEnd('/')
            val config = ProviderConfig(
                id = name,
                displayName = name.trim().ifEmpty { defaultName(kind) },
                wire = wire,
                baseUrl = trimmedUrl.ifEmpty { if (kind == NewProviderKind.Gemini && wire == Wire.Gemini) PresetCatalog.GEMINI_BASE_URL else "" },
                vertexProject = project.trim(),
                vertexLocation = location.trim().ifEmpty { "global" },
                supportsModelList = wire != Wire.Vertex,
            )
            val added = providerStore.add(config)
            if (key.isNotBlank()) graph.vault.setApiKey(added.id, key)
            onAdded(added.id)
        }
    }

    private fun defaultName(kind: NewProviderKind) = when (kind) {
        NewProviderKind.OpenAiCompatible -> "OpenAI-compatible"
        NewProviderKind.Anthropic -> "Anthropic-compatible"
        NewProviderKind.Gemini -> "Gemini"
    }

    fun removeProvider(providerId: String) {
        viewModelScope.launch {
            if (providerStore.get(providerId)?.auth != AuthMethod.ApiKey) accounts.signOut(providerId)
            providerStore.remove(providerId)
            graph.vault.removeProvider(providerId)
            val s = graph.settings.current()
            if ((s.selection as? EngineSelection.Cloud)?.providerId == providerId) {
                graph.settings.setSelection(AppSettings.DEFAULT_SELECTION)
            }
            if (s.cloudFallback?.providerId == providerId) graph.settings.setFallback(null)
        }
    }

    fun resetProvider(providerId: String) {
        viewModelScope.launch { providerStore.reset(providerId) }
    }

    fun duplicateProvider(providerId: String, suffix: String, onAdded: (String) -> Unit) {
        viewModelScope.launch {
            val copy = providerStore.duplicate(providerId, suffix) ?: return@launch
            graph.vault.copyApiKey(providerId, copy.id)
            onAdded(copy.id)
        }
    }

    // ------------------------------------------------------------ Models

    fun pullModels(providerId: String) {
        _pulls.update { it + (providerId to PullState(loading = true)) }
        viewModelScope.launch {
            val outcome = try {
                val config = providerStore.get(providerId) ?: return@launch
                val key = graph.vault.apiKey(providerId).orEmpty()
                val remote = CloudEngines.listRemoteModels(
                    config.toProvider(),
                    key,
                    config.baseUrl,
                    EngineOptions(headers = config.headers),
                )
                PullState(fresh = remote.filter { r -> config.models.none { it.id == r.id } })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PullState(error = e.message ?: e::class.simpleName)
            }
            _pulls.update { it + (providerId to outcome) }
        }
    }

    fun dismissPull(providerId: String) = _pulls.update { it - providerId }

    fun addPulled(providerId: String, ids: Set<String>) {
        val fresh = _pulls.value[providerId]?.fresh.orEmpty().filter { it.id in ids }
        viewModelScope.launch {
            val config = providerStore.get(providerId) ?: return@launch
            val added = fresh.map { ModelConfig(id = it.id, displayName = it.displayName ?: it.id, detected = it.capabilities) }
            providerStore.setModels(providerId, config.models + added)
            _pulls.update { it + (providerId to PullState(fresh = it[providerId]?.fresh.orEmpty().filterNot { r -> r.id in ids })) }
        }
    }

    fun addCustomModel(providerId: String, displayName: String, modelId: String) {
        val id = modelId.trim().ifEmpty { return }
        viewModelScope.launch {
            val config = providerStore.get(providerId) ?: return@launch
            if (config.models.any { it.id == id }) return@launch
            providerStore.setModels(providerId, config.models + ModelConfig(id = id, displayName = displayName.trim().ifEmpty { id }))
        }
    }

    fun updateModel(providerId: String, modelId: String, change: (ModelConfig) -> ModelConfig) {
        viewModelScope.launch { providerStore.updateModel(providerId, modelId, change) }
    }

    /** Renames a model id too; selections pointing at the old id move with it. */
    fun changeModelId(providerId: String, oldId: String, newId: String, onDone: () -> Unit) {
        val id = newId.trim().ifEmpty { return }
        if (id == oldId) return
        viewModelScope.launch {
            val config = providerStore.get(providerId) ?: return@launch
            if (config.models.any { it.id == id }) return@launch
            providerStore.update(providerId) { c ->
                c.copy(
                    models = c.models.map { if (it.id == oldId) it.copy(id = id, builtIn = false) else it },
                    defaultModel = if (c.defaultModel == oldId) id else c.defaultModel,
                )
            }
            val current = graph.settings.current().selection as? EngineSelection.Cloud
            if (current?.providerId == providerId && current.model == oldId) graph.settings.setSelection(current.copy(model = id))
            onDone()
        }
    }

    fun deleteModels(providerId: String, ids: Set<String>) {
        viewModelScope.launch {
            val config = providerStore.get(providerId) ?: return@launch
            providerStore.setModels(providerId, config.models.filterNot { it.id in ids })
        }
    }

    // ------------------------------------------------------------ Test connection

    /** Sends a tiny request through the real routing path, so keys, sign-in, headers and body are all exercised. */
    fun test(providerId: String, modelId: String) {
        testJobs.remove(providerId)?.cancel()
        _tests.update { it + (providerId to TestState.Running) }
        testJobs[providerId] = viewModelScope.launch {
            _keyDraft.value?.takeIf { it.first == providerId }?.let { (id, key) ->
                if (key != graph.vault.apiKey(id).orEmpty()) graph.vault.setApiKey(id, key.ifBlank { null })
            }
            val outcome = try {
                val selection = EngineSelection.Cloud(providerId, modelId)
                val routed = graph.router.acquire(selection)
                try {
                    val started = SystemClock.elapsedRealtime()
                    val reply = StringBuilder()
                    withTimeout(30_000) {
                        routed.engine.stream(
                            LlmRequest(
                                model = routed.model,
                                messages = listOf(ChatMessage.user("Reply with the single word: OK")),
                                maxOutputTokens = 64,
                            ),
                        ).collect { if (it is LlmEvent.TextDelta) reply.append(it.text) }
                    }
                    TestState.Ok(reply.toString().trim().take(40), SystemClock.elapsedRealtime() - started)
                } finally {
                    graph.router.release(routed)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TestState.Failed(e.message ?: e::class.simpleName ?: "Error")
            }
            _tests.update { it + (providerId to outcome) }
        }
    }

    // ------------------------------------------------------------ models.dev

    private val catalogFile = File(app.cacheDir, "models_dev_catalog.json")

    /** Loads the community catalog from cache, fetching it when missing, stale or [force]d. Never sends keys. */
    fun loadCatalog(force: Boolean = false) {
        if (_catalogState.value is CatalogState.Loading) return
        _catalogState.value = CatalogState.Loading
        viewModelScope.launch {
            val cached = if (!force) readCatalogCache() else null
            if (cached != null && System.currentTimeMillis() - cached.fetchedAt < CATALOG_MAX_AGE_MS) {
                _catalogState.value = cached
                return@launch
            }
            _catalogState.value = try {
                val fresh = withContext(Dispatchers.IO) { ModelsDev.fetch() }
                withContext(Dispatchers.IO) { runCatching { catalogFile.writeText(ModelsDev.encodeCache(fresh)) } }
                CatalogState.Ready(fresh, System.currentTimeMillis())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                cached ?: CatalogState.Failed(e.message ?: e::class.simpleName.orEmpty())
            }
        }
    }

    private suspend fun readCatalogCache(): CatalogState.Ready? = withContext(Dispatchers.IO) {
        if (!catalogFile.exists()) return@withContext null
        val list = ModelsDev.decodeCache(catalogFile.readText()) ?: return@withContext null
        CatalogState.Ready(list, catalogFile.lastModified())
    }

    fun importFromCatalog(provider: CatalogProvider, modelIds: Set<String>, onAdded: (String) -> Unit) {
        viewModelScope.launch {
            val added = providerStore.add(ModelsDev.toConfig(provider, modelIds, provider.id))
            onAdded(added.id)
        }
    }

    // ------------------------------------------------------------ Legacy key helpers

    fun removeKey(providerId: String) {
        viewModelScope.launch { graph.vault.setApiKey(providerId, null) }
        _keyDraft.update { if (it?.first == providerId) providerId to "" else it }
    }

    companion object {
        private const val CATALOG_MAX_AGE_MS = 24 * 60 * 60 * 1000L

        fun selectionOf(config: ProviderConfig, model: ModelConfig) = EngineSelection.Cloud(config.id, model.id)

        val HEADER_TEMPLATE = HeaderEntry("User-Agent", "")
    }
}
