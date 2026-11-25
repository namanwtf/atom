package atom.general.appearance

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import atom.ui.theme.Appearance
import atom.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** String form of [Appearance], shared by the DataStore and the backup section. Unknown values fall back to defaults. */
object AppearanceCodec {
    const val MODE = "mode"
    const val PURE_BLACK = "pureBlack"
    const val ACCENT = "accent"
    const val COLOR_STYLE = "colorStyle"
    const val BLUR = "blur"
    const val TOP_BAR_BLUR = "topBarBlur"
    const val PROGRESSIVE_BLUR = "progressiveBlur"
    const val PREDICTIVE_BACK = "predictiveBack"
    const val SWIPE_BACK = "swipeBackAnywhere"
    const val TEXT_SIZE = "textSize"
    const val MONET = "monet"
    const val UI_STYLE = "uiStyle"

    val KEYS = listOf(
        MODE, PURE_BLACK, ACCENT, COLOR_STYLE, BLUR, TOP_BAR_BLUR, PROGRESSIVE_BLUR, PREDICTIVE_BACK, SWIPE_BACK, TEXT_SIZE,
        MONET, UI_STYLE,
    )

    fun encode(a: Appearance): Map<String, String> = linkedMapOf(
        MODE to a.mode.name,
        PURE_BLACK to a.pureBlack.toString(),
        ACCENT to a.accent.name,
        COLOR_STYLE to a.colorStyle.name,
        BLUR to a.blur.toString(),
        TOP_BAR_BLUR to a.topBarBlur.name,
        PROGRESSIVE_BLUR to a.progressiveBlur.toString(),
        PREDICTIVE_BACK to a.predictiveBack.toString(),
        SWIPE_BACK to a.swipeBackAnywhere.toString(),
        TEXT_SIZE to a.textSize.name,
        MONET to a.monet.toString(),
        UI_STYLE to a.uiStyle.name,
    )

    /**
     * Never throws. Unknown or retired values fall back to the defaults: the old accents (Silver, Ember, Jade,
     * Iris, Rose) become the default accent. Data saved before the Monet switch existed keeps wallpaper colours
     * on if the stored accent was "System" (that was how wallpaper colours were chosen then).
     */
    fun decode(m: Map<String, String>): Appearance {
        val d = Appearance()
        fun bool(k: String, def: Boolean) = m[k]?.toBooleanStrictOrNull() ?: def
        val monet = m[MONET]?.toBooleanStrictOrNull() ?: (m[ACCENT] == LEGACY_WALLPAPER_ACCENT || d.monet)
        return Appearance(
            mode = enumOr(m[MODE], d.mode),
            pureBlack = bool(PURE_BLACK, d.pureBlack),
            accent = enumOr(m[ACCENT], d.accent),
            colorStyle = enumOr(m[COLOR_STYLE], d.colorStyle),
            blur = bool(BLUR, d.blur),
            topBarBlur = enumOr(m[TOP_BAR_BLUR], d.topBarBlur),
            progressiveBlur = bool(PROGRESSIVE_BLUR, d.progressiveBlur),
            predictiveBack = bool(PREDICTIVE_BACK, d.predictiveBack),
            swipeBackAnywhere = bool(SWIPE_BACK, d.swipeBackAnywhere),
            textSize = enumOr(m[TEXT_SIZE], d.textSize),
            monet = monet,
            uiStyle = enumOr(m[UI_STYLE], d.uiStyle),
        )
    }

    /** Before the Monet switch, picking the "System" accent meant wallpaper colours. */
    private const val LEGACY_WALLPAPER_ACCENT = "System"

    private inline fun <reified E : Enum<E>> enumOr(name: String?, def: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: def
}

private val Context.appearanceDataStore: DataStore<Preferences> by preferencesDataStore(name = "appearance")

/** Appearance options, persisted in their own small DataStore ("appearance"). */
class AppearanceStore private constructor(private val store: DataStore<Preferences>) {

    val appearance: Flow<Appearance> = store.data.map { p ->
        AppearanceCodec.decode(AppearanceCodec.KEYS.mapNotNull { k -> p[stringPreferencesKey(k)]?.let { k to it } }.toMap())
    }

    suspend fun current(): Appearance = appearance.first()

    suspend fun update(transform: (Appearance) -> Appearance) {
        store.edit { p ->
            val now = AppearanceCodec.decode(AppearanceCodec.KEYS.mapNotNull { k -> p[stringPreferencesKey(k)]?.let { k to it } }.toMap())
            AppearanceCodec.encode(transform(now)).forEach { (k, v) -> p[stringPreferencesKey(k)] = v }
        }
    }

    suspend fun replace(a: Appearance) = update { a }

    /** One-time migration hook: adopt the app's old theme setting if this store never saved a mode. */
    suspend fun seedModeIfUnset(mode: ThemeMode) {
        store.edit { p ->
            val key = stringPreferencesKey(AppearanceCodec.MODE)
            if (p[key] == null) p[key] = mode.name
        }
    }

    companion object {
        @Volatile private var instance: AppearanceStore? = null

        fun get(context: Context): AppearanceStore =
            instance ?: synchronized(this) {
                instance ?: AppearanceStore(context.applicationContext.appearanceDataStore).also { instance = it }
            }
    }
}

/**
 * The saved appearance, or null for the frame or two before DataStore answers (show a blank background then,
 * to avoid flashing the wrong theme). For MainActivity: `AtomTheme(rememberAppearance() ?: Appearance()) { ... }`.
 */
@Composable
fun rememberAppearance(): Appearance? {
    val context = LocalContext.current
    val value by AppearanceStore.get(context).appearance.collectAsStateWithLifecycle(initialValue = null)
    return value
}
