package atom.app.integration

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import atom.app.AppGraph
import atom.app.R
import atom.app.data.ConversationDto
import atom.app.data.ConversationStore
import atom.app.data.KeyVault
import atom.app.data.ProviderStore
import atom.app.data.runtimeDataStore
import atom.app.data.settingsDataStore
import atom.app.data.toDto
import atom.app.data.toModel
import atom.characters.session.CharacterSession
import atom.extend.ExtendFeature
import atom.llm.cloud.ProviderConfig
import atom.ui.feature.BackupContributor
import atom.ui.feature.Backups
import atom.voice.speech.Speech
import atom.voice.speech.SpeechConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Registers the app's backup sections. Cheap: nothing is read until the backup screen asks. */
internal object AppBackups {
    fun register(context: Context, graph: AppGraph) {
        val app = context.applicationContext
        Backups.register(ConversationsBackup(app, graph.conversations))
        Backups.register(ProvidersBackup(app, graph.vault))
        Backups.register(SettingsBackup(app))
        Backups.register(MemoryBackup(app))
        Backups.register(CharactersBackup(app))
        Backups.register(VoiceSettingsBackup(app))
    }
}

private val backupJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

private const val VERSION = 1

private fun JsonElement.versionOk(): Boolean = (this as? JsonObject)?.get("version")?.jsonPrimitive?.intOrNull == VERSION

private abstract class Section(protected val app: Context) : BackupContributor {
    protected fun damaged() = app.getString(R.string.backup_invalid_section)

    protected fun checkVersion(data: JsonElement): String? = when {
        data !is JsonObject -> damaged()
        data["version"]?.jsonPrimitive?.intOrNull?.let { it > VERSION } == true -> app.getString(R.string.backup_unsupported_version)
        !data.versionOk() -> damaged()
        else -> null
    }
}

/** Every conversation, as stored (image payloads are already dropped on save; attachment files are not copied). */
private class ConversationsBackup(app: Context, private val store: ConversationStore) : Section(app) {
    override val key = "conversations"
    override val label = R.string.backup_part_conversations
    override val containsSecrets = false

    override suspend fun export(): JsonElement {
        val ids = store.summaries.first().map { it.id }
        val dtos = ids.mapNotNull { store.load(it)?.toDto() }
        return buildJsonObject {
            put("version", VERSION)
            put("items", backupJson.encodeToJsonElement(ListSerializer(ConversationDto.serializer()), dtos))
        }
    }

    override suspend fun validate(data: JsonElement): String? {
        checkVersion(data)?.let { return it }
        val items = runCatching { decode(data) }.getOrNull() ?: return damaged()
        if (items.any { !ConversationStore.isValidId(it.id) }) return damaged()
        return null
    }

    override suspend fun import(data: JsonElement) {
        val items = decode(data)
        val keep = items.map { it.id }.toSet()
        for (summary in store.summaries.first()) if (summary.id !in keep) store.delete(summary.id)
        for (dto in items) store.save(dto.toModel())
    }

    private fun decode(data: JsonElement): List<ConversationDto> =
        backupJson.decodeFromJsonElement(ListSerializer(ConversationDto.serializer()), data.jsonObject.getValue("items"))
}

/** Provider configs plus their API keys (plain text in the file). Account sign-in tokens are never exported. */
private class ProvidersBackup(app: Context, private val vault: KeyVault) : Section(app) {
    override val key = "providers"
    override val label = R.string.backup_part_providers
    override val containsSecrets = true

    private val store get() = ProviderStore.get(app)

    override suspend fun export(): JsonElement {
        val providers = store.all()
        val keys = providers.mapNotNull { p -> vault.apiKey(p.id)?.let { p.id to it } }.toMap()
        return buildJsonObject {
            put("version", VERSION)
            put("providers", backupJson.encodeToJsonElement(ListSerializer(ProviderConfig.serializer()), providers))
            put("keys", JsonObject(keys.mapValues { JsonPrimitive(it.value) }))
            vault.hfToken()?.let { put("hfToken", it) }
        }
    }

    override suspend fun validate(data: JsonElement): String? {
        checkVersion(data)?.let { return it }
        return runCatching { decode(data); null }.getOrElse { damaged() }
    }

    override suspend fun import(data: JsonElement) {
        val (providers, keys, hf) = decode(data)
        val keep = providers.map { it.id }.toSet()
        store.all().filter { it.id !in keep }.forEach { vault.removeProvider(it.id) }
        store.replaceAll(providers)
        providers.forEach { vault.setApiKey(it.id, keys[it.id]) }
        vault.setHfToken(hf)
    }

    private fun decode(data: JsonElement): Triple<List<ProviderConfig>, Map<String, String>, String?> {
        val o = data.jsonObject
        val providers = backupJson.decodeFromJsonElement(ListSerializer(ProviderConfig.serializer()), o.getValue("providers"))
        require(providers.map { it.id }.distinct().size == providers.size) { "duplicate provider ids" }
        val keys = o["keys"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
        val hf = o["hfToken"]?.jsonPrimitive?.contentOrNull
        return Triple(providers, keys, hf)
    }
}

/**
 * App settings, model roles and runtime preferences: the "settings" and "runtime" DataStores copied key by key,
 * so settings added later are covered without touching this class. No secrets live there.
 */
private class SettingsBackup(app: Context) : Section(app) {
    override val key = "app_settings"
    override val label = R.string.backup_part_settings
    override val containsSecrets = false

    private val stores: Map<String, DataStore<Preferences>> get() = mapOf("settings" to app.settingsDataStore, "runtime" to app.runtimeDataStore)

    override suspend fun export(): JsonElement = buildJsonObject {
        put("version", VERSION)
        for ((name, store) in stores) put(name, PrefsCodec.encode(store.data.first()))
    }

    override suspend fun validate(data: JsonElement): String? {
        checkVersion(data)?.let { return it }
        val o = data.jsonObject
        for (name in stores.keys) {
            val section = o[name] ?: continue
            if (runCatching { PrefsCodec.check(section) }.isFailure) return damaged()
        }
        return null
    }

    override suspend fun import(data: JsonElement) {
        val o = data.jsonObject
        for ((name, store) in stores) {
            val section = o[name]?.jsonObject ?: continue
            store.edit { prefs ->
                prefs.clear()
                PrefsCodec.apply(section, prefs)
            }
        }
    }
}

/** Typed DataStore preferences <-> JSON: {"key": {"t": "b|i|l|f|d|s|ss|y", "v": value}}. */
private object PrefsCodec {
    fun encode(p: Preferences): JsonObject = buildJsonObject {
        for ((k, v) in p.asMap()) {
            val entry: JsonObject = when (v) {
                is Boolean -> typed("b", JsonPrimitive(v))
                is Int -> typed("i", JsonPrimitive(v))
                is Long -> typed("l", JsonPrimitive(v))
                is Float -> typed("f", JsonPrimitive(v))
                is Double -> typed("d", JsonPrimitive(v))
                is String -> typed("s", JsonPrimitive(v))
                is Set<*> -> typed("ss", JsonArray(v.map { JsonPrimitive(it.toString()) }))
                is ByteArray -> typed("y", JsonPrimitive(Base64.encodeToString(v, Base64.NO_WRAP)))
                else -> continue
            }
            put(k.name, entry)
        }
    }

    private fun typed(t: String, v: JsonElement) = JsonObject(mapOf("t" to JsonPrimitive(t), "v" to v))

    fun check(section: JsonElement) {
        val scratch = androidx.datastore.preferences.core.mutablePreferencesOf()
        apply(section.jsonObject, scratch)
    }

    fun apply(section: JsonObject, prefs: androidx.datastore.preferences.core.MutablePreferences) {
        for ((name, raw) in section) {
            val e = raw.jsonObject
            val v = e.getValue("v")
            when (e.getValue("t").jsonPrimitive.content) {
                "b" -> prefs[booleanPreferencesKey(name)] = requireNotNull(v.jsonPrimitive.booleanOrNull)
                "i" -> prefs[intPreferencesKey(name)] = requireNotNull(v.jsonPrimitive.intOrNull)
                "l" -> prefs[longPreferencesKey(name)] = requireNotNull(v.jsonPrimitive.longOrNull)
                "f" -> prefs[floatPreferencesKey(name)] = requireNotNull(v.jsonPrimitive.doubleOrNull).toFloat()
                "d" -> prefs[doublePreferencesKey(name)] = requireNotNull(v.jsonPrimitive.doubleOrNull)
                "s" -> prefs[stringPreferencesKey(name)] = v.jsonPrimitive.content
                "ss" -> prefs[stringSetPreferencesKey(name)] = v.jsonArray.map { it.jsonPrimitive.content }.toSet()
                "y" -> prefs[byteArrayPreferencesKey(name)] = Base64.decode(v.jsonPrimitive.content, Base64.NO_WRAP)
                else -> Unit // a type from a newer version: skipped
            }
        }
    }
}

/** MEMORY.md as text. */
private class MemoryBackup(app: Context) : Section(app) {
    override val key = "memory"
    override val label = R.string.backup_part_memory
    override val containsSecrets = false

    override suspend fun export(): JsonElement = withContext(Dispatchers.IO) {
        val file = ExtendFeature.memoryFile(app)
        buildJsonObject {
            put("version", VERSION)
            put("exists", file.exists())
            put("text", if (file.exists()) file.readText() else "")
        }
    }

    override suspend fun validate(data: JsonElement): String? {
        checkVersion(data)?.let { return it }
        return if (data.jsonObject["text"]?.jsonPrimitive?.isString == true) null else damaged()
    }

    override suspend fun import(data: JsonElement) = withContext(Dispatchers.IO) {
        val o = data.jsonObject
        val file = ExtendFeature.memoryFile(app)
        val exists = o["exists"]?.jsonPrimitive?.booleanOrNull ?: true
        if (!exists) {
            file.delete()
            return@withContext
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".import")
        tmp.writeText(o.getValue("text").jsonPrimitive.content)
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "could not write MEMORY.md" }
        }
    }
}

/** Characters, persona, character chats and their images (the module's own backup format). */
private class CharactersBackup(app: Context) : Section(app) {
    override val key = "characters"
    override val label = R.string.backup_part_characters
    override val containsSecrets = false

    private val store get() = CharacterSession.apply { install(app) }.store

    override suspend fun export(): JsonElement = store.exportBackup()

    override suspend fun validate(data: JsonElement): String? {
        if (data !is JsonObject) return damaged()
        if (data["version"]?.jsonPrimitive?.intOrNull == null || data["characters"] !is JsonArray) return damaged()
        return null
    }

    override suspend fun import(data: JsonElement) = store.importBackup(data.jsonObject)
}

/** Speech settings (services, voices, behaviour). Speech credentials are stored separately and never exported. */
private class VoiceSettingsBackup(app: Context) : Section(app) {
    override val key = "speech"
    override val label = R.string.backup_part_voice
    override val containsSecrets = false

    override suspend fun export(): JsonElement = buildJsonObject {
        put("version", VERSION)
        put("config", backupJson.encodeToJsonElement(SpeechConfig.serializer(), Speech.config.value))
    }

    override suspend fun validate(data: JsonElement): String? {
        checkVersion(data)?.let { return it }
        return runCatching { decode(data); null }.getOrElse { damaged() }
    }

    override suspend fun import(data: JsonElement) {
        Speech.init(app)
        Speech.save(decode(data))
    }

    private fun decode(data: JsonElement): SpeechConfig =
        backupJson.decodeFromJsonElement(SpeechConfig.serializer(), data.jsonObject.getValue("config"))
}
