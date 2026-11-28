package atom.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import atom.core.agent.LimitOverrides
import atom.core.llm.ReasoningEffort
import atom.llm.cloud.PresetCatalog
import atom.ui.theme.ThemeMode

internal val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "atom4_settings")

/** Hardware path for on-device inference. GGUF models only distinguish GPU offload from CPU. */
@Serializable
enum class LocalBackend { Auto, Npu, Gpu, Cpu }

/** Which model answers the next task. */
@Serializable
sealed interface EngineSelection {
    @Serializable
    @SerialName("cloud")
    data class Cloud(
        val providerId: String,
        val model: String,
        /** Only set when it differs from the provider default (custom endpoints, proxies). */
        val baseUrl: String? = null,
    ) : EngineSelection

    @Serializable
    @SerialName("local")
    data class Local(
        val installedModelId: String,
        val backend: LocalBackend = LocalBackend.Auto,
    ) : EngineSelection
}

data class AppSettings(
    /** The global default model. A fresh install starts on [DEFAULT_SELECTION] (the user still adds a key). */
    val selection: EngineSelection? = DEFAULT_SELECTION,
    /** False until the user picks a default model themselves. */
    val selectionExplicit: Boolean = false,
    /** Used instead of the on-device model while the PowerGovernor disallows local inference. */
    val cloudFallback: EngineSelection.Cloud? = null,
    val wifiOnlyDownloads: Boolean = true,
    /** When true, Act-risk tools (taps, typing) also ask first, not only Sensitive ones. */
    val confirmActTools: Boolean = false,
    val onboarded: Boolean = false,
    val theme: ThemeMode = ThemeMode.System,
    /** Opt-in "Hey Atom" listener. Off by default: an open microphone is never free. */
    val heyAtom: Boolean = false,
    /** Also listen with the screen off on battery (charging always allows it). */
    val heyAtomScreenOff: Boolean = false,
    /** New conversations start with reasoning ("Deep Think") on. */
    val reasoningByDefault: Boolean = false,
    /** Effort used when reasoning is on; null means the model's own default. */
    val defaultReasoning: ReasoningEffort? = null,
    val autoTitles: Boolean = true,
    /** Summarise older turns automatically at ~85% of the model's context window. */
    val autoCompaction: Boolean = true,
    /** Keep the screen on while a run is active and Atom is visible (with a safety timeout). */
    val keepScreenOn: Boolean = true,
    /** Android 16+: show a running task as a Live Update (status-bar chip). Older Androids ignore it. */
    val liveUpdates: Boolean = true,
    /** Settings > Advanced: limits the user raised or lowered; everything else follows the model. */
    val limits: LimitOverrides = LimitOverrides(),
) {
    companion object {
        val DEFAULT_SELECTION = EngineSelection.Cloud(PresetCatalog.DEFAULT_PROVIDER, PresetCatalog.DEFAULT_MODEL)
    }
}

class SettingsRepository(private val store: DataStore<Preferences>) {
    private val json = Json { ignoreUnknownKeys = true }

    val settings: Flow<AppSettings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map(::read)
        .distinctUntilChanged()

    suspend fun current(): AppSettings = settings.first()

    suspend fun setSelection(selection: EngineSelection) {
        store.edit { it[SELECTION] = json.encodeToString(EngineSelection.serializer(), selection) }
    }

    /**
     * New installs start with the original app's defaults: reasoning on by default. Installs that finished setup
     * (or already chose) keep what they have.
     */
    suspend fun applyNewInstallDefaults() {
        store.edit { if (it[ONBOARDED] != true && it[REASONING_DEFAULT] == null) it[REASONING_DEFAULT] = true }
    }

    suspend fun setReasoningByDefault(enabled: Boolean) {
        store.edit { it[REASONING_DEFAULT] = enabled }
    }

    suspend fun setDefaultReasoning(effort: ReasoningEffort?) {
        store.edit { if (effort == null) it.remove(REASONING_EFFORT) else it[REASONING_EFFORT] = effort.name }
    }

    suspend fun setAutoTitles(enabled: Boolean) {
        store.edit { it[AUTO_TITLES] = enabled }
    }

    suspend fun setAutoCompaction(enabled: Boolean) {
        store.edit { it[AUTO_COMPACTION] = enabled }
    }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        store.edit { it[KEEP_SCREEN_ON] = enabled }
    }

    suspend fun setLiveUpdates(enabled: Boolean) {
        store.edit { it[LIVE_UPDATES] = enabled }
    }

    suspend fun setFallback(fallback: EngineSelection.Cloud?) {
        store.edit {
            if (fallback == null) it.remove(FALLBACK)
            else it[FALLBACK] = json.encodeToString(EngineSelection.Cloud.serializer(), fallback)
        }
    }

    suspend fun setWifiOnly(value: Boolean) {
        store.edit { it[WIFI_ONLY] = value }
    }

    /** Saves the Advanced limits; a null value goes back to Auto. */
    suspend fun setLimits(limits: LimitOverrides) {
        store.edit { p ->
            fun put(key: androidx.datastore.preferences.core.Preferences.Key<Int>, value: Int?) {
                if (value == null) p.remove(key) else p[key] = value
            }
            put(ADV_MAX_OUTPUT, limits.maxOutputTokens)
            put(ADV_MAX_STEPS, limits.maxSteps)
            put(ADV_TOOL_RESULT, limits.toolResultChars)
            put(ADV_SUBAGENT_MIN, limits.subagentMinutes)
            put(ADV_APPROVAL_MIN, limits.approvalWaitMinutes)
            put(ADV_TASK_MIN, limits.taskMinutes)
            put(ADV_IMAGES, limits.imagesKept)
            p[ADV_APPROVAL_PAUSES] = limits.approvalPausesRun
        }
    }

    suspend fun setConfirmActTools(value: Boolean) {
        store.edit { it[CONFIRM_ACT] = value }
    }

    suspend fun setOnboarded() {
        store.edit { it[ONBOARDED] = true }
    }

    suspend fun setTheme(mode: ThemeMode) {
        store.edit { it[THEME] = mode.name }
    }

    suspend fun setHeyAtom(enabled: Boolean) {
        store.edit { it[HEY_ATOM] = enabled }
    }

    suspend fun setHeyAtomScreenOff(enabled: Boolean) {
        store.edit { it[HEY_ATOM_SCREEN_OFF] = enabled }
    }

    private fun read(p: Preferences) = AppSettings(
        selection = p[SELECTION]?.let { decode(EngineSelection.serializer(), it) } ?: AppSettings.DEFAULT_SELECTION,
        selectionExplicit = p[SELECTION] != null,
        cloudFallback = p[FALLBACK]?.let { decode(EngineSelection.Cloud.serializer(), it) },
        wifiOnlyDownloads = p[WIFI_ONLY] ?: true,
        confirmActTools = p[CONFIRM_ACT] ?: false,
        onboarded = p[ONBOARDED] ?: false,
        theme = p[THEME]?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: ThemeMode.System,
        heyAtom = p[HEY_ATOM] ?: false,
        heyAtomScreenOff = p[HEY_ATOM_SCREEN_OFF] ?: false,
        reasoningByDefault = p[REASONING_DEFAULT] ?: false,
        defaultReasoning = p[REASONING_EFFORT]?.let { name -> ReasoningEffort.entries.firstOrNull { it.name == name } },
        autoTitles = p[AUTO_TITLES] ?: true,
        autoCompaction = p[AUTO_COMPACTION] ?: true,
        keepScreenOn = p[KEEP_SCREEN_ON] ?: true,
        liveUpdates = p[LIVE_UPDATES] ?: true,
        limits = LimitOverrides(
            maxOutputTokens = p[ADV_MAX_OUTPUT],
            maxSteps = p[ADV_MAX_STEPS],
            toolResultChars = p[ADV_TOOL_RESULT],
            subagentMinutes = p[ADV_SUBAGENT_MIN],
            approvalWaitMinutes = p[ADV_APPROVAL_MIN],
            approvalPausesRun = p[ADV_APPROVAL_PAUSES] ?: true,
            taskMinutes = p[ADV_TASK_MIN],
            imagesKept = p[ADV_IMAGES],
        ),
    )

    private fun <T> decode(serializer: KSerializer<T>, raw: String): T? =
        runCatching { json.decodeFromString(serializer, raw) }.getOrNull()

    private companion object {
        val SELECTION = stringPreferencesKey("engine_selection")
        val FALLBACK = stringPreferencesKey("cloud_fallback")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only_downloads")
        val CONFIRM_ACT = booleanPreferencesKey("confirm_act_tools")
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val THEME = stringPreferencesKey("theme")
        val HEY_ATOM = booleanPreferencesKey("hey_atom")
        val HEY_ATOM_SCREEN_OFF = booleanPreferencesKey("hey_atom_screen_off")
        val REASONING_DEFAULT = booleanPreferencesKey("reasoning_by_default")
        val REASONING_EFFORT = stringPreferencesKey("default_reasoning_effort")
        val AUTO_TITLES = booleanPreferencesKey("auto_titles")
        val AUTO_COMPACTION = booleanPreferencesKey("auto_compaction")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val LIVE_UPDATES = booleanPreferencesKey("live_update_notification")
        val ADV_MAX_OUTPUT = intPreferencesKey("adv_max_output_tokens")
        val ADV_MAX_STEPS = intPreferencesKey("adv_max_steps")
        val ADV_TOOL_RESULT = intPreferencesKey("adv_tool_result_chars")
        val ADV_SUBAGENT_MIN = intPreferencesKey("adv_subagent_minutes")
        val ADV_APPROVAL_MIN = intPreferencesKey("adv_approval_wait_minutes")
        val ADV_APPROVAL_PAUSES = booleanPreferencesKey("adv_approval_pauses_run")
        val ADV_TASK_MIN = intPreferencesKey("adv_task_minutes")
        val ADV_IMAGES = intPreferencesKey("adv_images_kept")
    }
}
