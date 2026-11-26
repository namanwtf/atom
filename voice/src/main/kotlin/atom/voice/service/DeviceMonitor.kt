package atom.voice.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.PowerManager
import atom.voice.policy.DeviceConditions

/**
 * Tracks the inputs of [atom.voice.policy.ListeningPolicy] purely from system callbacks (broadcasts, thermal
 * listener, audio-mode listener, recording callback): no polling, no alarms, no wake locks. Main thread only.
 * [onChange] fires only when a value the policy cares about actually changed.
 */
internal class DeviceMonitor(
    private val context: Context,
    private val handler: Handler,
    private val onChange: () -> Unit,
) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)

    private var screenOn = true
    private var charging = false
    private var batteryPercent = -1
    private var powerSave = false
    private var thermalStatus = 0
    private var audioMode = AudioManager.MODE_NORMAL
    private var recordings: List<AudioRecordingConfiguration> = emptyList()
    private var started = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val changed = when (intent.action) {
                Intent.ACTION_SCREEN_ON -> update(screen = true)
                Intent.ACTION_SCREEN_OFF -> update(screen = false)
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> update(saver = power.isPowerSaveMode)
                else -> false
            }
            // Every event also re-reads the battery (plug state, the 20% line) from the sticky intent, for free.
            val battery = refreshBattery()
            if (changed || battery) onChange()
        }
    }

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        if (status != thermalStatus) {
            thermalStatus = status
            onChange()
        }
    }

    private val modeListener: Any? = if (Build.VERSION.SDK_INT >= 31) {
        AudioManager.OnModeChangedListener { mode ->
            if (mode != audioMode) {
                audioMode = mode
                onChange()
            }
        }
    } else {
        null
    }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            recordings = configs.toList()
            if (Build.VERSION.SDK_INT < 31) audioMode = audio.mode // calls usually start a capture too
            onChange()
        }
    }

    fun start() {
        if (started) return
        started = true
        screenOn = power.isInteractive
        powerSave = power.isPowerSaveMode
        thermalStatus = power.currentThermalStatus
        audioMode = audio.mode
        recordings = audio.activeRecordingConfigurations
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            // Not BATTERY_CHANGED: it arrives on every voltage and temperature tick (every few seconds while
            // charging) and would wake the listener for nothing. Plug changes and the low-battery edges are enough.
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
        }
        // All protected system broadcasts.
        context.registerReceiver(receiver, filter, null, handler)
        refreshBattery()
        power.addThermalStatusListener(context.mainExecutor, thermalListener)
        if (Build.VERSION.SDK_INT >= 31) {
            audio.addOnModeChangedListener(context.mainExecutor, modeListener as AudioManager.OnModeChangedListener)
        }
        audio.registerAudioRecordingCallback(recordingCallback, handler)
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { context.unregisterReceiver(receiver) }
        runCatching { power.removeThermalStatusListener(thermalListener) }
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching { audio.removeOnModeChangedListener(modeListener as AudioManager.OnModeChangedListener) }
        }
        runCatching { audio.unregisterAudioRecordingCallback(recordingCallback) }
    }

    /**
     * @param ownSessionId our AudioRecord's session (0 if none) so our own capture never counts as "busy".
     * @param ignoreMic true while our own speech recognizer holds the mic.
     */
    fun conditions(ownSessionId: Int, ignoreMic: Boolean): DeviceConditions {
        if (Build.VERSION.SDK_INT < 31) audioMode = audio.mode
        val micBusy = !ignoreMic && recordings.any { cfg ->
            if (ownSessionId != 0 && cfg.clientAudioSessionId == ownSessionId) cfg.isClientSilenced else true
        }
        return DeviceConditions(
            screenOn = screenOn,
            charging = charging,
            powerSave = powerSave,
            thermalStatus = thermalStatus,
            batteryPercent = batteryPercent,
            inCall = audioMode in CALL_MODES,
            micBusy = micBusy,
        )
    }

    private fun update(screen: Boolean = screenOn, saver: Boolean = powerSave): Boolean {
        val changed = screen != screenOn || saver != powerSave
        screenOn = screen
        powerSave = saver
        return changed
    }

    /** Reads the sticky BATTERY_CHANGED intent without subscribing to it. */
    private fun refreshBattery(): Boolean {
        val sticky = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        return sticky?.let(::readBattery) ?: false
    }

    /** Only plug state and the 20% line matter. */
    private fun readBattery(intent: Intent): Boolean {
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val low = percent in 0 until 20
        val changed = plugged != charging || low != (batteryPercent in 0 until 20)
        charging = plugged
        batteryPercent = percent
        return changed
    }

    private companion object {
        /** MODE_RINGTONE, MODE_IN_CALL, MODE_IN_COMMUNICATION, MODE_CALL_SCREENING, MODE_CALL_REDIRECT, MODE_COMMUNICATION_REDIRECT. */
        val CALL_MODES = setOf(1, 2, 3, 4, 5, 6)
    }
}
