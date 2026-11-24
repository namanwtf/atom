package atom.device.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

enum class Thermal { Normal, Warm, Hot, Critical }

data class PowerState(val batteryPercent: Int, val charging: Boolean, val powerSave: Boolean, val thermal: Thermal)

/**
 * Tracks battery, charging, power-save and thermal state purely from system callbacks (sticky battery
 * broadcast, power-save broadcast, thermal listener). Nothing polls; between [start] and [stop] the cost is
 * one receiver and one listener.
 */
class PowerGovernor(context: Context) {
    private val app = context.applicationContext
    private val power = app.getSystemService(PowerManager::class.java)
    private val started = AtomicBoolean(false)

    private val _state = MutableStateFlow(readNow())
    val state: StateFlow<PowerState> = _state.asStateFlow()

    /** Whether running a model on-device is reasonable right now (otherwise prefer cloud or wait). */
    val localInferenceAllowed: Boolean get() = PowerPolicy.localInferenceAllowed(_state.value)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val b = Battery.parse(intent)
                    _state.value = _state.value.copy(batteryPercent = b.percent, charging = b.charging)
                }
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED ->
                    _state.value = _state.value.copy(powerSave = power?.isPowerSaveMode == true)
            }
        }
    }

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        _state.value = _state.value.copy(thermal = PowerPolicy.thermalOf(status))
    }

    private val direct = Executor { it.run() }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        // Sticky: registering returns the current battery intent immediately, so state is fresh at once.
        val sticky = if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(receiver, filter)
        }
        sticky?.let { receiver.onReceive(app, it) }
        power?.addThermalStatusListener(direct, thermalListener) // also delivers the current status
        _state.value = _state.value.copy(powerSave = power?.isPowerSaveMode == true)
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { app.unregisterReceiver(receiver) }
        runCatching { power?.removeThermalStatusListener(thermalListener) }
    }

    private fun readNow(): PowerState {
        val b = Battery.read(app)
        return PowerState(
            batteryPercent = b.percent,
            charging = b.charging,
            powerSave = power?.isPowerSaveMode == true,
            thermal = PowerPolicy.thermalOf(power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE),
        )
    }
}

/** Pure policy, unit tested. */
internal object PowerPolicy {
    const val LOW_BATTERY_PERCENT = 15

    fun localInferenceAllowed(s: PowerState): Boolean = when {
        s.thermal == Thermal.Hot || s.thermal == Thermal.Critical -> false
        s.batteryPercent in 0 until LOW_BATTERY_PERCENT && !s.charging -> false
        else -> true
    }

    fun thermalOf(status: Int): Thermal = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> Thermal.Normal
        PowerManager.THERMAL_STATUS_LIGHT, PowerManager.THERMAL_STATUS_MODERATE -> Thermal.Warm
        PowerManager.THERMAL_STATUS_SEVERE -> Thermal.Hot
        PowerManager.THERMAL_STATUS_CRITICAL,
        PowerManager.THERMAL_STATUS_EMERGENCY,
        PowerManager.THERMAL_STATUS_SHUTDOWN -> Thermal.Critical
        else -> if (status > PowerManager.THERMAL_STATUS_SHUTDOWN) Thermal.Critical else Thermal.Normal
    }
}

internal data class BatterySnapshot(val percent: Int, val charging: Boolean, val plugged: String?, val temperatureC: Float?)

internal object Battery {
    /** Reads the sticky battery broadcast without registering a receiver. */
    fun read(context: Context): BatterySnapshot {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return BatterySnapshot(-1, false, null, null)
        return parse(intent)
    }

    fun parse(intent: Intent): BatterySnapshot {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).takeIf { it > 0 } ?: 100
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return BatterySnapshot(
            percent = if (level < 0) -1 else level * 100 / scale,
            charging = plugged != 0 || status == BatteryManager.BATTERY_STATUS_CHARGING,
            plugged = when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                0 -> null
                else -> "dock"
            },
            temperatureC = if (temp == Int.MIN_VALUE) null else temp / 10f,
        )
    }
}
