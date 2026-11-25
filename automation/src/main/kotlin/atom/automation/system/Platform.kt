package atom.automation.system

import android.Manifest
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import atom.automation.rule.DeviceState
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** One alarm for the earliest wake-up across all tasks and timed automations. */
internal class AlarmScheduler(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    private fun intent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_WAKE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun canExact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    fun arm(atMillis: Long?) {
        val pi = intent()
        if (atMillis == null) {
            alarms.cancel(pi)
            return
        }
        try {
            if (canExact()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            }
        } catch (_: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        }
    }
}

internal class AndroidDeviceState(private val context: Context) : DeviceState {
    override val now: Instant = Instant.now()
    override val zone: ZoneId = ZoneId.systemDefault()

    private val battery: Intent? by lazy {
        runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
    }

    override val batteryPercent: Int? get() = battery?.let {
        val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) null else level * 100 / scale
    }

    override val charging: Boolean? get() = battery?.let {
        val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL ||
            it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    @Suppress("DEPRECATION")
    override val wifiSsid: String? get() = runCatching {
        val info = context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo ?: return null
        info.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID.trim('<', '>') && !it.startsWith("<") }
    }.getOrNull()

    override val rooted: Boolean get() = Root.present
}

internal object Root {
    val present: Boolean by lazy {
        listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su", "/data/adb/ksu", "/data/adb/magisk", "/data/adb/ap")
            .any { runCatching { File(it).exists() }.getOrDefault(false) }
    }
}

internal object PhoneState {
    fun locked(context: Context): Boolean = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
}

/** A check on the health card, with the screen that fixes it. */
data class HealthItem(val title: String, val ok: Boolean, val detail: String, val fix: Intent?, val key: String = title)

object Health {
    const val KEY_BATTERY = "battery"
    const val KEY_EXACT = "exact_alarms"
    const val KEY_NOTIFICATIONS = "notifications"

    fun scheduled(context: Context): List<HealthItem> = listOf(battery(context), exactAlarms(context), notifications(context))

    fun battery(context: Context): HealthItem {
        val ok = context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
        return HealthItem(
            "Unrestricted battery",
            ok,
            if (ok) "Runs are not cut off by Doze" else "Doze can delay or cut off background runs",
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
            KEY_BATTERY,
        )
    }

    fun exactAlarms(context: Context): HealthItem {
        val ok = AlarmScheduler(context).canExact()
        return HealthItem(
            "Exact alarms",
            ok,
            if (ok) "Tasks start on the minute" else "Tasks may start several minutes late",
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")) else null,
            KEY_EXACT,
        )
    }

    fun notifications(context: Context): HealthItem {
        val ok = NotificationManagerCompat.from(context).areNotificationsEnabled()
        return HealthItem(
            "Notifications",
            ok,
            if (ok) "Results and alerts can reach you" else "You will not see results or failures",
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            KEY_NOTIFICATIONS,
        )
    }

    fun notificationAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun accessibility(context: Context): Boolean =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.any { it.startsWith(context.packageName + "/") } == true

    fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun location(context: Context) =
        granted(context, Manifest.permission.ACCESS_COARSE_LOCATION) || granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    fun backgroundLocation(context: Context) = granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun bluetooth(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted(context, Manifest.permission.BLUETOOTH_CONNECT)

    fun appSettings(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
}
