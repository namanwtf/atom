package atom.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import atom.llm.cloud.ModelConfig
import atom.llm.cloud.PresetCatalog
import atom.llm.cloud.ProviderConfig
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val Context.providersDataStore: DataStore<Preferences> by preferencesDataStore(name = "atom4_providers")

@Serializable
private data class StoredProviders(
    val version: Int = 1,
    val providers: List<ProviderConfig> = emptyList(),
    /** Built-in providers the user removed; they are not added back on the next start. */
    val removedPresets: Set<String> = emptySet(),
    /** "<presetId>/<modelId>" entries already offered, so official models added in an update appear once. */
    val knownPresetModels: Set<String> = emptySet(),
)

/**
 * Configured model providers. Built-in presets are materialised on first use (each with the default system
 * prompt); everything after that is the user's own copy. Secrets stay in [KeyVault] under the provider id.
 */
class ProviderStore internal constructor(private val store: DataStore<Preferences>, scope: CoroutineScope) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private val stored: Flow<StoredProviders> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> materialize(prefs[KEY]?.let(::decode)) }
        .distinctUntilChanged()

    val providers: StateFlow<List<ProviderConfig>> = stored
        .map { it.providers }
        .stateIn(scope, SharingStarted.Eagerly, materialize(null).providers)

    suspend fun all(): List<ProviderConfig> = stored.first().providers

    /** Built-in presets the user removed; they are not brought back (also not by the importer). */
    suspend fun removedPresets(): Set<String> = stored.first().removedPresets

    /** The last loaded value, for synchronous lookups (labels); prefer [get] where suspending is possible. */
    fun peek(id: String): ProviderConfig? = providers.value.firstOrNull { it.id == id }

    suspend fun get(id: String): ProviderConfig? = all().firstOrNull { it.id == id }

    suspend fun update(id: String, transform: (ProviderConfig) -> ProviderConfig) = edit { current ->
        current.copy(providers = current.providers.map { if (it.id == id) transform(it).copy(id = id) else it })
    }

    /** Adds [config] under a unique id derived from its name and returns the stored copy. */
    suspend fun add(config: ProviderConfig): ProviderConfig {
        var added = config
        edit { current ->
            val id = uniqueId(config.id.ifBlank { config.displayName }, current.providers.map { it.id }.toSet())
            added = config.copy(id = id)
            current.copy(providers = current.providers + added)
        }
        return added
    }

    suspend fun remove(id: String) = edit { current ->
        val removed = current.providers.firstOrNull { it.id == id }
        current.copy(
            providers = current.providers.filterNot { it.id == id },
            removedPresets = current.removedPresets + listOfNotNull(removed?.presetId),
        )
    }

    suspend fun duplicate(id: String, suffix: String): ProviderConfig? {
        val source = get(id) ?: return null
        return add(source.duplicate(newId = source.id + "-copy", suffix = suffix))
    }

    suspend fun reset(id: String) = update(id) { it.resetToPreset() }

    suspend fun setModels(id: String, models: List<ModelConfig>) = update(id) { config ->
        val default = config.defaultModel?.takeIf { d -> models.any { it.id == d } } ?: models.firstOrNull()?.id
        config.copy(models = models.distinctBy { it.id }, defaultModel = default)
    }

    suspend fun updateModel(providerId: String, modelId: String, transform: (ModelConfig) -> ModelConfig) =
        update(providerId) { config -> config.copy(models = config.models.map { if (it.id == modelId) transform(it) else it }) }

    /** Replaces everything (used by backup restore); built-ins missing from [providers] count as removed. */
    suspend fun replaceAll(providers: List<ProviderConfig>) = edit {
        val presets = providers.mapNotNull { it.presetId }.toSet()
        StoredProviders(
            providers = providers,
            removedPresets = PresetCatalog.providers.map { it.id }.toSet() - presets,
            knownPresetModels = allPresetModelKeys(),
        )
    }

    private suspend fun edit(transform: (StoredProviders) -> StoredProviders) {
        store.edit { prefs ->
            val current = materialize(prefs[KEY]?.let(::decode))
            prefs[KEY] = json.encodeToString(StoredProviders.serializer(), transform(current))
        }
    }

    private fun decode(raw: String): StoredProviders? =
        runCatching { json.decodeFromString(StoredProviders.serializer(), raw) }.getOrNull()

    private fun materialize(saved: StoredProviders?): StoredProviders {
        val base = saved ?: StoredProviders()
        val present = base.providers.mapNotNull { it.presetId }.toSet()
        val added = PresetCatalog.providers
            .filter { it.id !in present && it.id !in base.removedPresets && base.providers.none { p -> p.id == it.id } }
            .map { it.toConfig() }
        val known = base.knownPresetModels
        val upgraded = base.providers.map { config ->
            val preset = config.preset ?: return@map config
            val fresh = preset.models.filter { "${preset.id}/${it.id}" !in known && config.models.none { m -> m.id == it.id } }
            if (fresh.isEmpty() || saved == null) config else config.copy(models = config.models + fresh.map { it.toConfig() })
        }
        return base.copy(providers = upgraded + added, knownPresetModels = allPresetModelKeys())
    }

    private fun allPresetModelKeys(): Set<String> =
        PresetCatalog.providers.flatMapTo(mutableSetOf()) { p -> p.models.map { "${p.id}/${it.id}" } }

    companion object {
        private val KEY = stringPreferencesKey("providers_v1")

        @Volatile
        private var instance: ProviderStore? = null

        fun get(context: Context): ProviderStore = instance ?: synchronized(this) {
            instance ?: ProviderStore(
                context.applicationContext.providersDataStore,
                CoroutineScope(SupervisorJob() + Dispatchers.Default),
            ).also { instance = it }
        }

        /** The store if anything has created it yet; synchronous label lookups fall back to presets otherwise. */
        fun peekInstance(): ProviderStore? = instance

        fun uniqueId(base: String, taken: Set<String>): String {
            val slug = base.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "provider" }.take(32)
            if (slug !in taken) return slug
            var n = 2
            while ("$slug-$n" in taken) n++
            return "$slug-$n"
        }
    }
}
