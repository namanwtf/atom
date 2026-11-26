package atom.automation.rule

import atom.automation.schedule.ScheduleText
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/** A snapshot of the phone read only when a rule is about to run (never polled). */
interface DeviceState {
    val now: Instant
    val zone: ZoneId
    val batteryPercent: Int?
    val charging: Boolean?
    /** Null when not on Wi-Fi or when the name is hidden (no location permission). */
    val wifiSsid: String?
    val rooted: Boolean
}

data class StaticDeviceState(
    override val now: Instant,
    override val zone: ZoneId,
    override val batteryPercent: Int? = null,
    override val charging: Boolean? = null,
    override val wifiSsid: String? = null,
    override val rooted: Boolean = false,
) : DeviceState

/** Something the system told us happened. */
sealed interface TriggerEvent {
    val summary: String
    val vars: Map<String, String> get() = emptyMap()

    data object ChargerConnected : TriggerEvent { override val summary = "Charger connected" }
    data object ChargerDisconnected : TriggerEvent { override val summary = "Charger disconnected" }
    data class Battery(val level: Int, val previous: Int?) : TriggerEvent {
        override val summary = "Battery at $level%"
        override val vars = mapOf("battery" to "$level")
    }
    data object ScreenOn : TriggerEvent { override val summary = "Screen on" }
    data object ScreenOff : TriggerEvent { override val summary = "Screen off" }
    data object Unlocked : TriggerEvent { override val summary = "Phone unlocked" }
    data class Wifi(val connected: Boolean, val ssid: String?) : TriggerEvent {
        override val summary = (if (connected) "Connected to " else "Disconnected from ") + (ssid ?: "Wi-Fi")
        override val vars = mapOfNotNull("ssid" to ssid)
    }
    data class Bluetooth(val connected: Boolean, val name: String?, val address: String?) : TriggerEvent {
        override val summary = (if (connected) "Connected " else "Disconnected ") + (name ?: address ?: "Bluetooth device")
        override val vars = mapOfNotNull("device" to (name ?: address))
    }
    data class Headset(val plugged: Boolean) : TriggerEvent { override val summary = if (plugged) "Headset plugged in" else "Headset unplugged" }
    data class Airplane(val on: Boolean) : TriggerEvent { override val summary = "Airplane mode " + if (on) "on" else "off" }
    data class Dnd(val on: Boolean) : TriggerEvent { override val summary = "Do Not Disturb " + if (on) "on" else "off" }
    data class Place(val ruleId: String, val entering: Boolean) : TriggerEvent {
        override val summary = if (entering) "Arrived at the place" else "Left the place"
    }
    data class Notification(val packageName: String, val appLabel: String?, val title: String?, val text: String?) : TriggerEvent {
        override val summary = "Notification from ${appLabel ?: packageName}"
        override val vars = mapOfNotNull("event.app" to (appLabel ?: packageName), "event.title" to title, "event.text" to text)
    }
    data class AppOpened(val packageName: String, val appLabel: String?) : TriggerEvent {
        override val summary = "Opened ${appLabel ?: packageName}"
        override val vars = mapOfNotNull("event.app" to (appLabel ?: packageName))
    }
    data object Boot : TriggerEvent { override val summary = "Phone started" }
    data class Time(val ruleId: String) : TriggerEvent { override val summary = "Scheduled time" }
    data object Manual : TriggerEvent { override val summary = "Test run" }
}

private fun mapOfNotNull(vararg pairs: Pair<String, String?>): Map<String, String> =
    pairs.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()

object Triggers {
    fun matches(trigger: Trigger, event: TriggerEvent, ruleId: String): Boolean = when (trigger) {
        is Trigger.Time -> event is TriggerEvent.Time && event.ruleId == ruleId
        Trigger.ChargerConnected -> event == TriggerEvent.ChargerConnected
        Trigger.ChargerDisconnected -> event == TriggerEvent.ChargerDisconnected
        is Trigger.BatteryLevel -> event is TriggerEvent.Battery && event.previous != null && crossed(trigger, event.previous, event.level)
        Trigger.ScreenOn -> event == TriggerEvent.ScreenOn
        Trigger.ScreenOff -> event == TriggerEvent.ScreenOff
        Trigger.Unlocked -> event == TriggerEvent.Unlocked
        is Trigger.WifiConnected -> event is TriggerEvent.Wifi && event.connected && ssidMatches(trigger.ssid, event.ssid)
        is Trigger.WifiDisconnected -> event is TriggerEvent.Wifi && !event.connected && ssidMatches(trigger.ssid, event.ssid)
        is Trigger.BluetoothConnected -> event is TriggerEvent.Bluetooth && event.connected && deviceMatches(trigger.device, event)
        is Trigger.BluetoothDisconnected -> event is TriggerEvent.Bluetooth && !event.connected && deviceMatches(trigger.device, event)
        Trigger.HeadsetPlugged -> event == TriggerEvent.Headset(true)
        Trigger.HeadsetUnplugged -> event == TriggerEvent.Headset(false)
        is Trigger.AirplaneMode -> event is TriggerEvent.Airplane && (trigger.on == null || trigger.on == event.on)
        is Trigger.DndChanged -> event is TriggerEvent.Dnd && (trigger.on == null || trigger.on == event.on)
        is Trigger.Location -> event is TriggerEvent.Place && event.ruleId == ruleId && event.entering == (trigger.transition != "leave")
        is Trigger.Notification -> event is TriggerEvent.Notification && appMatches(trigger.app, event.packageName, event.appLabel) &&
            (trigger.contains == null || listOfNotNull(event.title, event.text).any { it.contains(trigger.contains, ignoreCase = true) })
        is Trigger.AppOpened -> event is TriggerEvent.AppOpened && appMatches(trigger.app, event.packageName, event.appLabel)
        Trigger.BootCompleted -> event == TriggerEvent.Boot
    }

    fun crossed(t: Trigger.BatteryLevel, previous: Int, level: Int): Boolean =
        if (t.direction == "above") previous < t.percent && level >= t.percent else previous > t.percent && level <= t.percent

    private fun ssidMatches(want: String?, got: String?) = want == null || (got != null && want.trim('"').equals(got.trim('"'), ignoreCase = true))

    private fun deviceMatches(want: String?, e: TriggerEvent.Bluetooth) = want == null ||
        e.address?.equals(want, ignoreCase = true) == true || e.name?.contains(want, ignoreCase = true) == true

    fun appMatches(want: String?, pkg: String, label: String?) = want == null ||
        want.equals(pkg, ignoreCase = true) || label?.equals(want, ignoreCase = true) == true ||
        (want.length >= 4 && label?.contains(want, ignoreCase = true) == true)

    fun describe(t: Trigger): String = when (t) {
        is Trigger.Time -> ScheduleText.parseOrNull(t.schedule)?.let { ScheduleText.describe(it) } ?: t.schedule
        Trigger.ChargerConnected -> "Charger connected"
        Trigger.ChargerDisconnected -> "Charger disconnected"
        is Trigger.BatteryLevel -> "Battery ${if (t.direction == "above") "rises to" else "drops to"} ${t.percent}%"
        Trigger.ScreenOn -> "Screen turns on"
        Trigger.ScreenOff -> "Screen turns off"
        Trigger.Unlocked -> "Phone unlocked"
        is Trigger.WifiConnected -> "Wi-Fi connects" + (t.ssid?.let { " to $it" } ?: "")
        is Trigger.WifiDisconnected -> "Wi-Fi disconnects" + (t.ssid?.let { " from $it" } ?: "")
        is Trigger.BluetoothConnected -> "Bluetooth connects" + (t.device?.let { " to $it" } ?: "")
        is Trigger.BluetoothDisconnected -> "Bluetooth disconnects" + (t.device?.let { " from $it" } ?: "")
        Trigger.HeadsetPlugged -> "Headset plugged in"
        Trigger.HeadsetUnplugged -> "Headset unplugged"
        is Trigger.AirplaneMode -> "Airplane mode " + when (t.on) { true -> "turns on"; false -> "turns off"; null -> "changes" }
        is Trigger.DndChanged -> "Do Not Disturb " + when (t.on) { true -> "turns on"; false -> "turns off"; null -> "changes" }
        is Trigger.Location -> (if (t.transition == "leave") "Leaving " else "Arriving at ") + (t.label ?: "%.4f, %.4f".format(t.latitude, t.longitude)) + " (${t.radiusM} m)"
        is Trigger.Notification -> "Notification" + (t.app?.let { " from $it" } ?: "") + (t.contains?.let { " containing \"$it\"" } ?: "")
        is Trigger.AppOpened -> "${t.app} opens"
        Trigger.BootCompleted -> "Phone starts"
    }
}

object Conditions {
    fun holds(c: Condition, s: DeviceState): Boolean = when (c) {
        is Condition.TimeWindow -> {
            val z = s.now.atZone(s.zone)
            val m = z.hour * 60 + z.minute
            val a = minutes(c.start)
            val b = minutes(c.end)
            when {
                a == b -> true
                a < b -> m in a until b
                else -> m >= a || m < b
            }
        }
        is Condition.Days -> s.now.atZone(s.zone).dayOfWeek in days(c.days)
        is Condition.Battery -> s.batteryPercent?.let { p -> (c.min == null || p >= c.min) && (c.max == null || p <= c.max) } ?: false
        is Condition.Charging -> s.charging?.let { it == c.charging } ?: false
        is Condition.WifiSsid -> {
            val on = s.wifiSsid?.trim('"')?.equals(c.ssid.trim('"'), ignoreCase = true) == true
            on == c.connected
        }
        is Condition.Rooted -> s.rooted == c.rooted
    }

    fun minutes(hhmm: String): Int = runCatching { ScheduleText.timeOfDay(hhmm) }.getOrDefault(0)

    fun days(names: List<String>): Set<DayOfWeek> = runCatching { ScheduleText.days(names.joinToString(",")) }.getOrDefault(emptySet())

    fun describe(c: Condition): String = when (c) {
        is Condition.TimeWindow -> "Between ${c.start} and ${c.end}"
        is Condition.Days -> {
            val d = days(c.days)
            when (d) {
                atom.automation.schedule.Schedule.WORKDAYS -> "On weekdays"
                setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> "On weekends"
                else -> "On " + DayOfWeek.entries.filter { it in d }.joinToString(", ") { ScheduleText.dayShort(it) }
            }
        }
        is Condition.Battery -> when {
            c.min != null && c.max != null -> "Battery ${c.min}-${c.max}%"
            c.min != null -> "Battery at least ${c.min}%"
            c.max != null -> "Battery at most ${c.max}%"
            else -> "Any battery level"
        }
        is Condition.Charging -> if (c.charging) "While charging" else "While not charging"
        is Condition.WifiSsid -> (if (c.connected) "On Wi-Fi " else "Not on Wi-Fi ") + c.ssid
        is Condition.Rooted -> if (c.rooted) "Phone is rooted" else "Phone is not rooted"
    }

    fun currentValue(c: Condition, s: DeviceState): String = when (c) {
        is Condition.TimeWindow -> s.now.atZone(s.zone).let { "%02d:%02d".format(it.hour, it.minute) }
        is Condition.Days -> ScheduleText.dayShort(s.now.atZone(s.zone).dayOfWeek)
        is Condition.Battery -> s.batteryPercent?.let { "$it%" } ?: "unknown"
        is Condition.Charging -> when (s.charging) { true -> "charging"; false -> "not charging"; null -> "unknown" }
        is Condition.WifiSsid -> s.wifiSsid ?: "no Wi-Fi"
        is Condition.Rooted -> if (s.rooted) "rooted" else "not rooted"
    }
}

object Templates {
    // Braces escaped: Android's ICU regex rejects a bare "}".
    private val TOKEN = Regex("\\{\\{\\s*([a-z_.]+)\\s*\\}\\}")

    fun vars(event: TriggerEvent, s: DeviceState): Map<String, String> = buildMap {
        put("event", event.summary)
        put("time", s.now.atZone(s.zone).let { "%02d:%02d".format(it.hour, it.minute) })
        s.batteryPercent?.let { put("battery", "$it") }
        s.wifiSsid?.let { put("ssid", it) }
        putAll(event.vars)
    }

    fun render(text: String, vars: Map<String, String>): String = TOKEN.replace(text) { m -> vars[m.groupValues[1]] ?: "" }
}

fun Action.describe(): String = when (this) {
    is Action.Agent -> "Ask Atom: \"${prompt}\"" + when (waitFor) {
        WaitFor.None -> ""
        WaitFor.Charging -> " (waits until charging)"
        WaitFor.Idle -> " (waits until idle)"
    }
    is Action.Tool -> "Run $tool" + if (args.isEmpty()) "" else " " + args.entries.joinToString(", ", "(", ")") { "${it.key}: ${it.value}" }
    is Action.Notify -> "Notify: $title" + if (text.isBlank()) "" else " - $text"
    is Action.Wait -> "Wait ${seconds} s"
    is Action.StopUnless -> "Stop unless: ${Conditions.describe(condition).replaceFirstChar { it.lowercase() }}"
}
