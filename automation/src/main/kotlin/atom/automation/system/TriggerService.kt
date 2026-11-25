package atom.automation.system

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import atom.automation.Automations
import atom.automation.rule.Rule
import atom.automation.rule.Trigger
import atom.automation.rule.TriggerEvent

/**
 * Exists only while an enabled automation needs a broadcast Android delivers to running apps only (charger, screen,
 * unlock, battery level, Wi-Fi, headset, airplane mode, Do Not Disturb). It registers exactly those receivers and
 * does nothing else: no timers, no wake locks, so it costs memory but no CPU while idle.
 */
class TriggerService : Service() {
    private val receivers = mutableListOf<BroadcastReceiver>()
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var lastBattery: Int? = null
    private var registeredAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Automations.attachIfNeeded(this)
        val rt = AutomationRuntime.get(this)
        rt.start()
        val rules = rt.state.value.rules.filter { it.enabled && needsService(it.trigger) }
        val foreground = runCatching {
            ServiceCompat.startForeground(
                this,
                Notifier.LISTENING_ID,
                rt.notifier.listening(rules.size.coerceAtLeast(1)),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
        }.isSuccess
        if (rules.isEmpty() || !foreground) {
            stopSelf()
            return START_NOT_STICKY
        }
        register(rules.map { it.trigger }.toSet(), rt)
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterAll()
        super.onDestroy()
    }

    private fun unregisterAll() {
        receivers.forEach { runCatching { unregisterReceiver(it) } }
        receivers.clear()
        wifiCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) } }
        wifiCallback = null
    }

    private fun register(triggers: Set<Trigger>, rt: AutomationRuntime) {
        unregisterAll()
        registeredAt = SystemClock.elapsedRealtime()
        val emit: (TriggerEvent) -> Unit = { rt.rules.onEvent(it) }
        val filter = IntentFilter()
        val kinds = triggers.map { it::class }.toSet()
        fun has(vararg k: kotlin.reflect.KClass<out Trigger>) = k.any { it in kinds }
        if (has(Trigger.ChargerConnected::class)) filter.addAction(Intent.ACTION_POWER_CONNECTED)
        if (has(Trigger.ChargerDisconnected::class)) filter.addAction(Intent.ACTION_POWER_DISCONNECTED)
        if (has(Trigger.ScreenOn::class)) filter.addAction(Intent.ACTION_SCREEN_ON)
        if (has(Trigger.ScreenOff::class)) filter.addAction(Intent.ACTION_SCREEN_OFF)
        if (has(Trigger.Unlocked::class)) filter.addAction(Intent.ACTION_USER_PRESENT)
        if (has(Trigger.HeadsetPlugged::class, Trigger.HeadsetUnplugged::class)) filter.addAction(AudioManager.ACTION_HEADSET_PLUG)
        if (has(Trigger.AirplaneMode::class)) filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        if (has(Trigger.DndChanged::class)) filter.addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        if (has(Trigger.BatteryLevel::class)) filter.addAction(Intent.ACTION_BATTERY_CHANGED)
        if (filter.countActions() > 0) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val event = when (intent.action) {
                        Intent.ACTION_POWER_CONNECTED -> TriggerEvent.ChargerConnected
                        Intent.ACTION_POWER_DISCONNECTED -> TriggerEvent.ChargerDisconnected
                        Intent.ACTION_SCREEN_ON -> TriggerEvent.ScreenOn
                        Intent.ACTION_SCREEN_OFF -> TriggerEvent.ScreenOff
                        Intent.ACTION_USER_PRESENT -> TriggerEvent.Unlocked
                        AudioManager.ACTION_HEADSET_PLUG ->
                            if (isInitialStickyBroadcast) null else TriggerEvent.Headset(intent.getIntExtra("state", 0) == 1)
                        Intent.ACTION_AIRPLANE_MODE_CHANGED -> TriggerEvent.Airplane(intent.getBooleanExtra("state", false))
                        NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> TriggerEvent.Dnd(
                            getSystemService(NotificationManager::class.java).currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL,
                        )
                        Intent.ACTION_BATTERY_CHANGED -> {
                            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                            val pct = if (level < 0) null else level * 100 / scale
                            val prev = lastBattery
                            lastBattery = pct
                            if (pct == null || pct == prev || isInitialStickyBroadcast) null else TriggerEvent.Battery(pct, prev)
                        }
                        else -> null
                    }
                    event?.let(emit)
                }
            }
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receivers += receiver
        }
        if (has(Trigger.WifiConnected::class, Trigger.WifiDisconnected::class)) registerWifi(emit)
    }

    private fun registerWifi(emit: (TriggerEvent) -> Unit) {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val ssids = mutableMapOf<Network, String?>()
        val cb = object : ConnectivityManager.NetworkCallback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) FLAG_INCLUDE_LOCATION_INFO else 0,
        ) {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (network in ssids) return
                val ssid = ssidOf(caps)
                ssids[network] = ssid
                if (SystemClock.elapsedRealtime() - registeredAt > SETTLE_MS) emit(TriggerEvent.Wifi(true, ssid))
            }

            override fun onLost(network: Network) {
                if (!ssids.containsKey(network)) return
                emit(TriggerEvent.Wifi(false, ssids.remove(network)))
            }
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        runCatching { cm.registerNetworkCallback(request, cb) }.onSuccess { wifiCallback = cb }
    }

    private fun ssidOf(caps: NetworkCapabilities): String? {
        val fromCaps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) (caps.transportInfo as? WifiInfo)?.ssid else null
        val raw = fromCaps ?: AndroidDeviceState(this).wifiSsid
        return raw?.trim('"')?.takeIf { it.isNotBlank() && !it.startsWith("<") }
    }

    companion object {
        private const val SETTLE_MS = 2_000L

        fun needsService(t: Trigger): Boolean = when (t) {
            Trigger.ChargerConnected, Trigger.ChargerDisconnected, Trigger.ScreenOn, Trigger.ScreenOff, Trigger.Unlocked,
            Trigger.HeadsetPlugged, Trigger.HeadsetUnplugged,
            is Trigger.BatteryLevel, is Trigger.WifiConnected, is Trigger.WifiDisconnected,
            is Trigger.AirplaneMode, is Trigger.DndChanged,
            -> true
            else -> false
        }

        /** Starts, refreshes or stops the service to match the enabled rules. */
        fun sync(context: Context, enabled: List<Rule>) {
            val intent = Intent(context, TriggerService::class.java)
            if (enabled.none { needsService(it.trigger) }) {
                context.stopService(intent)
                return
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }
    }
}
