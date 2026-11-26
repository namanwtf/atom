package atom.vdisplay.system

import android.content.Context
import android.content.SharedPreferences
import atom.xposed.HookConfig
import atom.xposed.ModuleStatus
import atom.xposed.PowerKeyTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * System enhancements settings. The hook settings live here (the app is their source of truth) and are mirrored to
 * the Xposed framework's remote preferences whenever they change or the framework connects.
 */
class SystemPrefs private constructor(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("atom4_system", Context.MODE_PRIVATE)

    private val _backgroundDisplay = MutableStateFlow(
        // An earlier build stored "off" for installs that already had other settings here, without the user ever
        // choosing it; only the user's own choice is kept.
        BackgroundUse.storedSetting(
            chosenByUser = sp.getBoolean(K_BG_DISPLAY_CHOSEN, false),
            stored = if (sp.contains(K_BG_DISPLAY)) sp.getBoolean(K_BG_DISPLAY, true) else null,
        ).also { on ->
            if (!sp.contains(K_BG_DISPLAY) || sp.getBoolean(K_BG_DISPLAY, true) != on) sp.edit().putBoolean(K_BG_DISPLAY, on).apply()
        },
    )

    /** On by default; only the user's own choice turns it off. It only acts with root (see [SystemFeature.tools]). */
    val backgroundDisplay: StateFlow<Boolean> = _backgroundDisplay.asStateFlow()

    private val _hooks = MutableStateFlow(HookConfig.fromMap(sp.all))
    val hooks: StateFlow<HookConfig> = _hooks.asStateFlow()

    private val _frameworkSeen = MutableStateFlow(sp.getBoolean(K_FRAMEWORK_SEEN, false))

    /** True once the Xposed framework has connected on this phone; the framework-only Settings rows show from then on. */
    val frameworkSeen: StateFlow<Boolean> = _frameworkSeen.asStateFlow()

    fun markFrameworkSeen() {
        if (_frameworkSeen.value) return
        sp.edit().putBoolean(K_FRAMEWORK_SEEN, true).apply()
        _frameworkSeen.value = true
    }

    fun setBackgroundDisplay(on: Boolean) {
        sp.edit().putBoolean(K_BG_DISPLAY, on).putBoolean(K_BG_DISPLAY_CHOSEN, true).apply()
        _backgroundDisplay.value = on
    }

    fun updateHooks(change: (HookConfig) -> HookConfig) {
        val next = change(_hooks.value)
        if (next == _hooks.value) return
        val e = sp.edit()
        for ((k, v) in next.toMap()) {
            when (v) {
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is String -> e.putString(k, v)
            }
        }
        e.apply()
        _hooks.value = next
        ModuleStatus.push(next)
    }

    fun setPowerKeyTarget(t: PowerKeyTarget) = updateHooks { it.copy(powerKeyTarget = t) }

    companion object {
        private const val K_BG_DISPLAY = "background_virtual_display"

        /** Written when the user flips the switch themselves; until then the setting follows the default (on). */
        private const val K_BG_DISPLAY_CHOSEN = "background_virtual_display_chosen"
        private const val K_FRAMEWORK_SEEN = "framework_seen"

        @Volatile
        private var instance: SystemPrefs? = null

        fun get(context: Context): SystemPrefs =
            instance ?: synchronized(this) { instance ?: SystemPrefs(context.applicationContext).also { instance = it } }
    }
}
