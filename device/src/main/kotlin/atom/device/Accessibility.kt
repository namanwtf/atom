package atom.device

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import atom.device.screen.UiController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App-facing handle on [AtomAccessibilityService]. */
object Accessibility {
    private val _connected = MutableStateFlow(false)

    /** True while the system has Atom's accessibility service bound. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    @Volatile
    internal var service: AtomAccessibilityService? = null
        private set

    @Volatile
    internal var taskActive: Boolean = false
        private set

    @Volatile
    private var appPackage: String? = null

    internal val settle = SettleDetector()

    /** Lets other modules (overlay windows) follow the service's lifetime. Set once by the app. */
    interface ServiceHook {
        fun onConnected(service: android.accessibilityservice.AccessibilityService)
        fun onDisconnected(service: android.accessibilityservice.AccessibilityService)
    }

    /**
     * Foreground-app changes for automations. While [watching] is true the service also subscribes to
     * window-state events when no task runs; call [refreshEventMask] whenever [watching] may have changed.
     */
    interface AppOpenHook {
        val watching: Boolean
        fun onAppOpened(packageName: String)
    }

    @Volatile
    var serviceHook: ServiceHook? = null

    @Volatile
    var appOpenHook: AppOpenHook? = null

    /** Re-applies the event subscription (after [appOpenHook]'s interest changed). */
    fun refreshEventMask() {
        service?.applyEventMask(taskActive)
    }

    /** Whether the user has switched the service on in system settings (it may not be bound yet). */
    fun isEnabled(context: Context): Boolean {
        appPackage = context.packageName
        val expected = ComponentName(context, AtomAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    /** Opens system accessibility settings, highlighting Atom's entry where the settings app supports it. */
    fun settingsIntent(): Intent {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pkg = appPackage ?: service?.packageName
        if (pkg != null) {
            val key = ComponentName(pkg, AtomAccessibilityService::class.java.name).flattenToString()
            intent.putExtra(":settings:fragment_args_key", key)
            intent.putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", key) })
        }
        return intent
    }

    /**
     * Battery switch for the service. While no agent task runs the service subscribes to zero event types,
     * so apps do not even dispatch accessibility events to it. A task turns on only the two window events
     * needed to detect when the screen settles after an action.
     */
    fun setTaskActive(active: Boolean) {
        taskActive = active
        service?.applyEventMask(active)
        if (!active) UiController.reset() // drop the cached node ids once the task is over
    }

    internal fun remember(context: Context) {
        appPackage = context.packageName
    }

    internal fun attach(s: AtomAccessibilityService) {
        service = s
        appPackage = s.packageName
        _connected.value = true
    }

    internal fun detach(s: AtomAccessibilityService) {
        if (service === s) {
            service = null
            _connected.value = false
        }
    }
}
