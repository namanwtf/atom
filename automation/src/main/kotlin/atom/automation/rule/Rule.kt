package atom.automation.rule

import atom.automation.Capability
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The canonical rule JSON. The same shape is what the compiler asks the model for, what the editor edits and what
 * is stored, so there is exactly one format. Field names are snake_case.
 */
@Serializable
data class Rule(
    val id: String = "",
    val name: String,
    /** What the user wrote; kept so they can see where the rule came from. */
    val sentence: String = "",
    val enabled: Boolean = true,
    val trigger: Trigger,
    val conditions: List<Condition> = emptyList(),
    val actions: List<Action>,
    /** What the direct tool steps may use (agent steps carry their own). Older rules get [Capability.STEP_DEFAULT]. */
    @SerialName("step_capabilities") val stepCapabilities: Set<Capability> = Capability.STEP_DEFAULT,
    val createdAt: Long = 0,
    val lastFiredAt: Long? = null,
)

@Serializable
sealed interface Trigger {
    /** "daily 23:00", "weekdays 8:30", "weekly sat,sun 10:00", "every 30m", "cron 0 9 * * 1-5". */
    @Serializable @SerialName("schedule") data class Time(val schedule: String) : Trigger
    @Serializable @SerialName("charger_connected") data object ChargerConnected : Trigger
    @Serializable @SerialName("charger_disconnected") data object ChargerDisconnected : Trigger
    /** Fires once when the level crosses [percent] in [direction] ("below" or "above"). */
    @Serializable @SerialName("battery_level") data class BatteryLevel(val percent: Int, val direction: String = "below") : Trigger
    @Serializable @SerialName("screen_on") data object ScreenOn : Trigger
    @Serializable @SerialName("screen_off") data object ScreenOff : Trigger
    @Serializable @SerialName("unlocked") data object Unlocked : Trigger
    /** Null [ssid] means any network. */
    @Serializable @SerialName("wifi_connected") data class WifiConnected(val ssid: String? = null) : Trigger
    @Serializable @SerialName("wifi_disconnected") data class WifiDisconnected(val ssid: String? = null) : Trigger
    /** [device] matches a name (case-insensitive substring) or a MAC address; null means any device. */
    @Serializable @SerialName("bluetooth_connected") data class BluetoothConnected(val device: String? = null) : Trigger
    @Serializable @SerialName("bluetooth_disconnected") data class BluetoothDisconnected(val device: String? = null) : Trigger
    @Serializable @SerialName("headset_plugged") data object HeadsetPlugged : Trigger
    @Serializable @SerialName("headset_unplugged") data object HeadsetUnplugged : Trigger
    /** Null [on] means any change. */
    @Serializable @SerialName("airplane_mode") data class AirplaneMode(val on: Boolean? = null) : Trigger
    @Serializable @SerialName("dnd_changed") data class DndChanged(val on: Boolean? = null) : Trigger
    @Serializable @SerialName("location") data class Location(
        val latitude: Double,
        val longitude: Double,
        @SerialName("radius_m") val radiusM: Int = 200,
        /** "enter" or "leave". */
        val transition: String = "enter",
        val label: String? = null,
    ) : Trigger
    /** [app] is a package name or app label; [contains] matches title or text, case-insensitive. */
    @Serializable @SerialName("notification") data class Notification(val app: String? = null, val contains: String? = null) : Trigger
    @Serializable @SerialName("app_opened") data class AppOpened(val app: String) : Trigger
    @Serializable @SerialName("boot_completed") data object BootCompleted : Trigger
}

@Serializable
sealed interface Condition {
    /** "HH:mm"; the window may cross midnight. */
    @Serializable @SerialName("time_window") data class TimeWindow(val start: String, val end: String) : Condition
    /** Lowercase three-letter day names. */
    @Serializable @SerialName("days") data class Days(val days: List<String>) : Condition
    @Serializable @SerialName("battery") data class Battery(val min: Int? = null, val max: Int? = null) : Condition
    @Serializable @SerialName("charging") data class Charging(val charging: Boolean = true) : Condition
    @Serializable @SerialName("wifi_ssid") data class WifiSsid(val ssid: String, val connected: Boolean = true) : Condition
    @Serializable @SerialName("rooted") data class Rooted(val rooted: Boolean = true) : Condition
}

@Serializable
enum class WaitFor { @SerialName("none") None, @SerialName("charging") Charging, @SerialName("idle") Idle }

@Serializable
sealed interface Action {
    /** Runs the agent. Strings may use {{event}}, {{event.app}}, {{event.title}}, {{event.text}}, {{time}}, {{battery}}. */
    @Serializable @SerialName("agent") data class Agent(
        val prompt: String,
        @SerialName("wait_for") val waitFor: WaitFor = WaitFor.None,
        val capabilities: Set<Capability> = Capability.DEFAULT,
    ) : Action
    @Serializable @SerialName("tool") data class Tool(val tool: String, val args: JsonObject = JsonObject(emptyMap())) : Action
    @Serializable @SerialName("notify") data class Notify(val title: String, val text: String = "") : Action
    @Serializable @SerialName("wait") data class Wait(val seconds: Int) : Action
    /** Ends the rule here unless [condition] holds right now. */
    @Serializable @SerialName("stop_unless") data class StopUnless(val condition: Condition) : Action
}

@Serializable
enum class RuleOutcome(val label: String) {
    Completed("Done"),
    ConditionsNotMet("Conditions not met"),
    Stopped("Stopped by a check"),
    Failed("Failed"),
    Deferred("Waiting to run"),
}

@Serializable
data class RuleRun(
    val id: String,
    val ruleId: String,
    val at: Long,
    val event: String,
    val outcome: RuleOutcome,
    val steps: List<String> = emptyList(),
)

const val MAX_WAIT_SECONDS = 600
