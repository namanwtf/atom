package atom.device.godmode

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class SpoofState(val active: Boolean, val lat: Double = 0.0, val lon: Double = 0.0, val sinceMillis: Long = 0, val error: String? = null)

/**
 * GPS spoofing with Atom as the system mock-location app. It runs only while the user has it switched on, inside a
 * foreground service with a Stop action, and removes its test providers the moment it stops.
 */
object LocationSpoof {
    private val _state = MutableStateFlow(SpoofState(false))
    val state: StateFlow<SpoofState> = _state.asStateFlow()

    const val WARNING =
        "Spoofing applies to every app: Android cannot spoof per app without an LSPosed module. Games (Pokémon GO and " +
            "others), banking and ride-hailing apps detect mock locations and may ban the account."

    @Suppress("DEPRECATION")
    fun isMockApp(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        return runCatching {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, context.applicationInfo.uid, context.packageName) ==
                AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }

    fun start(context: Context, lat: Double, lon: Double, accuracyM: Float) {
        val i = Intent(context, SpoofService::class.java)
            .putExtra("lat", lat).putExtra("lon", lon).putExtra("acc", accuracyM)
        context.startForegroundService(i)
    }

    fun stop(context: Context) {
        context.startService(Intent(context, SpoofService::class.java).setAction(SpoofService.ACTION_STOP))
    }

    internal fun set(s: SpoofState) {
        _state.value = s
    }
}

class SpoofService : Service() {
    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private val providers = buildList {
        add(LocationManager.GPS_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
        if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
    }
    private val added = mutableSetOf<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        val lat = intent.getDoubleExtra("lat", Double.NaN)
        val lon = intent.getDoubleExtra("lon", Double.NaN)
        val acc = intent.getFloatExtra("acc", 5f)
        startForegroundCompat(lat, lon)
        if (lat.isNaN() || lon.isNaN()) {
            shutdown()
            return START_NOT_STICKY
        }
        val lm = getSystemService(LocationManager::class.java)
        try {
            providers.forEach { addProvider(lm, it) }
        } catch (e: SecurityException) {
            LocationSpoof.set(SpoofState(false, error = "Atom is not the mock location app. Developer options → Select mock location app → Atom."))
            shutdown()
            return START_NOT_STICKY
        }
        LocationSpoof.set(SpoofState(true, lat, lon, System.currentTimeMillis()))
        job?.cancel()
        val s = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }
        job = s.launch {
            // Apps expect a steady stream of fixes; one per second is what a real GPS delivers.
            while (isActive) {
                for (p in added) runCatching { lm.setTestProviderLocation(p, fix(p, lat, lon, acc)) }
                delay(1_000)
            }
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun addProvider(lm: LocationManager, name: String) {
        runCatching { lm.removeTestProvider(name) }
        if (Build.VERSION.SDK_INT >= 31) {
            lm.addTestProvider(
                name,
                ProviderProperties.Builder()
                    .setHasSatelliteRequirement(name == LocationManager.GPS_PROVIDER)
                    .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                    .setAccuracy(ProviderProperties.ACCURACY_FINE)
                    .build(),
            )
        } else {
            lm.addTestProvider(name, false, name == LocationManager.GPS_PROVIDER, false, false, true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
        }
        lm.setTestProviderEnabled(name, true)
        added += name
    }

    private fun fix(provider: String, lat: Double, lon: Double, acc: Float) = Location(provider).apply {
        latitude = lat
        longitude = lon
        accuracy = acc
        altitude = 0.0
        speed = 0f
        bearing = 0f
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }

    private fun startForegroundCompat(lat: Double, lon: Double) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Location spoofing", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, SpoofService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Location spoofing is on")
            .setContentText(String.format(java.util.Locale.US, "Every app sees %.5f, %.5f", lat, lon))
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun shutdown() {
        job?.cancel()
        job = null
        val lm = getSystemService(LocationManager::class.java)
        added.forEach { runCatching { lm.removeTestProvider(it) } }
        added.clear()
        if (LocationSpoof.state.value.active) LocationSpoof.set(SpoofState(false))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        job?.cancel()
        val lm = getSystemService(LocationManager::class.java)
        added.forEach { runCatching { lm.removeTestProvider(it) } }
        added.clear()
        if (LocationSpoof.state.value.active) LocationSpoof.set(SpoofState(false))
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "atom.device.SPOOF_STOP"
        private const val CHANNEL = "atom_location_spoof"
        private const val NOTIFICATION_ID = 4711
    }
}
