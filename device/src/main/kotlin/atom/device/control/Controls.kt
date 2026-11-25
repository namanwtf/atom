package atom.device.control

import android.app.NotificationManager
import android.app.UiModeManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.telephony.TelephonyManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Every control Atom can change, keyed by the name tools accept. */
object Controls {
    val flashlight = Flashlight
    val autoRotate = SettingBool("auto_rotate", "Auto-rotate", SysSettings.Ns.System, Settings.System.ACCELEROMETER_ROTATION)
    val autoBrightness = SettingBool(
        "auto_brightness", "Auto-brightness", SysSettings.Ns.System, Settings.System.SCREEN_BRIGHTNESS_MODE,
        onValue = Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC.toString(),
        offValue = Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL.toString(),
    )
    val nightLight = NightLight
    val brightness = Brightness
    val screenTimeout = ScreenTimeout
    val darkMode = DarkMode
    val soundMode = SoundMode
    val dnd = DoNotDisturb
    val wifi = Wifi
    val bluetooth = Bluetooth
    val mobileData = MobileData
    val airplane = Airplane
    val location = LocationToggle
    val nfc = Nfc
    val batterySaver = BatterySaver
    val hotspot = Hotspot
    val volumes: Map<String, Volume> = listOf(
        Volume("media", "Media volume", AudioManager.STREAM_MUSIC),
        Volume("ring", "Ring volume", AudioManager.STREAM_RING),
        Volume("notification", "Notification volume", AudioManager.STREAM_NOTIFICATION),
        Volume("alarm", "Alarm volume", AudioManager.STREAM_ALARM),
        Volume("call", "Call volume", AudioManager.STREAM_VOICE_CALL),
        Volume("system", "System volume", AudioManager.STREAM_SYSTEM),
    ).associateBy { it.stream }

    /** Booleans the set_toggle tool accepts. */
    val toggles: Map<String, BoolControl> = listOf(
        wifi, bluetooth, mobileData, airplane, location, nfc, batterySaver, hotspot, flashlight, autoRotate,
        autoBrightness, nightLight,
    ).associateBy { it.id }

    fun registerAll() {
        ControlRunner.register(*toggles.values.toTypedArray())
        ControlRunner.register(brightness, screenTimeout, darkMode, soundMode, dnd)
        ControlRunner.register(*volumes.values.toTypedArray())
    }
}

object Flashlight : BoolControl("flashlight", "Flashlight") {
    private fun camera(env: DeviceEnv): Pair<CameraManager, String>? {
        val cm = env.app.getSystemService(CameraManager::class.java) ?: return null
        val id = cm.cameraIdList.firstOrNull { id ->
            val c = cm.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        return id?.let { cm to it }
    }

    /** The torch callback reports the current state right after registering; nothing stays registered. */
    override suspend fun read(env: DeviceEnv): Boolean? {
        val (cm, id) = camera(env) ?: return null
        return withTimeoutOrNull(1_000) {
            suspendCancellableCoroutine { cont ->
                val cb = object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                        if (cameraId != id) return
                        cm.unregisterTorchCallback(this)
                        if (cont.isActive) cont.resume(enabled)
                    }

                    override fun onTorchModeUnavailable(cameraId: String) {
                        if (cameraId != id) return
                        cm.unregisterTorchCallback(this)
                        if (cont.isActive) cont.resume(false)
                    }
                }
                cm.registerTorchCallback(cb, Handler(Looper.getMainLooper()))
                cont.invokeOnCancellation { cm.unregisterTorchCallback(cb) }
            }
        }
    }

    override suspend fun write(env: DeviceEnv, value: Boolean): Written {
        val (cm, id) = camera(env) ?: throw ControlError("This phone has no flashlight.")
        try {
            cm.setTorchMode(id, value)
        } catch (e: Exception) {
            throw ControlError("The flashlight is busy (the camera may be in use): ${e.message}")
        }
        return Written.Done("camera API")
    }
}

object NightLight : SettingBool("night_light", "Night light", SysSettings.Ns.Secure, "night_display_activated") {
    override suspend fun write(env: DeviceEnv, value: Boolean): Written {
        val id = Resources.getSystem().getIdentifier("config_nightDisplayAvailable", "bool", "android")
        if (id != 0 && !Resources.getSystem().getBoolean(id)) throw ControlError("This phone has no night light (eye comfort) mode.")
        return super.write(env, value)
    }
}

/** Brightness as the slider position in percent; stored as the raw linear setting. */
object Brightness : Control<Int>("brightness", "Brightness") {
    private fun range(): Pair<Int, Int> {
        val r = Resources.getSystem()
        fun cfg(name: String, def: Int): Int {
            val id = r.getIdentifier(name, "integer", "android")
            return if (id == 0) def else runCatching { r.getInteger(id) }.getOrDefault(def)
        }
        val min = cfg("config_screenBrightnessSettingMinimum", 1).coerceAtLeast(0)
        val max = cfg("config_screenBrightnessSettingMaximum", 255).coerceAtLeast(min + 1)
        return min to max
    }

    override suspend fun read(env: DeviceEnv): Int? =
        SysSettings.get(env, SysSettings.Ns.System, Settings.System.SCREEN_BRIGHTNESS)?.trim()?.toFloatOrNull()?.toInt()

    override suspend fun write(env: DeviceEnv, value: Int): Written =
        Written.Done(SysSettings.put(env, SysSettings.Ns.System, Settings.System.SCREEN_BRIGHTNESS, value.toString()))

    fun rawFor(percent: Int): Int {
        val (min, max) = range()
        return BrightnessCurve.percentToLinear(percent, min, max)
    }

    override fun show(v: Int): String {
        val (min, max) = range()
        return "${BrightnessCurve.linearToPercent(v, min, maxOf(max, v))}%"
    }

    override fun encode(v: Int) = v.toString()
    override fun decode(s: String) = s.toIntOrNull()
    override fun matches(after: Int, target: Int) = show(after) == show(target)
}

object ScreenTimeout : Control<Int>("screen_timeout", "Screen timeout") {
    override suspend fun read(env: DeviceEnv): Int? =
        SysSettings.get(env, SysSettings.Ns.System, Settings.System.SCREEN_OFF_TIMEOUT)?.trim()?.toIntOrNull()

    override suspend fun write(env: DeviceEnv, value: Int): Written =
        Written.Done(SysSettings.put(env, SysSettings.Ns.System, Settings.System.SCREEN_OFF_TIMEOUT, value.toString()))

    override fun show(v: Int): String = when {
        v >= Int.MAX_VALUE / 2 -> "never"
        v >= 60_000 && v % 60_000 == 0 -> "${v / 60_000} min"
        else -> "${v / 1000} s"
    }

    override fun encode(v: Int) = v.toString()
    override fun decode(s: String) = s.toIntOrNull()
}

object DarkMode : Control<String>("dark_mode", "Dark mode") {
    val values = listOf("on", "off", "auto")

    override suspend fun read(env: DeviceEnv): String? = when (env.app.getSystemService(UiModeManager::class.java)?.nightMode) {
        UiModeManager.MODE_NIGHT_YES -> "on"
        UiModeManager.MODE_NIGHT_NO -> "off"
        UiModeManager.MODE_NIGHT_AUTO, UiModeManager.MODE_NIGHT_CUSTOM -> "auto"
        else -> null
    }

    override suspend fun write(env: DeviceEnv, value: String): Written {
        val mode = when (value) {
            "on" -> UiModeManager.MODE_NIGHT_YES
            "off" -> UiModeManager.MODE_NIGHT_NO
            else -> UiModeManager.MODE_NIGHT_AUTO
        }
        val sh = env.privileged()
        if (sh != null) {
            val arg = when (value) { "on" -> "yes"; "off" -> "no"; else -> "auto" }
            if (sh.run("cmd uimode night $arg", 10_000).ok) return Written.Done(env.tierName(sh))
        }
        try {
            env.app.getSystemService(UiModeManager::class.java)?.nightMode = mode
            return Written.Done("system API")
        } catch (e: SecurityException) {
            return openPanel(env, "display settings", Intent(Settings.ACTION_DISPLAY_SETTINGS))
        }
    }

    override fun show(v: String) = v
    override fun encode(v: String) = v
    override fun decode(s: String) = s.takeIf { it in values }
}

class Volume(val stream: String, label: String, private val type: Int) : Control<Int>("volume:$stream", label) {
    private fun am(env: DeviceEnv) = env.app.getSystemService(AudioManager::class.java)

    fun max(env: DeviceEnv): Int = am(env)?.getStreamMaxVolume(type) ?: 15
    fun min(env: DeviceEnv): Int = am(env)?.getStreamMinVolume(type) ?: 0

    @Volatile
    private var cachedMax = 15

    override suspend fun read(env: DeviceEnv): Int? = am(env)?.let {
        cachedMax = it.getStreamMaxVolume(type)
        it.getStreamVolume(type)
    }

    override suspend fun write(env: DeviceEnv, value: Int): Written {
        val am = am(env) ?: throw ControlError("Audio service unavailable.")
        try {
            am.setStreamVolume(type, value.coerceIn(am.getStreamMinVolume(type), am.getStreamMaxVolume(type)), 0)
        } catch (e: SecurityException) {
            throw ControlError("Android blocks changing this volume while Do Not Disturb is on unless Atom has Do Not Disturb access (Permissions page).")
        }
        return Written.Done("audio API")
    }

    override fun show(v: Int): String {
        val pct = if (cachedMax > 0) v * 100 / cachedMax else 0
        return "$v/$cachedMax ($pct%)"
    }

    override fun encode(v: Int) = v.toString()
    override fun decode(s: String) = s.toIntOrNull()
}

object SoundMode : Control<String>("sound_mode", "Sound mode") {
    val values = listOf("normal", "vibrate", "silent")

    override suspend fun read(env: DeviceEnv): String? = when (env.app.getSystemService(AudioManager::class.java)?.ringerMode) {
        AudioManager.RINGER_MODE_NORMAL -> "normal"
        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
        AudioManager.RINGER_MODE_SILENT -> "silent"
        else -> null
    }

    override suspend fun write(env: DeviceEnv, value: String): Written {
        val am = env.app.getSystemService(AudioManager::class.java) ?: throw ControlError("Audio service unavailable.")
        val mode = when (value) {
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            "silent" -> AudioManager.RINGER_MODE_SILENT
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        ensureDndAccess(env, "switch the sound mode")
        try {
            am.ringerMode = mode
        } catch (e: SecurityException) {
            throw ControlError("Android refused the sound mode change. Give Atom Do Not Disturb access on the Permissions page.")
        }
        return Written.Done("audio API")
    }

    override fun show(v: String) = v
    override fun encode(v: String) = v
    override fun decode(s: String) = s.takeIf { it in values }
}

/** Grants Atom DND access through Shizuku/root, only after the user says yes to a plain-English question. */
suspend fun ensureDndAccess(env: DeviceEnv, why: String) {
    val nm = env.app.getSystemService(NotificationManager::class.java) ?: return
    if (nm.isNotificationPolicyAccessGranted) return
    val sh = env.privileged() ?: throw ControlError(
        "To $why Atom needs Do Not Disturb access. Ask the user to grant it on Atom's Permissions page.",
    )
    val ok = env.confirm(
        "Atom needs Do Not Disturb access to $why. Grant it now using ${env.tierName(sh)}? " +
            "You can revoke it later in Settings → Notifications → Do Not Disturb access.",
    )
    if (!ok) throw ControlError("The user did not allow granting Do Not Disturb access.")
    sh.run("cmd notification allow_dnd ${env.app.packageName}", 10_000)
    if (!nm.isNotificationPolicyAccessGranted) throw ControlError("Granting Do Not Disturb access did not work on this phone; grant it on the Permissions page.")
}

object DoNotDisturb : Control<String>("dnd", "Do Not Disturb") {
    val values = listOf("off", "priority", "alarms", "total_silence")

    override suspend fun read(env: DeviceEnv): String? = when (env.app.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter) {
        NotificationManager.INTERRUPTION_FILTER_ALL -> "off"
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
        NotificationManager.INTERRUPTION_FILTER_NONE -> "total_silence"
        else -> null
    }

    override suspend fun write(env: DeviceEnv, value: String): Written {
        val nm = env.app.getSystemService(NotificationManager::class.java) ?: throw ControlError("Notification service unavailable.")
        if (nm.isNotificationPolicyAccessGranted) {
            nm.setInterruptionFilter(filter(value))
            return Written.Done("notification API")
        }
        val sh = env.privileged() ?: throw ControlError("Changing Do Not Disturb needs Do Not Disturb access. Ask the user to grant it on Atom's Permissions page.")
        val arg = when (value) { "off" -> "off"; "priority" -> "priority"; "alarms" -> "alarms"; else -> "none" }
        val r = sh.run("cmd notification set_dnd $arg", 10_000)
        if (!r.ok) throw ControlError("cmd notification set_dnd failed: ${r.stderr.take(200)}")
        return Written.Done(env.tierName(sh))
    }

    private fun filter(v: String) = when (v) {
        "priority" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
        "alarms" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
        "total_silence" -> NotificationManager.INTERRUPTION_FILTER_NONE
        else -> NotificationManager.INTERRUPTION_FILTER_ALL
    }

    override fun show(v: String) = v.replace('_', ' ')
    override fun encode(v: String) = v
    override fun decode(s: String) = s.takeIf { it in values }
}

/** A switch the platform no longer lets apps flip: Shizuku/root commands, else the matching system panel. */
abstract class ShellToggle(id: String, label: String) : BoolControl(id, label) {
    abstract fun commands(on: Boolean): List<String>
    abstract fun panels(on: Boolean): List<Intent>
    abstract val panelName: String
    override val settleMs: Long = 6_000

    override suspend fun write(env: DeviceEnv, value: Boolean): Written {
        val sh = env.privileged()
        if (sh != null) {
            env.firstWorking(sh, *commands(value).toTypedArray())?.let { return Written.Done(env.tierName(sh)) }
        }
        return openPanel(env, panelName, *panels(value).toTypedArray())
    }
}

object Wifi : ShellToggle("wifi", "Wi-Fi") {
    override suspend fun read(env: DeviceEnv) = env.app.getSystemService(WifiManager::class.java)?.isWifiEnabled
    override fun commands(on: Boolean) = listOf(
        "cmd wifi set-wifi-enabled ${if (on) "enabled" else "disabled"}",
        "svc wifi ${if (on) "enable" else "disable"}",
    )
    override fun panels(on: Boolean) = listOf(Intent(Settings.Panel.ACTION_WIFI), Intent(Settings.ACTION_WIFI_SETTINGS))
    override val panelName = "Wi-Fi panel"
}

object Bluetooth : ShellToggle("bluetooth", "Bluetooth") {
    override suspend fun read(env: DeviceEnv): Boolean? = try {
        val adapter = env.app.getSystemService(BluetoothManager::class.java)?.adapter
        adapter?.isEnabled
    } catch (e: SecurityException) {
        null
    }

    override fun commands(on: Boolean) = listOf(
        "cmd bluetooth_manager ${if (on) "enable" else "disable"}",
        "svc bluetooth ${if (on) "enable" else "disable"}",
    )

    override fun panels(on: Boolean) = buildList {
        if (on) add(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        add(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    override val panelName = "Bluetooth settings"
}

object MobileData : ShellToggle("mobile_data", "Mobile data") {
    override suspend fun read(env: DeviceEnv): Boolean? = try {
        env.app.getSystemService(TelephonyManager::class.java)?.isDataEnabled
    } catch (e: SecurityException) {
        SysSettings.get(env, SysSettings.Ns.Global, "mobile_data")?.let { it.trim() == "1" }
    }

    override fun commands(on: Boolean) = listOf("svc data ${if (on) "enable" else "disable"}")
    override fun panels(on: Boolean) = listOf(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY), Intent(Settings.ACTION_DATA_ROAMING_SETTINGS))
    override val panelName = "Internet panel"
}

object Airplane : ShellToggle("airplane", "Airplane mode") {
    override suspend fun read(env: DeviceEnv): Boolean? =
        Settings.Global.getInt(env.app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

    override fun commands(on: Boolean) = listOf(
        "cmd connectivity airplane-mode ${if (on) "enable" else "disable"}",
        "settings put global airplane_mode_on ${if (on) 1 else 0} && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $on",
    )

    override fun panels(on: Boolean) = listOf(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
    override val panelName = "airplane mode settings"
}

object LocationToggle : ShellToggle("location", "Location") {
    override suspend fun read(env: DeviceEnv) = env.app.getSystemService(LocationManager::class.java)?.isLocationEnabled
    override fun commands(on: Boolean) = listOf(
        "cmd location set-location-enabled $on",
        "settings put secure location_mode ${if (on) 3 else 0}",
    )
    override fun panels(on: Boolean) = listOf(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    override val panelName = "location settings"
}

object Nfc : ShellToggle("nfc", "NFC") {
    override suspend fun read(env: DeviceEnv): Boolean? = NfcAdapter.getDefaultAdapter(env.app)?.isEnabled

    override suspend fun write(env: DeviceEnv, value: Boolean): Written {
        if (NfcAdapter.getDefaultAdapter(env.app) == null) throw ControlError("This phone has no NFC.")
        return super.write(env, value)
    }

    override fun commands(on: Boolean) = listOf("svc nfc ${if (on) "enable" else "disable"}")
    override fun panels(on: Boolean) = listOf(Intent(Settings.Panel.ACTION_NFC), Intent(Settings.ACTION_NFC_SETTINGS))
    override val panelName = "NFC panel"
}

object BatterySaver : ShellToggle("battery_saver", "Battery saver") {
    override suspend fun read(env: DeviceEnv) = env.app.getSystemService(PowerManager::class.java)?.isPowerSaveMode
    override fun commands(on: Boolean) = listOf(
        "cmd power set-mode ${if (on) 1 else 0}",
        "settings put global low_power ${if (on) 1 else 0}",
    )
    override fun panels(on: Boolean) = listOf(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
    override val panelName = "battery saver settings"
}

/**
 * Personal hotspot. Its state is read from the sticky soft-AP broadcast. Android offers no shell command that
 * starts real tethering with the saved name and password, so Atom opens the hotspot screen instead of creating a
 * new configuration; the saved name and password are never touched.
 */
object Hotspot : BoolControl("hotspot", "Personal hotspot") {
    private const val ACTION = "android.net.wifi.WIFI_AP_STATE_CHANGED"
    override val settleMs: Long = 6_000

    override suspend fun read(env: DeviceEnv): Boolean? {
        val sticky = env.app.registerReceiver(null, IntentFilter(ACTION)) ?: return null
        return when (sticky.getIntExtra("wifi_state", -1)) {
            12, 13 -> true
            10, 11, 14 -> false
            else -> null
        }
    }

    override suspend fun write(env: DeviceEnv, value: Boolean): Written = openPanel(
        env,
        "hotspot settings",
        Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.TetherSettings")),
        Intent(Settings.ACTION_WIRELESS_SETTINGS),
    )
}

/** Opens Android's own screen for granting Atom "Modify system settings". */
fun writeSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))
