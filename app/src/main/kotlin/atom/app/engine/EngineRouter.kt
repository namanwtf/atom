package atom.app.engine

import android.content.Context
import atom.app.R
import atom.app.data.EngineSelection
import atom.app.data.KeyVault
import atom.app.data.LocalBackend
import atom.app.data.SettingsRepository
import atom.core.agent.AgentConfig
import atom.core.agent.ContextPacker
import atom.core.agent.ContextPolicy
import atom.core.llm.LlmEngine
import atom.core.llm.PromptedToolEngine
import atom.core.llm.Releasable
import atom.core.llm.ToolSpec
import atom.device.power.PowerGovernor
import atom.device.power.PowerState
import atom.device.power.Thermal
import atom.engine.litert.LiteRtBackend
import atom.engine.litert.LiteRtEngine
import atom.engine.llamacpp.LlamaConfig
import atom.engine.llamacpp.LlamaCppEngine
import atom.llm.cloud.CloudEngines
import atom.llm.cloud.Provider
import atom.llm.cloud.Providers
import atom.llm.cloud.Wire
import atom.models.InstalledModel
import atom.models.ModelFormat
import atom.models.ModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A user-facing reason the requested engine cannot run. */
class RouteException(message: String) : Exception(message)

/** The engine a task actually runs on, plus the budget that suits it. */
class RoutedEngine internal constructor(
    val engine: LlmEngine,
    val model: String,
    val label: String,
    val onDevice: Boolean,
    /** Short explanation when routing differs from the user's selection (e.g. hot phone → cloud). */
    val notice: String?,
) {
    fun agentConfig(systemPrompt: String, tools: List<ToolSpec>, slowApprovals: Boolean): AgentConfig {
        val vision = engine.capabilities.vision
        val toolTimeout = if (slowApprovals) 125_000L else 60_000L
        if (!onDevice) {
            return AgentConfig(
                model = model,
                systemPrompt = systemPrompt,
                maxSteps = 30,
                maxOutputTokens = 2048,
                toolTimeoutMs = toolTimeout,
                context = ContextPolicy(keepImages = if (vision) 1 else 0),
            )
        }
        // On-device every prompt token is prefill on the phone's own silicon: keep it small. Tool
        // descriptions are injected into the prompt by PromptedToolEngine, so they count against the window.
        val maxOut = 768
        val toolTokens = tools.sumOf { ContextPacker.estimate(it.name + it.description + it.parameters) + 24 }
        val window = engine.capabilities.contextWindow.takeIf { it > 0 } ?: LOCAL_CONTEXT_WINDOW
        val budget = (window - maxOut - toolTokens - 256).coerceIn(2_500, LOCAL_PROMPT_BUDGET)
        return AgentConfig(
            model = model,
            systemPrompt = systemPrompt,
            maxSteps = 15,
            maxOutputTokens = maxOut,
            toolTimeoutMs = toolTimeout,
            maxRetries = 1,
            context = ContextPolicy(
                budgetTokens = budget,
                keepFullToolResults = 2,
                maxToolResultChars = 3_000,
                compactedToolResultChars = 160,
                keepImages = if (vision) 1 else 0,
            ),
        )
    }

    companion object {
        const val LOCAL_CONTEXT_WINDOW = 8_192
        const val LOCAL_PROMPT_BUDGET = 6_000
    }
}

/**
 * Builds the engine for the current selection. On-device engines are expensive to load, so one is cached
 * and freed after [IDLE_RELEASE_MS] without use (a single delayed job, reset on every use — no polling)
 * or when the system signals memory pressure.
 */
class EngineRouter(
    private val context: Context,
    private val settings: SettingsRepository,
    private val vault: KeyVault,
    private val models: () -> ModelStore,
    private val power: () -> PowerGovernor,
    private val scope: CoroutineScope,
) {
    private data class LocalKey(val modelId: String, val backend: LocalBackend)
    private class CachedLocal(val key: LocalKey, val raw: LlmEngine, val engine: LlmEngine, val model: InstalledModel)

    private val mutex = Mutex()
    private var cached: CachedLocal? = null
    private var inUse = 0
    private var idleJob: Job? = null

    suspend fun acquire(): RoutedEngine = mutex.withLock {
        idleJob?.cancel()
        idleJob = null
        val current = settings.current()
        val selection = current.selection ?: throw RouteException(context.getString(R.string.route_no_engine))
        val routed = when (selection) {
            is EngineSelection.Cloud -> cloud(selection, notice = null)
            is EngineSelection.Local -> {
                val governor = power()
                if (governor.localInferenceAllowed) {
                    local(selection)
                } else {
                    val reason = blockReason(governor.state.value)
                    val fallback = current.cloudFallback
                        ?: throw RouteException(context.getString(R.string.route_local_paused, reason))
                    cloud(fallback, notice = context.getString(R.string.route_fallback_notice, reason))
                }
            }
        }
        inUse++
        routed
    }

    suspend fun release(routed: RoutedEngine) {
        mutex.withLock {
            inUse = (inUse - 1).coerceAtLeast(0)
            if (inUse == 0 && cached != null) scheduleIdleReleaseLocked()
        }
    }

    /** Called from Application.onTrimMemory(TRIM_MEMORY_BACKGROUND or higher). */
    fun onTrimMemory() {
        scope.launch {
            mutex.withLock {
                if (inUse == 0) {
                    idleJob?.cancel()
                    idleJob = null
                    releaseLocalLocked()
                }
            }
        }
    }

    private fun scheduleIdleReleaseLocked() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_RELEASE_MS)
            mutex.withLock { if (inUse == 0) releaseLocalLocked() }
        }
    }

    private suspend fun releaseLocalLocked() {
        val c = cached ?: return
        cached = null
        try {
            (c.raw as? Releasable)?.release()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Native teardown failures must not crash the app; the process will reclaim the memory.
        }
    }

    private suspend fun cloud(selection: EngineSelection.Cloud, notice: String?): RoutedEngine {
        val provider = ProviderResolver.resolve(selection.providerId, selection.baseUrl, selection.model)
            ?: throw RouteException(context.getString(R.string.route_unknown_provider, selection.providerId))
        val key = vault.apiKey(provider.id).orEmpty()
        if (key.isEmpty() && provider.id != Providers.CUSTOM_ID) {
            throw RouteException(context.getString(R.string.route_missing_key, provider.displayName))
        }
        val baseUrl = selection.baseUrl?.takeIf { it.isNotBlank() } ?: provider.baseUrl
        val engine = try {
            CloudEngines.create(provider, key, selection.model, baseUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw RouteException(context.getString(R.string.route_engine_failed, e.message ?: e::class.simpleName))
        }
        return RoutedEngine(
            engine = engine,
            model = selection.model,
            label = context.getString(R.string.engine_label_cloud, provider.displayName, selection.model),
            onDevice = false,
            notice = notice,
        )
    }

    private suspend fun local(selection: EngineSelection.Local): RoutedEngine {
        val installed = models().installed.value.firstOrNull { it.id == selection.installedModelId }
            ?: throw RouteException(context.getString(R.string.route_model_missing))
        val key = LocalKey(installed.id, selection.backend)
        val existing = cached
        val engine = if (existing != null && existing.key == key) {
            existing.engine
        } else {
            releaseLocalLocked()
            val raw = try {
                withContext(Dispatchers.IO) { createLocal(installed, selection.backend) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw RouteException(context.getString(R.string.route_engine_failed, e.message ?: e::class.simpleName))
            }
            // Most small models have no native function calling; the prompted adapter supplies it.
            val wrapped = if (raw.capabilities.nativeToolCalls) raw else PromptedToolEngine(raw)
            cached = CachedLocal(key, raw, wrapped, installed)
            wrapped
        }
        return RoutedEngine(
            engine = engine,
            model = installed.id,
            label = installed.name,
            onDevice = true,
            notice = null,
        )
    }

    private fun createLocal(model: InstalledModel, backend: LocalBackend): LlmEngine = when (model.format) {
        ModelFormat.LiteRtLm -> LiteRtEngine(
            context = context,
            modelPath = model.path,
            backend = when (backend) {
                LocalBackend.Auto -> LiteRtBackend.Auto
                LocalBackend.Npu -> LiteRtBackend.Npu
                LocalBackend.Gpu -> LiteRtBackend.Gpu
                LocalBackend.Cpu -> LiteRtBackend.Cpu
            },
            contextWindow = RoutedEngine.LOCAL_CONTEXT_WINDOW,
        )
        ModelFormat.Gguf -> LlamaCppEngine(
            modelPath = model.path,
            config = LlamaConfig(
                contextWindow = RoutedEngine.LOCAL_CONTEXT_WINDOW,
                gpuLayers = if (backend == LocalBackend.Gpu) GGUF_GPU_ALL_LAYERS else 0,
            ),
        )
    }

    private fun blockReason(state: PowerState): String = when {
        state.thermal == Thermal.Critical || state.thermal == Thermal.Hot -> context.getString(R.string.power_reason_hot)
        state.powerSave -> context.getString(R.string.power_reason_saver)
        !state.charging -> context.getString(R.string.power_reason_battery, state.batteryPercent)
        else -> context.getString(R.string.power_reason_generic)
    }

    companion object {
        const val IDLE_RELEASE_MS = 5 * 60 * 1000L
        private const val GGUF_GPU_ALL_LAYERS = 99
    }
}

object ProviderResolver {
    /** Resolves a provider id, synthesising an OpenAI-compatible provider for the custom id if needed. */
    fun resolve(providerId: String, baseUrl: String? = null, model: String? = null): Provider? {
        Providers.byId(providerId)?.let { return it }
        if (providerId != Providers.CUSTOM_ID) return null
        return Provider(
            id = Providers.CUSTOM_ID,
            displayName = "Custom",
            wire = Wire.OpenAiChat,
            baseUrl = baseUrl.orEmpty(),
            defaultModel = model.orEmpty(),
            keyHelpUrl = null,
            supportsModelList = true,
        )
    }

    /** Every provider the picker offers; custom is always present and last. */
    fun all(): List<Provider> {
        val listed = Providers.all
        return if (listed.any { it.id == Providers.CUSTOM_ID }) {
            listed.filterNot { it.id == Providers.CUSTOM_ID } + listed.first { it.id == Providers.CUSTOM_ID }
        } else {
            listed + resolve(Providers.CUSTOM_ID)!!
        }
    }
}
