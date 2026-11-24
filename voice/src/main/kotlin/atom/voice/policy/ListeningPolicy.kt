package atom.voice.policy

import atom.voice.WakePolicy

/** Snapshot of everything that decides whether the microphone may be open. Pure data, no Android types. */
data class DeviceConditions(
    val screenOn: Boolean,
    val charging: Boolean,
    val powerSave: Boolean,
    /** PowerManager.THERMAL_STATUS_* value. */
    val thermalStatus: Int,
    /** 0..100, or -1 when unknown. */
    val batteryPercent: Int,
    val inCall: Boolean,
    /** Another app is recording, or the system silenced us in favour of another app. */
    val micBusy: Boolean,
)

sealed interface ListenDecision {
    data object Listen : ListenDecision
    data class Pause(val reason: String) : ListenDecision
}

object ListeningPolicy {
    /** PowerManager.THERMAL_STATUS_SEVERE. */
    const val THERMAL_STATUS_SEVERE = 3
    const val LOW_BATTERY_PERCENT = 20

    const val REASON_CALL = "Phone call in progress"
    const val REASON_MIC_BUSY = "Another app is using the microphone"
    const val REASON_POWER_SAVE = "Battery saver is on"
    const val REASON_THERMAL = "Phone is too warm"
    const val REASON_LOW_BATTERY = "Battery below 20%"
    const val REASON_SCREEN_OFF = "Screen is off"

    /** Ordered from most to least important reason so the user sees the one that matters. */
    fun decide(c: DeviceConditions, policy: WakePolicy): ListenDecision = when {
        c.inCall -> ListenDecision.Pause(REASON_CALL)
        c.micBusy -> ListenDecision.Pause(REASON_MIC_BUSY)
        c.powerSave -> ListenDecision.Pause(REASON_POWER_SAVE)
        c.thermalStatus >= THERMAL_STATUS_SEVERE -> ListenDecision.Pause(REASON_THERMAL)
        !c.charging && c.batteryPercent in 0 until LOW_BATTERY_PERCENT -> ListenDecision.Pause(REASON_LOW_BATTERY)
        c.screenOn -> ListenDecision.Listen
        c.charging && policy.listenWhileCharging -> ListenDecision.Listen
        policy.screenOffListening -> ListenDecision.Listen
        else -> ListenDecision.Pause(REASON_SCREEN_OFF)
    }
}
