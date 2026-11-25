package atom.general.language

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList

/**
 * Per-app language. Today only English ships, so the only choice is "Follow system". The hook is ready: add a
 * BCP-47 tag to [available] once its translations exist (and list it in the app's locales_config.xml), and
 * [set] will apply it through the platform per-app language API (Android 13+; the system Settings page for
 * per-app languages uses the same state).
 */
object AppLanguage {
    /** Shipped translations besides the default English strings. Empty = English only. */
    val available: List<String> = emptyList()

    val perAppSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** Selected tag, or null when following the system. */
    fun current(context: Context): String? {
        if (!perAppSupported) return null
        val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales ?: return null
        return if (locales.isEmpty) null else locales[0].toLanguageTag()
    }

    /** Applies [tag] (null = follow system). Ignored for tags not in [available]. */
    fun set(context: Context, tag: String?) {
        if (!perAppSupported) return
        if (tag != null && tag !in available) return
        val lm = context.getSystemService(LocaleManager::class.java) ?: return
        lm.applicationLocales = if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
    }
}
