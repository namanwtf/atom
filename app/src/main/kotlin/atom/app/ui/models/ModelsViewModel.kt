package atom.app.ui.models

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import atom.app.AppGraph
import atom.app.data.AppSettings
import atom.app.data.EngineSelection
import atom.app.engine.ProviderResolver
import atom.app.ui.common.marketedRamGb
import atom.core.llm.ChatMessage
import atom.core.llm.LlmEvent
import atom.core.llm.LlmRequest
import atom.llm.cloud.CloudEngines
import atom.llm.cloud.Provider
import atom.llm.cloud.Providers
import atom.models.CatalogModel
import atom.models.DownloadState
import atom.models.InstalledModel
import atom.models.ModelCatalog
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.withTimeout

sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Ok(val reply: String, val millis: Long) : TestState
    data class Failed(val message: String) : TestState
}

data class CloudDraft(
    val providerId: String,
    val model: String,
    val baseUrl: String,
    val keyInput: String = "",
    val editingKey: Boolean = false,
    val fetchedModels: List<String> = emptyList(),
    val fetching: Boolean = false,
    val fetchError: String? = null,
    val test: TestState = TestState.Idle,
)

class ModelsViewModel(context: Context, private val graph: AppGraph) : ViewModel() {
    private val store = graph.models

    val providers: List<Provider> = ProviderResolver.all()
    val catalog: List<CatalogModel> = ModelCatalog.curated
    val deviceRamGb: Int = run {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
        marketedRamGb(info.totalMem)
    }

    val installed: StateFlow<List<InstalledModel>> = store.installed
    val settings: StateFlow<AppSettings?> =
        graph.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val savedKeys: StateFlow<Set<String>> =
        graph.vault.savedProviders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    val hasHfToken: StateFlow<Boolean> =
        graph.vault.hasHfToken.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _draft = MutableStateFlow(CloudDraft(providers.first().id, providers.first().defaultModel, providers.first().baseUrl))
    val draft: StateFlow<CloudDraft> = _draft.asStateFlow()

    private val _linkModels = MutableStateFlow<List<CatalogModel>>(emptyList())
    val linkModels: StateFlow<List<CatalogModel>> = _linkModels.asStateFlow()
    private val _linkError = MutableStateFlow<String?>(null)
    val linkError: StateFlow<String?> = _linkError.asStateFlow()

    private var autoUseJobs = mutableMapOf<String, Job>()
    private var testJob: Job? = null

    init {
        viewModelScope.launch {
            val current = graph.settings.current().selection
            if (current is EngineSelection.Cloud) {
                val provider = providers.firstOrNull { it.id == current.providerId } ?: return@launch
                _draft.value = CloudDraft(provider.id, current.model, current.baseUrl ?: provider.baseUrl)
            }
        }
    }

    // ------------------------------------------------------------ On-device

    fun progress(modelId: String): Flow<DownloadState> = store.progress(modelId)

    fun download(model: CatalogModel) {
        viewModelScope.launch {
            val s = graph.settings.current()
            store.download(model, hfToken = graph.vault.hfToken(), wifiOnly = s.wifiOnlyDownloads)
            // First model on the phone: make it active the moment it lands, so download is the whole setup.
            if (s.selection == null) watchAndAutoUse(model.id)
        }
    }

    private fun watchAndAutoUse(modelId: String) {
        autoUseJobs[modelId]?.cancel()
        autoUseJobs[modelId] = viewModelScope.launch {
            val end = store.progress(modelId).first { it is DownloadState.Done || it is DownloadState.Failed }
            if (end is DownloadState.Done && graph.settings.current().selection == null) {
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
            graph.settings.setSelection(EngineSelection.Local(modelId, backend ?: atom.app.data.LocalBackend.Auto))
        }
    }

    fun delete(modelId: String) {
        viewModelScope.launch { store.delete(modelId) }
    }

    fun addFromLink(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        val result = store.modelFromUrl(trimmed)
        val model = result.getOrElse {
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

    // ------------------------------------------------------------ Cloud

    fun selectProvider(id: String) {
        val provider = providers.firstOrNull { it.id == id } ?: return
        testJob?.cancel()
        viewModelScope.launch {
            val current = graph.settings.current().selection as? EngineSelection.Cloud
            val model = current?.takeIf { it.providerId == id }?.model ?: provider.defaultModel
            val base = current?.takeIf { it.providerId == id }?.baseUrl ?: provider.baseUrl
            _draft.value = CloudDraft(provider.id, model, base)
        }
    }

    fun editDraft(change: CloudDraft.() -> CloudDraft) = _draft.update { it.change().copy(test = TestState.Idle) }

    fun saveKey() {
        val d = _draft.value
        val key = d.keyInput.trim()
        if (key.isEmpty()) return
        viewModelScope.launch {
            graph.vault.setApiKey(d.providerId, key)
            _draft.update { it.copy(keyInput = "", editingKey = false) }
        }
    }

    fun removeKey() {
        val id = _draft.value.providerId
        viewModelScope.launch { graph.vault.setApiKey(id, null) }
    }

    fun fetchModels() {
        val d = _draft.value
        val provider = resolve(d) ?: return
        _draft.update { it.copy(fetching = true, fetchError = null) }
        viewModelScope.launch {
            try {
                val key = d.keyInput.trim().ifEmpty { graph.vault.apiKey(provider.id).orEmpty() }
                val models = CloudEngines.listModels(provider, key, d.baseUrl.ifBlank { provider.baseUrl })
                _draft.update { it.copy(fetching = false, fetchedModels = models.sorted()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _draft.update { it.copy(fetching = false, fetchError = e.message ?: e::class.simpleName) }
            }
        }
    }

    /** Sends a tiny request so the user knows the key, model and URL work before relying on them. */
    fun test() {
        val d = _draft.value
        val provider = resolve(d) ?: return
        testJob?.cancel()
        _draft.update { it.copy(test = TestState.Running) }
        testJob = viewModelScope.launch {
            val outcome = try {
                val key = d.keyInput.trim().ifEmpty { graph.vault.apiKey(provider.id).orEmpty() }
                val engine = CloudEngines.create(provider, key, d.model.trim(), d.baseUrl.ifBlank { provider.baseUrl })
                val started = SystemClock.elapsedRealtime()
                val reply = StringBuilder()
                withTimeout(30_000) {
                    engine.stream(
                        LlmRequest(
                            model = d.model.trim(),
                            messages = listOf(ChatMessage.user("Reply with the single word: OK")),
                            maxOutputTokens = 32,
                        ),
                    ).collect { if (it is LlmEvent.TextDelta) reply.append(it.text) }
                }
                TestState.Ok(reply.toString().trim().take(40), SystemClock.elapsedRealtime() - started)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TestState.Failed(e.message ?: e::class.simpleName ?: "Error")
            }
            _draft.update { it.copy(test = outcome) }
        }
    }

    fun useCloud() {
        val selection = cloudSelection() ?: return
        viewModelScope.launch {
            if (_draft.value.keyInput.isNotBlank()) saveKeyNow()
            graph.settings.setSelection(selection)
        }
    }

    fun useAsFallback() {
        val selection = cloudSelection() ?: return
        viewModelScope.launch {
            if (_draft.value.keyInput.isNotBlank()) saveKeyNow()
            graph.settings.setFallback(selection)
        }
    }

    private suspend fun saveKeyNow() {
        val d = _draft.value
        graph.vault.setApiKey(d.providerId, d.keyInput)
        _draft.update { it.copy(keyInput = "", editingKey = false) }
    }

    private fun cloudSelection(): EngineSelection.Cloud? {
        val d = _draft.value
        val provider = providers.firstOrNull { it.id == d.providerId } ?: return null
        val model = d.model.trim().ifEmpty { return null }
        val isCustom = provider.id == Providers.CUSTOM_ID
        val base = d.baseUrl.trim().takeIf { it.isNotEmpty() && (isCustom || it != provider.baseUrl) }
        if (isCustom && base == null) return null
        return EngineSelection.Cloud(provider.id, model, base)
    }

    private fun resolve(d: CloudDraft): Provider? =
        providers.firstOrNull { it.id == d.providerId }?.let { p ->
            if (p.id == Providers.CUSTOM_ID) p.copy(baseUrl = d.baseUrl.trim(), defaultModel = d.model.trim()) else p
        }
}
