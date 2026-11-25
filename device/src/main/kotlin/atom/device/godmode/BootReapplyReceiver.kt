package atom.device.godmode

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Re-applies the firewall chain and charge limit after a reboot (the kernel forgets both). The component is enabled
 * only while there is something to re-apply, so a phone without rules never even wakes Atom at boot.
 */
class BootReapplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(9_000) { GodModeTools.reapplyAfterBoot(context) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        fun sync(context: Context) {
            val app = context.applicationContext
            val needed = Firewall(app).rules().any { it.scope != NetScope.Background } || ChargeLimitStore(app).limit != null
            app.packageManager.setComponentEnabledSetting(
                ComponentName(app, BootReapplyReceiver::class.java),
                if (needed) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
