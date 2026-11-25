package atom.web

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

internal val PrefsJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

/** One non-secret settings object stored as JSON in this module's SharedPreferences. */
internal class JsonPref<T>(context: Context, private val key: String, private val serializer: KSerializer<T>, default: T) {
    private val prefs = context.applicationContext.getSharedPreferences("atom_web", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(
        prefs.getString(key, null)?.let { runCatching { PrefsJson.decodeFromString(serializer, it) }.getOrNull() } ?: default,
    )

    val flow: StateFlow<T> = state.asStateFlow()
    val value: T get() = state.value

    fun set(value: T) {
        state.value = value
        prefs.edit().putString(key, PrefsJson.encodeToString(serializer, value)).apply()
    }

    fun update(block: (T) -> T) = set(block(state.value))
}
