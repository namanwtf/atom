package atom.automation.rule

import atom.automation.schedule.Schedule
import atom.automation.schedule.ScheduleText
import atom.automation.schedule.nextRuns
import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class Conflict(val ruleA: String, val ruleB: String, val setting: String, val valueA: String, val valueB: String) {
    fun message(nameOf: (String) -> String) =
        "\"${nameOf(ruleA)}\" and \"${nameOf(ruleB)}\" can fire together and set $setting to $valueA vs $valueB"
}

/** Finds pairs of rules that can fire on the same event (or minute) and drive one setting to different values. */
object ConflictDetector {
    /** Argument names that pick which setting a tool changes rather than the value it sets. */
    private val SELECTORS = setOf("setting", "name", "key", "toggle", "target", "package", "app", "namespace", "radio", "feature", "which", "kind", "type", "control")

    fun find(rules: List<Rule>, zone: ZoneId, now: Instant = Instant.now()): List<Conflict> {
        val enabled = rules.filter { it.enabled }
        val out = mutableListOf<Conflict>()
        for (i in enabled.indices) for (j in i + 1 until enabled.size) {
            val a = enabled[i]
            val b = enabled[j]
            val writesA = writes(a)
            val writesB = writes(b)
            val clashes = writesA.keys.intersect(writesB.keys).filter { writesA[it] != writesB[it] }
            if (clashes.isEmpty()) continue
            if (!triggersOverlap(a, b, zone, now) || !conditionsCompatible(a.conditions, b.conditions)) continue
            clashes.forEach { out += Conflict(a.id, b.id, it, writesA.getValue(it), writesB.getValue(it)) }
        }
        return out
    }

    fun conflictsFor(ruleId: String, all: List<Conflict>) = all.filter { it.ruleA == ruleId || it.ruleB == ruleId }

    /** setting key → final value written by the rule. */
    fun writes(rule: Rule): Map<String, String> {
        val out = linkedMapOf<String, String>()
        rule.actions.filterIsInstance<Action.Tool>().forEach { a ->
            val selector = a.args.filterKeys { it in SELECTORS }
            val value = a.args.filterKeys { it !in SELECTORS }
            if (value.isEmpty()) return@forEach
            val key = a.tool + if (selector.isEmpty()) "" else selector.entries.sortedBy { it.key }.joinToString(",", "(", ")") { "${it.key}=${it.value}" }
            out[key] = JsonObject(value.toSortedMap()).toString()
        }
        return out
    }

    fun triggersOverlap(a: Rule, b: Rule, zone: ZoneId, now: Instant): Boolean {
        val x = a.trigger
        val y = b.trigger
        return when {
            x is Trigger.Time && y is Trigger.Time -> timesCollide(x, a.createdAt, y, b.createdAt, zone, now)
            x::class != y::class -> false
            x is Trigger.WifiConnected && y is Trigger.WifiConnected -> sameOrAny(x.ssid, y.ssid)
            x is Trigger.WifiDisconnected && y is Trigger.WifiDisconnected -> sameOrAny(x.ssid, y.ssid)
            x is Trigger.BluetoothConnected && y is Trigger.BluetoothConnected -> sameOrAny(x.device, y.device)
            x is Trigger.BluetoothDisconnected && y is Trigger.BluetoothDisconnected -> sameOrAny(x.device, y.device)
            x is Trigger.BatteryLevel && y is Trigger.BatteryLevel -> x.direction == y.direction && x.percent == y.percent
            x is Trigger.AirplaneMode && y is Trigger.AirplaneMode -> x.on == null || y.on == null || x.on == y.on
            x is Trigger.DndChanged && y is Trigger.DndChanged -> x.on == null || y.on == null || x.on == y.on
            x is Trigger.Location && y is Trigger.Location -> x.transition == y.transition &&
                distanceM(x.latitude, x.longitude, y.latitude, y.longitude) < x.radiusM + y.radiusM
            x is Trigger.Notification && y is Trigger.Notification -> sameOrAny(x.app, y.app)
            x is Trigger.AppOpened && y is Trigger.AppOpened -> x.app.equals(y.app, ignoreCase = true)
            else -> x == y
        }
    }

    private fun sameOrAny(a: String?, b: String?) = a == null || b == null || a.equals(b, ignoreCase = true)

    private fun timesCollide(x: Trigger.Time, xAnchor: Long, y: Trigger.Time, yAnchor: Long, zone: ZoneId, now: Instant): Boolean {
        val sx = ScheduleText.parseOrNull(x.schedule, Instant.ofEpochMilli(xAnchor)) ?: return false
        val sy = ScheduleText.parseOrNull(y.schedule, Instant.ofEpochMilli(yAnchor)) ?: return false
        val horizon = now.plus(Duration.ofDays(14))
        val fx = sx.nextRuns(now, zone, 400).filter { it.isBefore(horizon) }
        val fy = sy.nextRuns(now, zone, 400).filter { it.isBefore(horizon) }.map { it.epochSecond / 60 }.toSet()
        return fx.any { it.epochSecond / 60 in fy }
    }

    /** False when some pair of conditions can never both hold. */
    fun conditionsCompatible(a: List<Condition>, b: List<Condition>): Boolean {
        for (x in a) for (y in b) if (exclusive(x, y)) return false
        return true
    }

    private fun exclusive(x: Condition, y: Condition): Boolean = when {
        x is Condition.TimeWindow && y is Condition.TimeWindow -> !windowsOverlap(x, y)
        x is Condition.Days && y is Condition.Days -> Conditions.days(x.days).intersect(Conditions.days(y.days)).isEmpty()
        x is Condition.Battery && y is Condition.Battery -> (x.min ?: 0) > (y.max ?: 100) || (y.min ?: 0) > (x.max ?: 100)
        x is Condition.Charging && y is Condition.Charging -> x.charging != y.charging
        x is Condition.Rooted && y is Condition.Rooted -> x.rooted != y.rooted
        x is Condition.WifiSsid && y is Condition.WifiSsid -> when {
            x.connected && y.connected -> !x.ssid.equals(y.ssid, ignoreCase = true)
            x.connected != y.connected -> x.ssid.equals(y.ssid, ignoreCase = true)
            else -> false
        }
        else -> false
    }

    private fun windowsOverlap(x: Condition.TimeWindow, y: Condition.TimeWindow): Boolean {
        fun minutesOf(w: Condition.TimeWindow): BooleanArray {
            val s = Conditions.minutes(w.start)
            val e = Conditions.minutes(w.end)
            return BooleanArray(1440) { m -> if (s == e) true else if (s < e) m in s until e else m >= s || m < e }
        }
        val a = minutesOf(x)
        val b = minutesOf(y)
        return (0 until 1440).any { a[it] && b[it] }
    }

    private fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }
}

enum class CostLevel(val label: String) {
    None("No extra battery"),
    VeryLow("Very low battery use"),
    Low("Low battery use"),
    Medium("Medium battery use"),
    High("High battery use"),
}

data class BatteryCost(val level: CostLevel, val reason: String)

/** A rough, honest estimate of what a rule costs, based on how its trigger wakes the phone and what it runs. */
object BatteryEstimator {
    fun estimate(rule: Rule, zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now()): BatteryCost {
        val (base, why, frequent) = trigger(rule, zone, now)
        val agents = rule.actions.filterIsInstance<Action.Agent>()
        val eager = agents.any { it.waitFor == WaitFor.None }
        var level = base
        val notes = mutableListOf(why)
        if (agents.isNotEmpty()) {
            if (eager && frequent) {
                level = CostLevel.entries[(level.ordinal + 2).coerceAtMost(CostLevel.High.ordinal)]
                notes += "runs the model on a frequent event"
            } else if (eager) {
                level = CostLevel.entries[(level.ordinal + 1).coerceAtMost(CostLevel.High.ordinal)]
                notes += "each run uses the model"
            } else {
                notes += "model runs only while charging or idle"
            }
        }
        return BatteryCost(level, notes.joinToString("; ").replaceFirstChar { it.uppercase() })
    }

    private fun trigger(rule: Rule, zone: ZoneId, now: Instant): Triple<CostLevel, String, Boolean> = when (val t = rule.trigger) {
        is Trigger.Time -> {
            val s = ScheduleText.parseOrNull(t.schedule, Instant.ofEpochMilli(rule.createdAt))
            val perDay = when (s) {
                null -> 0
                is Schedule.Interval -> 1440 / s.minutes.coerceAtLeast(1)
                else -> s.nextRuns(now, zone, 200).count { it.isBefore(now.plus(Duration.ofDays(1))) }
            }
            when {
                perDay > 48 -> Triple(CostLevel.Medium, "wakes the phone about $perDay times a day", true)
                perDay > 12 -> Triple(CostLevel.Low, "wakes the phone about $perDay times a day", true)
                else -> Triple(CostLevel.VeryLow, "one exact alarm per run", false)
            }
        }
        Trigger.BootCompleted -> Triple(CostLevel.None, "runs once at start-up", false)
        is Trigger.BluetoothConnected, is Trigger.BluetoothDisconnected ->
            Triple(CostLevel.VeryLow, "the system wakes Atom only when a device connects or disconnects", false)
        is Trigger.BatteryLevel -> Triple(CostLevel.Low, "listens to each 1% battery change", false)
        is Trigger.Location -> Triple(CostLevel.Medium, "the system checks coarse location in the background", false)
        is Trigger.Notification -> Triple(CostLevel.VeryLow, "uses notification access, which is already listening", true)
        is Trigger.AppOpened -> Triple(CostLevel.Low, "needs Atom's accessibility service, which sees every app switch", true)
        Trigger.ScreenOn, Trigger.ScreenOff, Trigger.Unlocked ->
            Triple(CostLevel.VeryLow, "system broadcast; a quiet service keeps listening", true)
        else -> Triple(CostLevel.VeryLow, "system broadcast; a quiet service keeps listening", false)
    }
}
