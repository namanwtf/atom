package atom.general.appearance

import android.content.Context
import atom.general.R
import atom.ui.feature.BackupContributor
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Appearance options as a backup section ("appearance"). No secrets. */
class AppearanceBackup(context: Context) : BackupContributor {
    private val app = context.applicationContext
    private val store get() = AppearanceStore.get(app)

    override val key: String = KEY
    override val label: Int = R.string.general_backup_section_appearance
    override val containsSecrets: Boolean = false

    override suspend fun export(): JsonElement = toJson(AppearanceCodec.encode(store.current()))

    override suspend fun validate(data: JsonElement): String? = problem(data)

    override suspend fun import(data: JsonElement) {
        store.replace(AppearanceCodec.decode(fromJson(data)))
    }

    companion object {
        const val KEY = "appearance"

        fun toJson(map: Map<String, String>): JsonElement = JsonObject(map.mapValues { JsonPrimitive(it.value) })

        fun fromJson(data: JsonElement): Map<String, String> =
            (data as JsonObject).mapValues { (it.value as JsonPrimitive).content }

        /** null when [data] is an object of string values; unknown keys and values are tolerated (defaults). */
        fun problem(data: JsonElement): String? {
            if (data !is JsonObject) return "not an object"
            if (data.values.any { it !is JsonPrimitive || !it.isString }) return "unexpected value"
            return null
        }
    }
}
