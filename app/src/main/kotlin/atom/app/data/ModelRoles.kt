package atom.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import atom.core.llm.ReasoningEffort
import atom.llm.cloud.ReasoningInfo
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Jobs that can run on a model other than the conversation's. */
enum class ModelRole { Assistant, Subagent, Title, Scheduled, Image }

/** How a role picks its reasoning effort. [Auto] is the lightest setting the model allows (titles). */
@Serializable
enum class RoleReasoning { Follow, Auto, ModelDefault, Level }

/**
 * One role's model choice. A null [selection] follows the primary model: the conversation's model when there
 * is one, otherwise the global default.
 */
@Serializable
data class RoleSetting(
    val selection: EngineSelection? = null,
    val reasoning: RoleReasoning = RoleReasoning.Follow,
    val effort: ReasoningEffort? = null,
) {
    val followsPrimary: Boolean get() = selection == null
}

/** What the title generator should run on, or null when auto-titles are off. */
data class TitleModel(val selection: EngineSelection, val reasoning: ReasoningEffort?)

fun interface TitleModelProvider {
    suspend fun titleModel(conversationSelection: EngineSelection?): TitleModel?
}

/**
 * Per-role model choices. [resolve] is what the runtime calls; a role pointing at a provider or model that
 * no longer exists falls back to the primary model rather than failing.
 */
class ModelRoles internal constructor(
    private val store: DataStore<Preferences>,
    private val settings: SettingsRepository,
    private val exists: suspend (EngineSelection) -> Boolean,
    private val reasoningOf: suspend (EngineSelection) -> ReasoningInfo,
) : TitleModelProvider {
    private val json = Json { ignoreUnknownKeys = true }

    val roles: Flow<Map<ModelRole, RoleSetting>> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> ModelRole.entries.associateWith { role -> prefs[key(role)]?.let(::decode) ?: defaultFor(role) } }
        .distinctUntilChanged()

    suspend fun get(role: ModelRole): RoleSetting = roles.first().getValue(role)

    suspend fun set(role: ModelRole, setting: RoleSetting) {
        store.edit { it[key(role)] = json.encodeToString(RoleSetting.serializer(), setting) }
    }

    /**
     * The model [role] should use. [conversationSelection] is the conversation's own model (null outside a
     * conversation); [override] is a per-item choice such as one scheduled task's model.
     */
    suspend fun resolve(role: ModelRole, conversationSelection: EngineSelection?, override: EngineSelection? = null): EngineSelection? {
        val primary = conversationSelection ?: settings.current().selection
        override?.takeIf { exists(it) }?.let { return it }
        val chosen = get(role).selection?.takeIf { exists(it) }
        return chosen ?: primary
    }

    /**
     * The effort for [role] on [selection]. [conversationEffort] is what the conversation (or the global default)
     * would use; the result is always one the model accepts.
     */
    suspend fun resolveReasoning(role: ModelRole, selection: EngineSelection, conversationEffort: ReasoningEffort?): ReasoningEffort? {
        val setting = get(role)
        val info = reasoningOf(selection)
        if (!info.supported) return null
        val wanted = when (setting.reasoning) {
            RoleReasoning.Follow -> conversationEffort
            RoleReasoning.ModelDefault -> null
            RoleReasoning.Level -> setting.effort
            RoleReasoning.Auto -> info.levels.firstOrNull()
        }
        return atom.llm.cloud.Reasoning.snap(wanted, info)
    }

    override suspend fun titleModel(conversationSelection: EngineSelection?): TitleModel? {
        if (!settings.current().autoTitles) return null
        val selection = resolve(ModelRole.Title, conversationSelection) ?: return null
        return TitleModel(selection, resolveReasoning(ModelRole.Title, selection, conversationEffort = null))
    }

    private fun decode(raw: String): RoleSetting? = runCatching { json.decodeFromString(RoleSetting.serializer(), raw) }.getOrNull()

    private fun key(role: ModelRole) = stringPreferencesKey("model_role_" + role.name.lowercase())

    private fun defaultFor(role: ModelRole) =
        if (role == ModelRole.Title) RoleSetting(reasoning = RoleReasoning.Auto) else RoleSetting()

    companion object {
        @Volatile
        private var instance: ModelRoles? = null

        fun get(context: Context): ModelRoles = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                val info = atom.app.engine.ModelInfo.get(app)
                ModelRoles(
                    store = app.settingsDataStore,
                    settings = SettingsRepository(app.settingsDataStore),
                    exists = info::exists,
                    reasoningOf = info::reasoning,
                )
            }.also { instance = it }
        }
    }
}
