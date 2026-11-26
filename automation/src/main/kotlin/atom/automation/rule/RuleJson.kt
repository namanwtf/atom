@file:OptIn(ExperimentalSerializationApi::class)

package atom.automation.rule

import atom.automation.Capability
import atom.automation.schedule.Cron
import atom.automation.schedule.ScheduleText
import atom.core.llm.ToolSpec
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

data class Issue(val message: String, val fatal: Boolean)

data class ParseResult(val rule: Rule?, val issues: List<Issue>) {
    val errors: List<String> get() = issues.filter { it.fatal }.map { it.message }
    val warnings: List<String> get() = issues.filterNot { it.fatal }.map { it.message }
    val ok: Boolean get() = rule != null && errors.isEmpty()
}

/**
 * Turns model output (or editor JSON) into a valid [Rule]: extracts the JSON, accepts common synonyms and loose
 * value formats, normalises them into the canonical shape, then validates against the live tool catalogue.
 */
object RuleJson {
    val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
        explicitNulls = false
        encodeDefaults = true
    }

    private val lenient = Json {
        isLenient = true
        allowTrailingComma = true
        allowComments = true
        ignoreUnknownKeys = true
    }

    fun encode(rule: Rule): String = json.encodeToString(Rule.serializer(), rule)
    fun encodeTrigger(t: Trigger): JsonObject = json.encodeToJsonElement(Trigger.serializer(), t).jsonObject
    fun encodeCondition(c: Condition): JsonObject = json.encodeToJsonElement(Condition.serializer(), c).jsonObject
    fun encodeAction(a: Action): JsonObject = json.encodeToJsonElement(Action.serializer(), a).jsonObject

    private val TRIGGER_ALIASES = mapOf(
        "time" to "schedule", "cron" to "schedule", "interval" to "schedule", "timer" to "schedule", "at_time" to "schedule",
        "charger_plugged" to "charger_connected", "charging_started" to "charger_connected", "power_connected" to "charger_connected",
        "plugged_in" to "charger_connected", "charger_plugged_in" to "charger_connected", "charger" to "charger_connected",
        "charger_unplugged" to "charger_disconnected", "charging_stopped" to "charger_disconnected", "power_disconnected" to "charger_disconnected",
        "unplugged" to "charger_disconnected",
        "battery" to "battery_level", "battery_low" to "battery_level", "battery_below" to "battery_level", "battery_above" to "battery_level",
        "screen_unlocked" to "unlocked", "unlock" to "unlocked", "user_present" to "unlocked", "device_unlocked" to "unlocked",
        "screen_turned_on" to "screen_on", "screen_turned_off" to "screen_off", "screen_locked" to "screen_off",
        "wifi_connect" to "wifi_connected", "wifi_disconnect" to "wifi_disconnected", "connected_to_wifi" to "wifi_connected",
        "bluetooth_connect" to "bluetooth_connected", "bluetooth_disconnect" to "bluetooth_disconnected",
        "headphones_plugged" to "headset_plugged", "headphones_connected" to "headset_plugged", "headset_connected" to "headset_plugged",
        "headphones_unplugged" to "headset_unplugged", "headset_disconnected" to "headset_unplugged",
        "airplane" to "airplane_mode", "airplane_mode_changed" to "airplane_mode", "flight_mode" to "airplane_mode",
        "dnd" to "dnd_changed", "do_not_disturb" to "dnd_changed", "do_not_disturb_changed" to "dnd_changed",
        "geofence" to "location", "location_enter" to "location", "location_leave" to "location", "arrive" to "location", "leave" to "location",
        "notification_received" to "notification", "notification_posted" to "notification",
        "app_launched" to "app_opened", "app_open" to "app_opened", "open_app" to "app_opened",
        "boot" to "boot_completed", "startup" to "boot_completed", "device_boot" to "boot_completed",
    )

    private val CONDITION_ALIASES = mapOf(
        "time" to "time_window", "time_range" to "time_window", "between" to "time_window", "after" to "time_window", "before" to "time_window",
        "day_of_week" to "days", "weekday" to "days", "weekdays" to "days", "day" to "days",
        "battery_range" to "battery", "battery_level" to "battery",
        "is_charging" to "charging", "charging_state" to "charging", "power" to "charging",
        "wifi" to "wifi_ssid", "ssid" to "wifi_ssid", "connected_wifi" to "wifi_ssid",
        "root" to "rooted", "is_rooted" to "rooted",
    )

    private val ACTION_ALIASES = mapOf(
        "run_agent" to "agent", "prompt" to "agent", "ask_agent" to "agent", "llm" to "agent", "ai" to "agent", "agent_prompt" to "agent",
        "call_tool" to "tool", "tool_call" to "tool", "invoke_tool" to "tool", "device" to "tool",
        "notification" to "notify", "post_notification" to "notify", "notify_user" to "notify", "show_notification" to "notify",
        "delay" to "wait", "sleep" to "wait", "pause" to "wait",
        "stop_if_not" to "stop_unless", "only_if" to "stop_unless", "check" to "stop_unless", "require" to "stop_unless", "stop_if" to "stop_unless",
    )

    fun extractJson(text: String): String? {
        val body = text.replace(Regex("```(?:json)?"), "").trim()
        val start = body.indexOf('{')
        val end = body.lastIndexOf('}')
        return if (start >= 0 && end > start) body.substring(start, end + 1) else null
    }

    fun parse(text: String, tools: List<ToolSpec>? = null, sentence: String = ""): ParseResult {
        val raw = extractJson(text) ?: return ParseResult(null, listOf(Issue("No JSON object found", true)))
        val element = runCatching { lenient.parseToJsonElement(raw) }.getOrElse {
            return ParseResult(null, listOf(Issue("Invalid JSON: ${it.message?.lineSequence()?.firstOrNull()}", true)))
        }
        return parseElement(element, tools, sentence)
    }

    fun parseElement(element: JsonElement, tools: List<ToolSpec>?, sentence: String = ""): ParseResult {
        val issues = mutableListOf<Issue>()
        val root = (element as? JsonObject)?.let { o -> (o["rule"] as? JsonObject) ?: o }
            ?: return ParseResult(null, listOf(Issue("Expected a JSON object", true)))
        val repaired = repairRule(root, sentence, issues)
        val rule = runCatching { json.decodeFromJsonElement(Rule.serializer(), repaired) }.getOrElse {
            issues += Issue("Rule shape is wrong: ${it.message?.lineSequence()?.firstOrNull()}", true)
            return ParseResult(null, issues)
        }
        val fixed = fixTools(rule, tools, issues)
        issues += validate(fixed, tools)
        return ParseResult(fixed, issues.distinct())
    }

    // ------------------------------------------------------------------ repair

    private fun repairRule(o: JsonObject, sentence: String, issues: MutableList<Issue>): JsonObject {
        val m = o.toMutableMap()
        val name = str(m["name"]) ?: str(m["title"])
        m["name"] = JsonPrimitive(name?.takeIf { it.isNotBlank() } ?: sentence.take(48).ifBlank { "Automation" })
        m.remove("title")
        if (m["sentence"] == null && sentence.isNotBlank()) m["sentence"] = JsonPrimitive(sentence)
        val trig = m["trigger"] ?: m["when"] ?: m["event"]
        m.remove("when"); m.remove("event")
        when {
            trig == null -> issues += Issue("The rule has no trigger", true)
            else -> {
                val one = if (trig is JsonArray) {
                    if (trig.size > 1) issues += Issue("Only one trigger per rule; kept the first", false)
                    trig.firstOrNull()
                } else trig
                if (one != null) m["trigger"] = repairTrigger(asTyped(one), issues)
            }
        }
        m["conditions"] = JsonArray(list(m["conditions"] ?: m["if"] ?: m["only_if"]).map { repairCondition(asTyped(it), issues) })
        m.remove("if"); m.remove("only_if")
        val actions = list(m["actions"] ?: m["then"] ?: m["do"] ?: m["steps"])
        m.remove("then"); m.remove("do"); m.remove("steps")
        m["actions"] = JsonArray(actions.map { repairAction(asTyped(it), issues) })
        val stepCaps = m["step_capabilities"]
        if (stepCaps is JsonArray) {
            m["step_capabilities"] = JsonArray(stepCaps.mapNotNull { str(it)?.let(Capability::fromTag)?.tag?.let(::JsonPrimitive) })
        } else {
            m.remove("step_capabilities")
        }
        return JsonObject(m)
    }

    private fun list(e: JsonElement?): List<JsonElement> = when (e) {
        null, JsonNull -> emptyList()
        is JsonArray -> e
        else -> listOf(e)
    }

    private fun asTyped(e: JsonElement): JsonObject = when (e) {
        is JsonObject -> e
        is JsonPrimitive -> JsonObject(mapOf("type" to e))
        else -> JsonObject(emptyMap())
    }

    private fun key(s: String) = s.trim().lowercase().replace(Regex("[\\s\\-.]+"), "_")

    private fun type(o: MutableMap<String, JsonElement>, aliases: Map<String, String>): String {
        val raw = str(o["type"]) ?: str(o["kind"]) ?: str(o["action"]) ?: str(o["trigger"]) ?: ""
        o.remove("kind")
        val k = key(raw)
        val t = aliases[k] ?: k
        o["type"] = JsonPrimitive(t)
        return t
    }

    private fun rename(o: MutableMap<String, JsonElement>, target: String, vararg sources: String) {
        if (o[target] != null && o[target] != JsonNull) return
        for (s in sources) {
            val v = o.remove(s) ?: continue
            if (v != JsonNull) {
                o[target] = v
                return
            }
        }
    }

    private fun repairTrigger(src: JsonObject, issues: MutableList<Issue>): JsonObject {
        val o = src.toMutableMap()
        val rawType = key(str(o["type"]) ?: str(o["kind"]) ?: "")
        when (type(o, TRIGGER_ALIASES)) {
            "schedule" -> {
                val schedule = str(o["schedule"]) ?: str(o["spec"])
                    ?: str(o["cron"])?.let { "cron $it" }
                    ?: (str(o["interval_minutes"]) ?: str(o["minutes"]))?.let { "every ${it}m" }
                    ?: str(o["interval"])?.let { "every $it" }
                    ?: str(o["time"])?.let { t -> str(o["days"])?.let { "weekly $it $t" } ?: "daily $t" }
                    ?: (o["days"] as? JsonArray)?.let { d -> str(o["at"])?.let { "weekly ${d.joinToString(",") { str(it) ?: "" }} $it" } }
                    ?: str(o["at"])?.let { "daily $it" }
                val normalised = schedule?.let { s -> normaliseSchedule(s) }
                if (normalised == null) issues += Issue("Time trigger needs a valid schedule (got '${schedule ?: ""}')", true)
                listOf("spec", "cron", "interval_minutes", "minutes", "interval", "time", "days", "at").forEach { o.remove(it) }
                o["schedule"] = JsonPrimitive(normalised ?: schedule ?: "")
            }
            "battery_level" -> {
                if (o["direction"] == null) {
                    when {
                        o["below"] != null -> { o["percent"] = o.remove("below")!!; o["direction"] = JsonPrimitive("below") }
                        o["above"] != null -> { o["percent"] = o.remove("above")!!; o["direction"] = JsonPrimitive("above") }
                        rawType.endsWith("above") -> o["direction"] = JsonPrimitive("above")
                        else -> o["direction"] = JsonPrimitive("below")
                    }
                }
                rename(o, "percent", "level", "threshold", "value")
                o["direction"] = JsonPrimitive(if (str(o["direction"])?.lowercase()?.startsWith("a") == true || str(o["direction"]) == ">" ) "above" else "below")
                o["percent"] = JsonPrimitive((percent(o["percent"]) ?: 20).coerceIn(1, 100))
            }
            "wifi_connected", "wifi_disconnected" -> { rename(o, "ssid", "network", "name", "wifi"); blankToNull(o, "ssid") }
            "bluetooth_connected", "bluetooth_disconnected" -> { rename(o, "device", "name", "address", "device_name", "mac"); blankToNull(o, "device") }
            "airplane_mode", "dnd_changed" -> {
                rename(o, "on", "enabled", "state", "value")
                o["on"]?.let { v -> o["on"] = bool(v)?.let(::JsonPrimitive) ?: JsonNull }
            }
            "location" -> {
                rename(o, "latitude", "lat")
                rename(o, "longitude", "lng", "lon", "long")
                rename(o, "radius_m", "radius", "radius_meters", "meters")
                if (o["transition"] == null) o["transition"] = JsonPrimitive(if (rawType == "leave" || rawType == "location_leave") "leave" else "enter")
                o["transition"] = JsonPrimitive(if (str(o["transition"])?.lowercase()?.let { it.startsWith("l") || it.startsWith("ex") } == true) "leave" else "enter")
                rename(o, "label", "place", "name")
                o["radius_m"] = JsonPrimitive((num(o["radius_m"])?.toInt() ?: 200).coerceIn(50, 50_000))
            }
            "notification" -> {
                rename(o, "app", "package", "app_name", "package_name", "from")
                rename(o, "contains", "text", "keyword", "matches", "match", "filter")
                blankToNull(o, "app"); blankToNull(o, "contains")
            }
            "app_opened" -> rename(o, "app", "package", "app_name", "package_name", "name")
        }
        return JsonObject(o)
    }

    private fun repairCondition(src: JsonObject, issues: MutableList<Issue>): JsonObject {
        val o = src.toMutableMap()
        val rawType = key(str(o["type"]) ?: "")
        when (type(o, CONDITION_ALIASES)) {
            "time_window" -> {
                rename(o, "start", "from", "after", "begin")
                rename(o, "end", "to", "until", "before")
                if (rawType == "after" && o["end"] == null) o["end"] = JsonPrimitive("23:59")
                if (rawType == "before" && o["start"] == null) o["start"] = JsonPrimitive("00:00")
                for (k in listOf("start", "end")) {
                    val t = str(o[k])?.let { runCatching { ScheduleText.clock(ScheduleText.timeOfDay(it)) }.getOrNull() }
                    if (t == null) issues += Issue("Time window needs valid start and end times", true)
                    o[k] = JsonPrimitive(t ?: "00:00")
                }
            }
            "days" -> {
                rename(o, "days", "value", "on")
                val tokens = when (val d = o["days"]) {
                    is JsonArray -> d.mapNotNull { str(it) }.joinToString(",")
                    else -> str(d) ?: rawType.takeIf { it == "weekdays" } ?: ""
                }
                val days = runCatching { ScheduleText.days(tokens) }.getOrNull()
                if (days == null) issues += Issue("Day condition needs day names", true)
                o["days"] = JsonArray(java.time.DayOfWeek.entries.filter { days?.contains(it) == true }.map { JsonPrimitive(ScheduleText.dayShort(it).lowercase()) })
            }
            "battery" -> {
                rename(o, "min", "above", "at_least", "from")
                rename(o, "max", "below", "at_most", "to")
                percent(o["min"])?.let { o["min"] = JsonPrimitive(it.coerceIn(0, 100)) } ?: o.remove("min")
                percent(o["max"])?.let { o["max"] = JsonPrimitive(it.coerceIn(0, 100)) } ?: o.remove("max")
            }
            "charging" -> { rename(o, "charging", "value", "state", "is_charging"); o["charging"] = JsonPrimitive(o["charging"]?.let(::bool) ?: true) }
            "wifi_ssid" -> { rename(o, "ssid", "network", "name"); o["connected"] = JsonPrimitive(o["connected"]?.let(::bool) ?: true) }
            "rooted" -> { rename(o, "rooted", "value"); o["rooted"] = JsonPrimitive(o["rooted"]?.let(::bool) ?: true) }
            else -> issues += Issue("Unknown condition '${str(o["type"])}'", true)
        }
        return JsonObject(o)
    }

    private fun repairAction(src: JsonObject, issues: MutableList<Issue>): JsonObject {
        val o = src.toMutableMap()
        if (o["type"] == null && (o["tool"] != null || o["tool_name"] != null)) o["type"] = JsonPrimitive("tool")
        if (o["type"] == null && o["prompt"] != null) o["type"] = JsonPrimitive("agent")
        when (type(o, ACTION_ALIASES)) {
            "agent" -> {
                rename(o, "prompt", "text", "instruction", "instructions", "task", "message", "query")
                rename(o, "wait_for", "waitFor", "wait_until", "run_when", "defer_until")
                val w = str(o["wait_for"])?.lowercase().orEmpty()
                o["wait_for"] = JsonPrimitive(when { "charg" in w -> "charging"; "idle" in w -> "idle"; else -> "none" })
                val caps = o["capabilities"]
                if (caps is JsonArray) o["capabilities"] = JsonArray(caps.mapNotNull { str(it)?.let(Capability::fromTag)?.tag?.let(::JsonPrimitive) })
                else o.remove("capabilities")
            }
            "tool" -> {
                rename(o, "tool", "name", "tool_name", "function")
                rename(o, "args", "arguments", "params", "parameters", "input")
                o["args"] = when (val a = o["args"]) {
                    is JsonObject -> a
                    is JsonPrimitive -> runCatching { lenient.parseToJsonElement(a.content).jsonObject }.getOrElse { JsonObject(emptyMap()) }
                    else -> JsonObject(emptyMap())
                }
            }
            "notify" -> {
                rename(o, "title", "heading", "subject")
                rename(o, "text", "message", "body", "content")
                if (o["title"] == null) o["title"] = JsonPrimitive("Atom")
            }
            "wait" -> {
                rename(o, "seconds", "duration", "secs", "time", "delay", "value")
                val s = o["seconds"]?.let { v -> num(v)?.toInt() ?: str(v)?.let(::seconds) }
                if (s == null) issues += Issue("Wait needs a number of seconds", true)
                o["seconds"] = JsonPrimitive((s ?: 1).coerceIn(1, MAX_WAIT_SECONDS))
            }
            "stop_unless" -> {
                val c = o["condition"] ?: o["if"] ?: JsonObject(o.filterKeys { it != "type" })
                o.keys.retainAll(setOf("type"))
                o["condition"] = repairCondition(asTyped(c), issues)
            }
            else -> issues += Issue("Unknown action '${str(o["type"])}'", true)
        }
        return JsonObject(o)
    }

    private fun blankToNull(o: MutableMap<String, JsonElement>, k: String) {
        val s = str(o[k])
        if (s == null || s.isBlank() || s == "any" || s == "*") o.remove(k)
    }

    fun normaliseSchedule(text: String): String? {
        val s = ScheduleText.parseOrNull(text) ?: ScheduleText.parseOrNull("daily $text")
            ?: Cron.parseOrNull(text)?.let { atom.automation.schedule.Schedule.CronSchedule(it.expression) }
        return s?.let { ScheduleText.encode(it) }
    }

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun num(e: JsonElement?): Double? = (e as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.trim().toDoubleOrNull() }
    private fun bool(e: JsonElement): Boolean? = (e as? JsonPrimitive)?.let {
        it.booleanOrNull ?: when (it.content.trim().lowercase()) {
            "on", "yes", "enabled", "true", "1" -> true
            "off", "no", "disabled", "false", "0" -> false
            else -> null
        }
    }
    private fun percent(e: JsonElement?): Int? = (e as? JsonPrimitive)?.let { it.intOrNull ?: it.content.trim().removeSuffix("%").trim().toDoubleOrNull()?.toInt() }
    private fun seconds(text: String): Int? {
        val t = text.trim().lowercase()
        Regex("^(\\d+)\\s*(s|sec|secs|second|seconds)?$").find(t)?.let { return it.groupValues[1].toInt() }
        return runCatching { ScheduleText.durationMinutes(t) * 60 }.getOrNull()
    }

    // ------------------------------------------------------------------ tools

    /** Maps near-miss tool names onto real ones; anything unrecognisable becomes an agent step. */
    private fun fixTools(rule: Rule, tools: List<ToolSpec>?, issues: MutableList<Issue>): Rule {
        if (tools.isNullOrEmpty()) return rule
        val names = tools.map { it.name }.toSet()
        val actions = rule.actions.map { a ->
            if (a !is Action.Tool || a.tool in names) return@map a
            val guess = closestTool(a.tool, names)
            if (guess != null) {
                issues += Issue("Tool '${a.tool}' does not exist; using '$guess'", false)
                a.copy(tool = guess)
            } else {
                issues += Issue("No tool named '${a.tool}'; it will be done by the agent instead", false)
                Action.Agent("Use your tools to do this: ${a.tool.replace('_', ' ')} with ${a.args}", capabilities = setOf(Capability.DeviceControl, Capability.DeviceRead))
            }
        }
        return rule.copy(actions = actions)
    }

    fun closestTool(name: String, names: Set<String>): String? {
        val n = key(name)
        names.firstOrNull { it.equals(n, true) }?.let { return it }
        names.firstOrNull { it.contains(n) || n.contains(it) }?.let { return it }
        val best = names.minByOrNull { distance(n, it) } ?: return null
        return best.takeIf { distance(n, it) <= maxOf(2, n.length / 4) }
    }

    private fun distance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    // ------------------------------------------------------------------ validation

    fun validate(rule: Rule, tools: List<ToolSpec>?): List<Issue> {
        val out = mutableListOf<Issue>()
        if (rule.name.isBlank()) out += Issue("Give the automation a name", true)
        if (rule.actions.isEmpty()) out += Issue("Add at least one action", true)
        when (val t = rule.trigger) {
            is Trigger.Time -> if (ScheduleText.parseOrNull(t.schedule) == null) out += Issue("Invalid schedule '${t.schedule}'", true)
            is Trigger.Location -> {
                if (t.latitude !in -90.0..90.0 || t.longitude !in -180.0..180.0) out += Issue("Location coordinates are out of range", true)
                if (t.latitude == 0.0 && t.longitude == 0.0) out += Issue("Set the place on the location trigger", true)
            }
            is Trigger.AppOpened -> if (t.app.isBlank()) out += Issue("Pick which app opening triggers this", true)
            is Trigger.BatteryLevel -> if (t.percent !in 1..100) out += Issue("Battery level must be 1-100%", true)
            else -> Unit
        }
        rule.actions.forEachIndexed { i, a ->
            val n = i + 1
            when (a) {
                is Action.Agent -> if (a.prompt.isBlank()) out += Issue("Step $n: the agent needs a prompt", true)
                is Action.Notify -> if (a.title.isBlank() && a.text.isBlank()) out += Issue("Step $n: the notification is empty", true)
                is Action.Wait -> if (a.seconds !in 1..MAX_WAIT_SECONDS) out += Issue("Step $n: wait 1-$MAX_WAIT_SECONDS seconds", true)
                is Action.Tool -> {
                    val spec = tools?.firstOrNull { it.name == a.tool }
                    if (a.tool.isBlank()) out += Issue("Step $n: pick a tool", true)
                    else if (tools != null && tools.isNotEmpty() && spec == null) out += Issue("Step $n: unknown tool '${a.tool}'", true)
                    spec?.let { s ->
                        val required = (s.parameters["required"] as? JsonArray)?.mapNotNull { str(it) }.orEmpty()
                        val missing = required.filter { it !in a.args }
                        if (missing.isNotEmpty()) out += Issue("Step $n: ${a.tool} needs ${missing.joinToString()}", true)
                    }
                }
                is Action.StopUnless -> Unit
            }
        }
        return out
    }
}
