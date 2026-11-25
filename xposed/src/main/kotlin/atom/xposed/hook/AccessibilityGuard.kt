package atom.xposed.hook

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import atom.xposed.AccessibilityList
import atom.xposed.RetryBudget

/**
 * Accessibility protection (system_server). Some ROMs silently remove a third-party accessibility service (battery
 * "optimisation", app freezing, updates). This watches the enabled-services setting (a content observer, no
 * polling) and puts Atom's service back, at most 5 times per 30 minutes with growing delays. Turning the service off
 * by hand while this is on puts it back too: the setting in Atom is the switch for that.
 */
internal object AccessibilityGuard {
    const val SERVICE_CLASS = "atom.device.AtomAccessibilityService"
    private val main = Handler(Looper.getMainLooper())
    private val budget = RetryBudget(maxAttempts = 5, windowMs = 30 * 60_000L, delaysMs = listOf(2_000L, 10_000L, 30_000L, 120_000L))
    private lateinit var env: HookEnv
    private lateinit var context: Context
    private val repair = Runnable { repairNow() }

    private val component: String get() = "${env.modulePackage}/$SERVICE_CLASS"

    fun install(env: HookEnv, context: Context) {
        this.env = env
        this.context = context
        env.guard("a11y-observer") {
            val observer = object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) = onSettingChanged()
            }
            val uri = Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            try {
                // Hidden overload: observe every user (-1 = USER_ALL).
                context.contentResolver.javaClass.getMethod(
                    "registerContentObserver", android.net.Uri::class.java, Boolean::class.javaPrimitiveType,
                    ContentObserver::class.java, Int::class.javaPrimitiveType,
                ).invoke(context.contentResolver, uri, false, observer, -1)
            } catch (t: Throwable) {
                context.contentResolver.registerContentObserver(uri, false, observer)
            }
        }
        env.onConfigChanged { if (it.enforceAccessibility) onSettingChanged() }
        main.postDelayed({ onSettingChanged() }, 15_000) // after boot, once the ROM has applied its own policies
    }

    private fun onSettingChanged() {
        if (!env.config.enforceAccessibility || enabled()) return
        main.removeCallbacks(repair)
        val d = budget.nextDelay(SystemClock.elapsedRealtime()) ?: run {
            env.logOnce("a11y-budget", "Accessibility keeps being removed; pausing repairs for a while")
            return
        }
        main.postDelayed(repair, d)
    }

    private fun enabled(): Boolean =
        AccessibilityList.contains(Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), component)

    private fun repairNow() = env.guard("a11y-repair") {
        if (!env.config.enforceAccessibility || enabled()) return@guard
        // The service must still be installed; never write a component that does not exist.
        context.packageManager.getServiceInfo(android.content.ComponentName.unflattenFromString(component)!!, 0)
        budget.consume(SystemClock.elapsedRealtime())
        val current = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, AccessibilityList.withComponent(current, component))
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        env.log("Re-enabled Atom's accessibility service (attempt ${budget.used})")
    }
}
