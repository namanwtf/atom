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
    private val sp: SharedPreferences = context.getSharedPreferences("atom_system", Context.MODE_PRIVATE)

    private val _backgroundDisplay = MutableStateFlow(sp.getBoolean(K_BG_DISPLAY, false))

    /** Off by default until it has been verified on the user's phone. */
    val backgroundDisplay: StateFlow<Boolean> = _backgroundDisplay.asStateFlow()

    private val _hooks = MutableStateFlow(HookConfig.fromMap(sp.all))
    val hooks: StateFlow<HookConfig> = _hooks.asStateFlow()

    fun setBackgroundDisplay(on: Boolean) {
        sp.edit().putBoolean(K_BG_DISPLAY, on).apply()
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

        @Volatile
        private var instance: SystemPrefs? = null

        fun get(context: Context): SystemPrefs =
            instance ?: synchronized(this) { instance ?: SystemPrefs(context.applicationContext).also { instance = it } }
    }
}
