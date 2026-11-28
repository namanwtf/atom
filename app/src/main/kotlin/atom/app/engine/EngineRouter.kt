package atom.app.engine

import android.content.Context
import atom.app.R
import atom.app.data.EngineSelection
import atom.app.data.KeyVault
import atom.app.data.LocalBackend
import atom.app.data.SettingsRepository
import atom.core.agent.AgentConfig
import atom.core.agent.ContextPolicy
import atom.core.agent.SmallModel
import atom.core.agent.TinyModel
import atom.core.llm.DegenerateOutputGuard
import atom.core.llm.LlmEngine
import atom.core.llm.PromptedToolEngine
import atom.core.llm.Releasable
import atom.core.llm.ToolSpec
import atom.device.power.PowerGovernor
import atom.device.power.PowerState
import atom.device.power.Thermal
import atom.app.data.ModelRole
import atom.app.data.ModelRoles
import atom.app.data.ProviderStore
import atom.app.ondevice.OnDevicePack
import atom.core.ondevice.OnDeviceBackend
import atom.core.llm.ReasoningEffort
import atom.llm.cloud.AuthMethod
import atom.llm.cloud.ChatGptAuth
import atom.llm.cloud.CloudEngines
import atom.llm.cloud.EngineOptions
import atom.llm.cloud.Family
import atom.llm.cloud.GrokAuth
import atom.llm.cloud.HeaderEntry
import atom.llm.cloud.ModelConfig
import atom.llm.cloud.OAuthException
import atom.llm.cloud.OutputBudget
import atom.llm.cloud.PresetCatalog
import atom.llm.cloud.Protocol
import atom.llm.cloud.Provider
import atom.llm.cloud.ProviderConfig
import atom.llm.cloud.Providers
import atom.llm.cloud.Reasoning
import atom.llm.cloud.ReasoningInfo
import atom.llm.cloud.RequestCustomizer
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
    /** What actually runs (the fallback when [notice] says so). */
    val selection: EngineSelection? = null,
    /** Reasoning levels the model accepts; pass requested efforts through [snapReasoning]. */
    val reasoning: ReasoningInfo = ReasoningInfo.None,
    /** Context window in tokens when known; null means unknown (automatic compaction stays off). */
    val contextWindow: Int? = null,
    /** An on-device model under about 3B parameters ([SmallModel]): fine for chat, weak at multi-step tasks. */
    val smallModel: Boolean = false,
    /** An on-device model under about 1.5B parameters ([TinyModel]): a short prompt and one-step tools only. */
    val tinyModel: Boolean = false,
    /** What a cloud model's output budget depends on; null on-device, where [localPlan] decides it. */
    val output: OutputBudget.Target? = null,
) {
    fun snapReasoning(effort: ReasoningEffort?): ReasoningEffort? = Reasoning.snap(effort, reasoning)

    /**
     * Output tokens a cloud request with [effort] may use: room for the reasoning as well as the answer when the
     * model thinks ([OutputBudget]), the plain answer budget otherwise.
     */
    fun cloudOutputTokens(effort: ReasoningEffort?): Int = output?.tokens(effort) ?: CLOUD_MAX_OUTPUT

    /**
     * The reasoning for the one retry after a thinking model spent its whole output budget reasoning
     * ([AgentConfig.briefReasoning]): thinking off where the model allows it, else its lightest level, else the
     * model default.
     */
    fun briefReasoning(): ReasoningEffort? = briefReasoningFor(reasoning)

    /** [effort] is the reasoning the run sends; it decides how much output room a cloud request gets. */
    fun agentConfig(
        systemPrompt: String,
        tools: List<ToolSpec>,
        slowApprovals: Boolean,
        effort: ReasoningEffort? = null,
    ): AgentConfig {
        val vision = engine.capabilities.vision
        val toolTimeout = if (slowApprovals) 125_000L else 60_000L
        if (!onDevice) {
            return AgentConfig(
                model = model,
                systemPrompt = systemPrompt,
                maxSteps = CLOUD_MAX_STEPS,
                maxOutputTokens = cloudOutputTokens(effort),
                reasoning = effort,
                briefReasoning = briefReasoning(),
                toolTimeoutMs = toolTimeout,
                // No deliberate cap: the prompt may use the model window minus the answer, and automatic compaction
                // starts near 85% of that (AgentConfig.usableWindow).
                context = ContextPolicy(keepImages = if (vision) 1 else 0),
            )
        }
        // On-device every prompt token is prefill on the phone's own silicon: keep it small. Tool
        // descriptions are injected into the prompt by PromptedToolEngine, so they count against the window.
        val window = engine.capabilities.contextWindow.takeIf { it > 0 } ?: LOCAL_CONTEXT_WINDOW
        val plan = localPlan(window)
        return AgentConfig(
            model = model,
            systemPrompt = systemPrompt,
            maxSteps = LOCAL_MAX_STEPS,
            maxOutputTokens = plan.maxOutputTokens,
            reasoning = effort,
            briefReasoning = briefReasoning(),
            toolTimeoutMs = toolTimeout,
            maxRetries = 1,
            context = ContextPolicy(
                budgetTokens = plan.promptBudget,
                keepFullToolResults = 2,
                maxToolResultChars = plan.maxToolResultChars,
                compactedToolResultChars = 160,
                keepImages = if (vision) 1 else 0,
                // Tool descriptions go into the on-device prompt, and the offered set grows as groups load: each
                // request's message budget is what its tools and the answer leave of the window.
                sharedWindow = window,
            ),
        )
    }

    /** How an on-device run splits its window between the messages, the tool descriptions and the answer. */
    data class LocalPlan(val promptBudget: Int, val maxOutputTokens: Int, val maxToolResultChars: Int)

    companion object {
        const val LOCAL_CONTEXT_WINDOW = 8_192
        const val LOCAL_PROMPT_BUDGET = 6_000

        /**
         * Safety caps on steps (model calls) per run, not a budget: operating the phone takes one step per screen
         * read, tap or scroll, so a run goes on as long as it makes progress. The loop guard ends a run that keeps
         * repeating itself without new information, automatic compaction keeps long runs inside the window, and
         * whenever a run stops early the model writes a final reply. On-device every step is a full prefill on the
         * phone, so its cap is lower.
         */
        const val CLOUD_MAX_STEPS = 400
        const val LOCAL_MAX_STEPS = 60

        /**
         * Messages + tool descriptions + answer must fit in [window], or the runtime refuses the prompt (LiteRT-LM:
         * "Input token ids are too long") or runs out of room mid-answer. Small windows (4096, as Qwen3 0.6B has)
         * get a shorter answer and shorter tool results instead of a budget planned for 8192. The tools' share is
         * taken per request ([atom.core.agent.AgentConfig.packingFor]), as the offered set grows when groups load.
         */
        fun localPlan(window: Int): LocalPlan {
            val small = window <= 4_096
            val maxOut = if (small) 512 else 768
            val budget = (window - maxOut - ContextPolicy.SHARED_MARGIN).coerceIn(ContextPolicy.SHARED_MIN_BUDGET, LOCAL_PROMPT_BUDGET)
            return LocalPlan(promptBudget = budget, maxOutputTokens = maxOut, maxToolResultChars = if (small) 1_500 else 3_000)
        }

        /**
         * Answer room a cloud run reserves when the model does not think; its prompt may use the rest of the model
         * window. A thinking request gets more ([cloudOutputTokens]).
         */
        const val CLOUD_MAX_OUTPUT = OutputBudget.ANSWER

        /** See [RoutedEngine.briefReasoning]. */
        fun briefReasoningFor(info: ReasoningInfo): ReasoningEffort? {
            val support = atom.core.llm.ReasoningSupport.of(info.supported, info.levels)
            return when {
                support.mode == atom.core.llm.ReasoningMode.Unsupported -> null
                support.canDisable -> ReasoningEffort.Off
                else -> support.levels.minOrNull()
            }
        }
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

    private val providers: ProviderStore by lazy { ProviderStore.get(context) }
    private val accounts: SubscriptionAuth by lazy { SubscriptionAuth.get(context) }

    /** The engine for a role (assistant overlay, subagent, titles, scheduled tasks), following the primary model when unset. */
    suspend fun acquire(
        role: ModelRole,
        conversationSelection: EngineSelection?,
        override: EngineSelection? = null,
        hostedSearch: Boolean = false,
    ): RoutedEngine = acquire(ModelRoles.get(context).resolve(role, conversationSelection, override), hostedSearch)

    /**
     * The engine for [selection] (a conversation's model), or the global default when null. Provider-hosted web
     * search (Gemini Google Search, OpenAI web_search) is on only when [hostedSearch] is true and the provider has
     * it on. Only a run passes true, as decided by `CapabilityTools.hostedSearch` (capabilities, the web_search
     * switch and "Web search and reading"); side calls (titles, compaction, dictation, tests) keep the default.
     */
    suspend fun acquire(selection: EngineSelection? = null, hostedSearch: Boolean = false): RoutedEngine = mutex.withLock {
        idleJob?.cancel()
        idleJob = null
        val current = settings.current()
        val selection = selection ?: current.selection ?: throw RouteException(context.getString(R.string.route_no_engine))
        val routed = when (selection) {
            is EngineSelection.Cloud -> cloud(selection, notice = null, hostedSearch = hostedSearch)
            is EngineSelection.Local -> {
                val governor = power()
                if (governor.localInferenceAllowed) {
                    local(selection)
                } else {
                    val reason = blockReason(governor.state.value)
                    val fallback = current.cloudFallback
                        ?: throw RouteException(context.getString(R.string.route_local_paused, reason))
                    cloud(fallback, notice = context.getString(R.string.route_fallback_notice, reason), hostedSearch = hostedSearch)
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

    private suspend fun cloud(selection: EngineSelection.Cloud, notice: String?, hostedSearch: Boolean): RoutedEngine {
        val config = providers.get(selection.providerId) ?: ProviderResolver.legacyConfig(selection)
            ?: throw RouteException(context.getString(R.string.route_unknown_provider, selection.providerId))
        if (!config.enabled) throw RouteException(context.getString(R.string.mdl_route_disabled, config.displayName))
        val modelId = selection.model.ifBlank { config.primaryModel }
        if (modelId.isBlank()) throw RouteException(context.getString(R.string.mdl_route_no_model, config.displayName))
        val modelConfig = config.model(modelId) ?: ModelConfig(modelId)
        val credential = credential(config)
        val extraBody = RequestCustomizer.parseExtraBody(config.extraBody).getOrElse {
            throw RouteException(context.getString(R.string.mdl_route_bad_body, config.displayName))
        }
        val provider = credential.provider
        val baseUrl = provider.baseUrl
        val options = engineOptions(config, modelConfig, provider, credential.headers, extraBody, hostedSearch)
        val engine = try {
            CloudEngines.create(provider, credential.secret, modelId, baseUrl, options)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw RouteException(context.getString(R.string.route_engine_failed, e.message ?: e::class.simpleName))
        }
        return RoutedEngine(
            engine = engine,
            model = modelId,
            label = context.getString(R.string.engine_label_cloud, config.displayName, modelConfig.displayName),
            onDevice = false,
            notice = notice,
            selection = selection.copy(model = modelId),
            reasoning = modelConfig.reasoning,
            contextWindow = modelConfig.effectiveContextWindow,
            output = OutputBudget.Target(
                wire = provider.wire,
                family = provider.family,
                modelId = modelId,
                reasoning = modelConfig.engineReasoning,
                maxOutput = modelConfig.maxOutput,
                contextWindow = modelConfig.effectiveContextWindow,
            ),
        )
    }

    private class Credential(val provider: Provider, val secret: String, val headers: List<HeaderEntry>)

    private suspend fun credential(config: ProviderConfig): Credential {
        val provider = config.toProvider()
        return when (config.auth) {
            AuthMethod.ApiKey -> {
                val key = vault.apiKey(config.id).orEmpty()
                if (key.isEmpty() && !config.keyOptional) {
                    val message = if (vault.isUnreadable(config.id)) R.string.mdl_route_key_unreadable else R.string.route_missing_key
                    throw RouteException(context.getString(message, config.displayName))
                }
                Credential(provider, key, emptyList())
            }
            AuthMethod.ChatGpt -> {
                val tokens = accountTokens(config)
                val account = provider.copy(wire = Wire.OpenAiResponses, baseUrl = ChatGptAuth.BASE_URL, family = Family.CHATGPT, supportsModelList = false)
                Credential(account, tokens.accessToken, ChatGptAuth.accountHeaders(tokens))
            }
            AuthMethod.Grok -> {
                val tokens = accountTokens(config)
                val account = provider.copy(wire = Wire.OpenAiResponses, baseUrl = GrokAuth.BASE_URL, family = Family.XAI)
                Credential(account, tokens.accessToken, emptyList())
            }
        }
    }

    private suspend fun accountTokens(config: ProviderConfig) = try {
        accounts.validTokens(config.id)
    } catch (e: OAuthException) {
        throw RouteException(context.getString(R.string.mdl_route_signin, config.displayName, e.message.orEmpty()))
    }

    private suspend fun local(selection: EngineSelection.Local): RoutedEngine {
        if (!OnDevicePack.ready.value) throw RouteException(context.getString(R.string.route_needs_pack))
        val installed = models().installed.value.firstOrNull { it.id == selection.installedModelId }
            ?: throw RouteException(context.getString(R.string.route_model_missing))
        val key = LocalKey(installed.id, selection.backend)
        val existing = cached
        val engine = if (existing != null && existing.key == key) {
            existing.engine
        } else {
            releaseLocalLocked()
            val raw = try {
                // Reading capabilities once here, off the main thread, lets the engine read its model file's header.
                withContext(Dispatchers.IO) { createLocal(installed, selection.backend).also { it.capabilities } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw RouteException(context.getString(R.string.route_engine_failed, e.message ?: e::class.simpleName))
            }
            // A generation that turns into a loop of symbols is stopped with a clear message instead of streaming
            // garbage to the output limit. Most small models have no native function calling; the prompted adapter
            // supplies it.
            val guarded = DegenerateOutputGuard(raw)
            val wrapped = if (raw.capabilities.nativeToolCalls) guarded else PromptedToolEngine(guarded)
            cached = CachedLocal(key, raw, wrapped, installed)
            wrapped
        }
        return RoutedEngine(
            engine = engine,
            model = installed.id,
            label = installed.name,
            onDevice = true,
            notice = null,
            selection = selection,
            // The model's own window (LiteRT-LM files carry it), not the size the engine was asked for.
            contextWindow = engine.capabilities.contextWindow.takeIf { it > 0 } ?: RoutedEngine.LOCAL_CONTEXT_WINDOW,
            smallModel = SmallModel.isSmall(installed.name, installed.sizeBytes),
            tinyModel = TinyModel.isTiny(installed.name, installed.sizeBytes),
        )
    }

    /** On-device engines live in the optional on-device AI pack; every call goes through its interface. */
    private fun createLocal(model: InstalledModel, backend: LocalBackend): LlmEngine {
        val pack = OnDevicePack.engines() ?: throw RouteException(context.getString(R.string.route_needs_pack))
        return when (model.format) {
            ModelFormat.LiteRtLm -> pack.liteRt(
                context = context,
                modelPath = model.path,
                backend = when (backend) {
                    LocalBackend.Auto -> OnDeviceBackend.Auto
                    LocalBackend.Npu -> OnDeviceBackend.Npu
                    LocalBackend.Gpu -> OnDeviceBackend.Gpu
                    LocalBackend.Cpu -> OnDeviceBackend.Cpu
                },
                contextWindow = RoutedEngine.LOCAL_CONTEXT_WINDOW,
            )
            ModelFormat.Gguf -> pack.llamaCpp(
                modelPath = model.path,
                contextWindow = RoutedEngine.LOCAL_CONTEXT_WINDOW,
                gpuLayers = if (backend == LocalBackend.Gpu) GGUF_GPU_ALL_LAYERS else 0,
            )
        }
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

        /** The engine options for [modelConfig] of [config] (pure, unit-tested). */
        internal fun engineOptions(
            config: ProviderConfig,
            modelConfig: ModelConfig,
            provider: Provider,
            authHeaders: List<HeaderEntry>,
            extraBody: kotlinx.serialization.json.JsonObject?,
            hostedSearch: Boolean,
        ) = EngineOptions(
            headers = config.headers,
            authHeaders = authHeaders,
            extraBody = extraBody,
            systemPrompt = config.systemPrompt.takeIf { it.isNotBlank() },
            hostedWebSearch = hostedSearch && config.hostedWebSearch && provider.wire == Wire.OpenAiResponses,
            googleSearch = hostedSearch && config.googleSearch && provider.wire.protocol == Protocol.Gemini,
            vertexProject = config.vertexProject.takeIf { it.isNotBlank() },
            vertexLocation = config.vertexLocation,
            // Unknown (null) when nothing is detected in Auto mode: see ModelConfig.engineReasoning.
            reasoning = modelConfig.engineReasoning,
            contextWindow = modelConfig.effectiveContextWindow,
            vision = modelConfig.vision,
            anthropicVersion = config.anthropicVersion.takeIf { it.isNotBlank() },
        )
    }
}

object ProviderResolver {
    /**
     * Resolves a provider id to its configured provider (or the built-in preset before the store has loaded),
     * synthesising an OpenAI-compatible provider for the legacy custom id.
     */
    fun resolve(providerId: String, baseUrl: String? = null, model: String? = null): Provider? {
        ProviderStore.peekInstance()?.peek(providerId)?.let { return it.toProvider() }
        Providers.byId(providerId)?.takeIf { it.id != Providers.CUSTOM_ID }?.let { return it }
        if (providerId != Providers.CUSTOM_ID) return null
        return Provider(
            id = Providers.CUSTOM_ID,
            displayName = "Custom",
            wire = Wire.OpenAiChat,
            baseUrl = baseUrl.orEmpty(),
            defaultModel = model.orEmpty(),
            keyHelpUrl = null,
            supportsModelList = true,
            family = Family.CUSTOM,
        )
    }

    /** Enabled providers, in the user's order. */
    fun all(): List<Provider> {
        val configured = ProviderStore.peekInstance()?.providers?.value
        return configured?.filter { it.enabled }?.map { it.toProvider() }
            ?: PresetCatalog.providers.map { it.toProvider() }
    }

    /** Selections saved before providers were configurable used a single "custom" id with a base URL. */
    internal fun legacyConfig(selection: EngineSelection.Cloud): ProviderConfig? {
        if (selection.providerId != Providers.CUSTOM_ID || selection.baseUrl.isNullOrBlank()) return null
        return ProviderConfig(
            id = Providers.CUSTOM_ID,
            displayName = "Custom",
            wire = Wire.OpenAiChat,
            baseUrl = selection.baseUrl,
        )
    }
}
