package atom.automation.ui

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import atom.automation.Automations
import atom.automation.Capability
import atom.automation.rule.Conditions
import atom.automation.rule.RuleJson
import atom.automation.schedule.ScheduleText
import atom.automation.system.Health
import atom.ui.components.AtomMenu
import atom.ui.components.AtomMenuItem
import atom.ui.components.AtomSwitch
import atom.ui.components.CompactButton
import atom.ui.components.Group
import atom.ui.components.GroupDivider
import atom.ui.components.GroupRow
import atom.ui.components.RowValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

internal enum class FieldKind { Text, OptText, Int, Decimal, TriBool, Bool, Direction, Transition, WaitFor, Time, Days, Schedule, Json, Caps, ToolName, Condition }

internal data class FieldSpec(val key: String, val label: String, val kind: FieldKind)

internal data class TypeSpec(val type: String, val label: String, val fields: List<FieldSpec> = emptyList(), val defaults: Map<String, JsonElement> = emptyMap())

private fun p(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is Boolean -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is String -> JsonPrimitive(v)
    is List<*> -> JsonArray(v.map { p(it) })
    is Map<*, *> -> JsonObject(v.entries.associate { it.key.toString() to p(it.value) })
    else -> JsonPrimitive(v.toString())
}

internal object Specs {
    val triggers = listOf(
        TypeSpec("charger_connected", "Charger connected"),
        TypeSpec("charger_disconnected", "Charger disconnected"),
        TypeSpec("battery_level", "Battery level", listOf(FieldSpec("direction", "Direction", FieldKind.Direction), FieldSpec("percent", "Percent", FieldKind.Int)), mapOf("direction" to p("below"), "percent" to p(20))),
        TypeSpec("screen_on", "Screen on"),
        TypeSpec("screen_off", "Screen off"),
        TypeSpec("unlocked", "Phone unlocked"),
        TypeSpec("schedule", "Time or schedule", listOf(FieldSpec("schedule", "Schedule", FieldKind.Schedule)), mapOf("schedule" to p("daily 08:00"))),
        TypeSpec("wifi_connected", "Wi-Fi connected", listOf(FieldSpec("ssid", "Network name (empty = any)", FieldKind.OptText))),
        TypeSpec("wifi_disconnected", "Wi-Fi disconnected", listOf(FieldSpec("ssid", "Network name (empty = any)", FieldKind.OptText))),
        TypeSpec("bluetooth_connected", "Bluetooth connected", listOf(FieldSpec("device", "Device name (empty = any)", FieldKind.OptText))),
        TypeSpec("bluetooth_disconnected", "Bluetooth disconnected", listOf(FieldSpec("device", "Device name (empty = any)", FieldKind.OptText))),
        TypeSpec("headset_plugged", "Headset plugged in"),
        TypeSpec("headset_unplugged", "Headset unplugged"),
        TypeSpec("airplane_mode", "Airplane mode changed", listOf(FieldSpec("on", "Turns", FieldKind.TriBool))),
        TypeSpec("dnd_changed", "Do Not Disturb changed", listOf(FieldSpec("on", "Turns", FieldKind.TriBool))),
        TypeSpec(
            "location", "Arrive or leave a place",
            listOf(
                FieldSpec("transition", "When", FieldKind.Transition), FieldSpec("label", "Place name", FieldKind.OptText),
                FieldSpec("latitude", "Latitude", FieldKind.Decimal), FieldSpec("longitude", "Longitude", FieldKind.Decimal),
                FieldSpec("radius_m", "Radius (m)", FieldKind.Int),
            ),
            mapOf("transition" to p("enter"), "latitude" to p(0.0), "longitude" to p(0.0), "radius_m" to p(500)),
        ),
        TypeSpec("notification", "Notification received", listOf(FieldSpec("app", "From app (empty = any)", FieldKind.OptText), FieldSpec("contains", "Containing text (optional)", FieldKind.OptText))),
        TypeSpec("app_opened", "App opened", listOf(FieldSpec("app", "App name or package", FieldKind.Text)), mapOf("app" to p(""))),
        TypeSpec("boot_completed", "Phone starts"),
    )

    val conditions = listOf(
        TypeSpec("time_window", "Time window", listOf(FieldSpec("start", "From", FieldKind.Time), FieldSpec("end", "Until", FieldKind.Time)), mapOf("start" to p("23:00"), "end" to p("07:00"))),
        TypeSpec("days", "Days of week", listOf(FieldSpec("days", "Days", FieldKind.Days)), mapOf("days" to p(listOf("mon", "tue", "wed", "thu", "fri")))),
        TypeSpec("battery", "Battery range", listOf(FieldSpec("min", "At least %", FieldKind.Int), FieldSpec("max", "At most %", FieldKind.Int)), mapOf("min" to p(0), "max" to p(100))),
        TypeSpec("charging", "Charging state", listOf(FieldSpec("charging", "Charging", FieldKind.Bool)), mapOf("charging" to p(true))),
        TypeSpec("wifi_ssid", "Wi-Fi network", listOf(FieldSpec("ssid", "Network name", FieldKind.Text), FieldSpec("connected", "Connected to it", FieldKind.Bool)), mapOf("ssid" to p(""), "connected" to p(true))),
        TypeSpec("rooted", "Root", listOf(FieldSpec("rooted", "Phone is rooted", FieldKind.Bool)), mapOf("rooted" to p(true))),
    )

    val actions = listOf(
        TypeSpec("tool", "Device or app tool", listOf(FieldSpec("tool", "Tool", FieldKind.ToolName), FieldSpec("args", "Arguments (JSON)", FieldKind.Json)), mapOf("tool" to p(""), "args" to JsonObject(emptyMap()))),
        TypeSpec(
            "agent", "Ask Atom",
            listOf(FieldSpec("prompt", "What should Atom do", FieldKind.Text), FieldSpec("wait_for", "Run", FieldKind.WaitFor), FieldSpec("capabilities", "Atom may use", FieldKind.Caps)),
            mapOf("prompt" to p(""), "wait_for" to p("none"), "capabilities" to p(listOf("web", "device_read"))),
        ),
        TypeSpec("notify", "Post a notification", listOf(FieldSpec("title", "Title", FieldKind.Text), FieldSpec("text", "Text", FieldKind.OptText)), mapOf("title" to p(""), "text" to p(""))),
        TypeSpec("wait", "Wait", listOf(FieldSpec("seconds", "Seconds (1-600)", FieldKind.Int)), mapOf("seconds" to p(10))),
        TypeSpec("stop_unless", "Stop unless", listOf(FieldSpec("condition", "Continue only if", FieldKind.Condition)), mapOf("condition" to JsonObject(mapOf("type" to p("charging"), "charging" to p(true))))),
    )

    fun of(list: List<TypeSpec>, type: String) = list.firstOrNull { it.type == type } ?: list.first()

    fun fresh(spec: TypeSpec) = JsonObject(mapOf("type" to JsonPrimitive(spec.type)) + spec.defaults)
}

private fun JsonElement?.str(): String = (this as? JsonPrimitive)?.contentOrNull ?: ""

/** Edits one trigger, condition or action as canonical JSON; [decode] validates before it is accepted. */
@Composable
internal fun ItemDialog(
    title: String,
    specs: List<TypeSpec>,
    initial: JsonObject,
    decode: (JsonObject) -> Unit,
    onDismiss: () -> Unit,
    onDone: (JsonObject) -> Unit,
) {
    var type by remember { mutableStateOf(initial["type"].str().ifEmpty { specs.first().type }) }
    val values = remember { mutableStateMapOf<String, JsonElement>().apply { putAll(initial) } }
    val collectors = remember { mutableMapOf<String, () -> JsonElement>() }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Group {
                    ChoiceRow("Type", type, specs.map { it.type }, { t -> Specs.of(specs, t).label }, { t ->
                        if (t != type) {
                            type = t
                            collectors.clear()
                            values.clear()
                            values.putAll(Specs.fresh(Specs.of(specs, t)))
                        }
                    })
                    Fields(Specs.of(specs, type), values, collectors)
                }
                if (type == "location") CurrentLocationButton(values)
                error?.let { Note(it, warning = true) }
            }
        },
        confirmButton = {
            TextButton({
                collectors.forEach { (k, collect) -> values[k] = collect() }
                val obj = JsonObject(values.toMap() + ("type" to JsonPrimitive(type)))
                val problem = runCatching { decode(obj) }.exceptionOrNull()
                if (problem == null) onDone(obj) else error = problem.message?.lineSequence()?.firstOrNull() ?: "Check the fields"
            }) { Text("Done", color = MaterialTheme.colorScheme.onSurface) }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) } },
    )
}

@Composable
private fun Fields(spec: TypeSpec, values: SnapshotStateMap<String, JsonElement>, collectors: MutableMap<String, () -> JsonElement>) {
    spec.fields.forEach { f ->
        GroupDivider(18.dp)
        Field(f, values, collectors)
    }
}

@Composable
private fun Field(f: FieldSpec, values: SnapshotStateMap<String, JsonElement>, collectors: MutableMap<String, () -> JsonElement>) {
    val v = values[f.key]
    fun set(e: JsonElement?) { if (e == null) values.remove(f.key) else values[f.key] = e }
    when (f.kind) {
        FieldKind.Text -> AtomField(v.str(), { set(JsonPrimitive(it)) }, f.label, singleLine = f.key != "prompt", minLines = if (f.key == "prompt") 2 else 1, label = f.label)
        FieldKind.OptText -> AtomField(v.str(), { set(if (it.isBlank()) null else JsonPrimitive(it)) }, f.label, label = f.label)
        FieldKind.Int -> {
            var text by remember(f.key) { mutableStateOf(v.str()) }
            AtomField(text, { t -> text = t.filter { it.isDigit() }; set(text.toIntOrNull()?.let(::JsonPrimitive)) }, f.label, number = true, label = f.label)
        }
        FieldKind.Decimal -> {
            var text by remember(f.key) { mutableStateOf(v.str()) }
            AtomField(text, { t -> text = t.filter { it.isDigit() || it == '.' || it == '-' }; set(text.toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(0.0)) }, f.label, label = f.label)
        }
        FieldKind.Bool -> GroupRow(f.label, trailing = { AtomSwitch((v as? JsonPrimitive)?.booleanOrNull ?: true, { set(JsonPrimitive(it)) }) })
        FieldKind.TriBool -> {
            val cur = (v as? JsonPrimitive)?.booleanOrNull
            ChoiceRow(f.label, cur, listOf(null, true, false), { when (it) { null -> "Any change"; true -> "On"; false -> "Off" } }, { set(it?.let(::JsonPrimitive)) })
        }
        FieldKind.Direction -> ChoiceRow(f.label, v.str().ifEmpty { "below" }, listOf("below", "above"), { if (it == "below") "Drops to" else "Rises to" }, { set(JsonPrimitive(it)) })
        FieldKind.Transition -> ChoiceRow(f.label, v.str().ifEmpty { "enter" }, listOf("enter", "leave"), { if (it == "enter") "Arriving" else "Leaving" }, { set(JsonPrimitive(it)) })
        FieldKind.WaitFor -> ChoiceRow(f.label, v.str().ifEmpty { "none" }, listOf("none", "charging", "idle"), {
            when (it) { "charging" -> "When charging"; "idle" -> "When idle"; else -> "Right away" }
        }, { set(JsonPrimitive(it)) })
        FieldKind.Time -> TimeRow(f.label, Conditions.minutes(v.str().ifEmpty { "00:00" })) { set(JsonPrimitive(ScheduleText.clock(it))) }
        FieldKind.Days -> {
            val days = Conditions.days((v as? JsonArray)?.map { it.str() }.orEmpty())
            DayPicker(days) { d -> set(JsonArray(java.time.DayOfWeek.entries.filter { it in d }.map { JsonPrimitive(ScheduleText.dayShort(it).lowercase()) })) }
        }
        FieldKind.Schedule -> {
            var text by remember(f.key) { mutableStateOf(v.str()) }
            AtomField(text, { text = it; set(JsonPrimitive(RuleJson.normaliseSchedule(it) ?: it)) }, "daily 23:00 · weekdays 8:30 · every 30m · cron 0 9 * * *", label = f.label)
            val parsed = ScheduleText.parseOrNull(v.str())
            Note(parsed?.let { ScheduleText.describe(it) } ?: "Not a schedule yet", warning = parsed == null)
        }
        FieldKind.Json -> {
            var text by remember(f.key) { mutableStateOf((v as? JsonObject)?.toString() ?: "{}") }
            var bad by remember { mutableStateOf(false) }
            AtomField(text, { t ->
                text = t
                val parsed = runCatching { RuleJson.json.parseToJsonElement(t.ifBlank { "{}" }).jsonObject }.getOrNull()
                bad = parsed == null
                if (parsed != null) set(parsed)
            }, "{\"percent\": 80}", singleLine = false, label = f.label)
            if (bad) Note("Not valid JSON yet", warning = true)
        }
        FieldKind.Caps -> {
            val caps = (v as? JsonArray)?.mapNotNull { Capability.fromTag(it.str()) }?.toSet().orEmpty()
            Column {
                Capability.entries.forEach { c ->
                    GroupRow(c.label, trailing = {
                        AtomSwitch(c in caps, { on -> set(JsonArray((if (on) caps + c else caps - c).map { JsonPrimitive(it.tag) })) })
                    })
                }
            }
        }
        FieldKind.ToolName -> ToolPicker(v.str()) { set(JsonPrimitive(it)) }
        FieldKind.Condition -> {
            val nested = remember { mutableStateMapOf<String, JsonElement>().apply { putAll((v as? JsonObject) ?: Specs.fresh(Specs.conditions[3])) } }
            remember { collectors[f.key] = { JsonObject(nested.toMap()) } }
            val nestedType = nested["type"].str().ifEmpty { "charging" }
            ChoiceRow(f.label, nestedType, Specs.conditions.map { it.type }, { t -> Specs.of(Specs.conditions, t).label }, { t ->
                nested.clear()
                nested.putAll(Specs.fresh(Specs.of(Specs.conditions, t)))
            })
            Specs.of(Specs.conditions, nestedType).fields.forEach { nf ->
                GroupDivider(18.dp)
                Field(nf, nested, collectors)
            }
        }
    }
}

@Composable
private fun ToolPicker(value: String, onPick: (String) -> Unit) {
    val tools = remember { Automations.toolInvoker?.available().orEmpty() }
    var text by remember { mutableStateOf(value) }
    var open by remember { mutableStateOf(false) }
    Box {
        AtomField(text, { text = it; onPick(it.trim()); open = tools.isNotEmpty() }, "Tool name", label = "Tool")
        val matches = tools.filter { it.name.contains(text.trim(), ignoreCase = true) || it.description.contains(text.trim(), ignoreCase = true) }.take(8)
        AtomMenu(open && matches.isNotEmpty() && text.isNotBlank(), { open = false }) {
            matches.forEach { t -> AtomMenuItem(t.name, t.name == text) { text = t.name; onPick(t.name); open = false } }
        }
    }
    val spec = tools.firstOrNull { it.name == text.trim() }
    when {
        tools.isEmpty() -> Note("Device tools are not connected in this build yet.", warning = true)
        spec == null && text.isNotBlank() -> Note("No tool with this name", warning = true)
        spec != null -> Note(spec.description.lineSequence().first().take(160))
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun CurrentLocationButton(values: SnapshotStateMap<String, JsonElement>) {
    val context = LocalContext.current
    var note by remember { mutableStateOf<String?>(null) }
    CompactButton("Use current location", {
        val loc = lastLocation(context)
        if (loc == null) {
            note = if (Health.location(context)) "No recent location; open a maps app once and try again" else "Allow location for Atom first"
        } else {
            values["latitude"] = JsonPrimitive(loc.first)
            values["longitude"] = JsonPrimitive(loc.second)
            note = "Set to %.5f, %.5f".format(loc.first, loc.second)
        }
    }, primary = false)
    note?.let { Note(it) }
}

@SuppressLint("MissingPermission")
private fun lastLocation(context: Context): Pair<Double, Double>? {
    if (!Health.location(context)) return null
    val lm = context.getSystemService(LocationManager::class.java) ?: return null
    return lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }?.let { it.latitude to it.longitude }
}

@Composable
internal fun ValueRow(title: String, value: String) = GroupRow(title, trailing = { RowValue(value) })
