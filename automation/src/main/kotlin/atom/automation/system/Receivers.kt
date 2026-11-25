package atom.automation.system

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import atom.automation.Automations
import atom.automation.rule.Rule
import atom.automation.rule.Trigger
import atom.automation.rule.TriggerEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** Loads the runtime off the main thread and keeps the receiver alive briefly so quick rules finish. */
private fun BroadcastReceiver.work(context: Context, block: AutomationRuntime.() -> Unit) {
    val app = context.applicationContext
    Automations.attachIfNeeded(app)
    val pending = goAsync()
    receiverScope.launch {
        try {
            val rt = AutomationRuntime.get(app)
            rt.block()
            withTimeoutOrNull(8_000) { rt.rules.awaitIdle() }
        } finally {
            pending.finish()
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_WAKE) work(context) { onWake() }
    }

    companion object {
        const val ACTION_WAKE = "atom.automation.action.WAKE"
    }
}

/** Boot, app update, clock and zone changes, exact-alarm permission changes: re-plan and re-arm everything. */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> work(context) {
                onSystemChange()
                rules.onEvent(TriggerEvent.Boot)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            -> work(context) { onSystemChange() }
        }
    }
}

/** Enabled only while some automation has a Bluetooth trigger. */
class BluetoothReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val connected = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> true
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> false
            else -> return
        }
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        val name = if (Health.bluetooth(context)) runCatching { device?.name }.getOrNull() else null
        val address = runCatching { device?.address }.getOrNull()
        work(context) { rules.onEvent(TriggerEvent.Bluetooth(connected, name, address)) }
    }
}

class PlaceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ruleId = intent.data?.lastPathSegment ?: return
        if (!intent.hasExtra(LocationManager.KEY_PROXIMITY_ENTERING)) return
        val entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)
        work(context) { rules.onEvent(TriggerEvent.Place(ruleId, entering)) }
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        when (intent.action) {
            ACTION_RUN_TASK -> work(context) {
                notifier.cancelReady(id)
                tasks.runNow(id)
            }
            ACTION_STOP_TASK -> work(context) { tasks.stop(id) }
        }
    }

    companion object {
        const val ACTION_RUN_TASK = "atom.automation.action.RUN_TASK"
        const val ACTION_STOP_TASK = "atom.automation.action.STOP_TASK"
        const val EXTRA_ID = "id"
    }
}

/** System-held proximity alerts: no process, no polling; the location provider wakes us on enter or leave. */
internal object Places {
    private const val PREFS = "atom4_automation_places"

    private fun intent(context: Context, ruleId: String) = PendingIntent.getBroadcast(
        context,
        ruleId.hashCode(),
        Intent(context, PlaceReceiver::class.java).setData(Uri.parse("atom-automation://place/$ruleId")),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    @SuppressLint("MissingPermission")
    fun sync(context: Context, rules: List<Rule>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val before = prefs.getStringSet("ids", emptySet()).orEmpty()
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        val wanted = rules.filter { it.trigger is Trigger.Location }
        for (id in before - wanted.map { it.id }.toSet()) runCatching { lm.removeProximityAlert(intent(context, id)) }
        val registered = mutableSetOf<String>()
        if (Health.location(context)) {
            for (rule in wanted) {
                val t = rule.trigger as Trigger.Location
                val pi = intent(context, rule.id)
                runCatching {
                    lm.removeProximityAlert(pi)
                    lm.addProximityAlert(t.latitude, t.longitude, t.radiusM.toFloat(), -1, pi)
                    registered += rule.id
                }
            }
        }
        prefs.edit().putStringSet("ids", registered).apply()
    }
}
