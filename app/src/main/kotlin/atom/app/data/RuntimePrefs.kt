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

internal val Context.runtimeDataStore: DataStore<Preferences> by preferencesDataStore(name = "runtime")

/** Agent-runtime and chat switches. Defaults match the old app's functional defaults. */
data class RuntimeSettings(
    /** FLAG_KEEP_SCREEN_ON while Atom is visible and a run is active (with a safety timeout). */
    val keepScreenOn: Boolean = true,
    /** Compact automatically at about 85% of the model's window. */
    val autoCompact: Boolean = true,
)

/** Where the transcript was scrolled to, so reopening a conversation lands at the same place. */
data class ReadingPosition(val itemKey: String, val offset: Int)

class RuntimePrefs(private val store: DataStore<Preferences>) {
    val settings: Flow<RuntimeSettings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            RuntimeSettings(
                keepScreenOn = p[KEEP_SCREEN_ON] ?: true,
                autoCompact = p[AUTO_COMPACT] ?: true,
            )
        }
        .distinctUntilChanged()

    suspend fun current(): RuntimeSettings = settings.first()

    suspend fun setKeepScreenOn(value: Boolean) = store.edit { it[KEEP_SCREEN_ON] = value }

    suspend fun setAutoCompact(value: Boolean) = store.edit { it[AUTO_COMPACT] = value }

    suspend fun readingPosition(conversationId: String): ReadingPosition? {
        val p = runCatching { store.data.first() }.getOrNull() ?: return null
        val key = p[stringPreferencesKey(POS_KEY + conversationId)] ?: return null
        return ReadingPosition(key, p[intPreferencesKey(POS_OFFSET + conversationId)] ?: 0)
    }

    suspend fun setReadingPosition(conversationId: String, position: ReadingPosition?) {
        store.edit {
            if (position == null) {
                it.remove(stringPreferencesKey(POS_KEY + conversationId))
                it.remove(intPreferencesKey(POS_OFFSET + conversationId))
            } else {
                it[stringPreferencesKey(POS_KEY + conversationId)] = position.itemKey
                it[intPreferencesKey(POS_OFFSET + conversationId)] = position.offset
            }
        }
    }

    private companion object {
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val AUTO_COMPACT = booleanPreferencesKey("auto_compact")
        const val POS_KEY = "pos_key_"
        const val POS_OFFSET = "pos_off_"
    }
}
