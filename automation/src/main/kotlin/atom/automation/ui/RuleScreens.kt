package atom.automation.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import atom.automation.Automations
import atom.automation.rule.Action
import atom.automation.rule.BatteryEstimator
import atom.automation.rule.CompileResult
import atom.automation.rule.Condition
import atom.automation.rule.Conditions
import atom.automation.rule.ConflictDetector
import atom.automation.rule.DryLine
import atom.automation.rule.Rule
import atom.automation.rule.RuleJson
import atom.automation.rule.Trigger
import atom.automation.rule.Triggers
import atom.automation.rule.describe
import atom.automation.schedule.ScheduleText
import atom.automation.system.Health
import atom.ui.components.AtomCard
import atom.ui.components.AtomSwitch
import atom.ui.components.BottomActionBar
import atom.ui.components.CircleIconButton
import atom.ui.components.CompactButton
import atom.ui.components.Group
import atom.ui.components.GroupDivider
import atom.ui.components.GroupRow
import atom.ui.components.LargeTitleScreen
import atom.ui.components.Pill
import atom.ui.components.PrimaryButton
import atom.ui.components.SectionLabel
import atom.ui.feature.FeatureNav
import atom.ui.theme.extras
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

internal const val R_RULES = "automation/rules"
internal const val R_RULE = "automation/rule/"

/** The compiled-but-unsaved rule handed from the list to the review screen. */
internal object Drafts {
    @Volatile var result: CompileResult? = null
}

private val EXAMPLES = listOf(
    "Whenever I plug in my charger after 11 PM, turn on Do Not Disturb and summarise today's important notifications.",
    "When I connect to my car's Bluetooth, set media volume to 70% and open Maps.",
    "When battery drops to 15%, turn on battery saver and notify me.",
)

@Composable
internal fun RulesScreen(nav: FeatureNav) {
    val rt = rememberRuntime()
    val snap by rt.state.flow.collectAsState()
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val conflicts = remember(snap.rules) { ConflictDetector.find(snap.rules, ZoneId.systemDefault()) }
    fun create() {
        if (text.isBlank() || busy) return
        busy = true
        error = null
        scope.launch {
            val result = runCatching { Automations.compiler.compile(text.trim()) }.getOrElse { CompileResult(null, errors = listOf(it.message ?: "Failed")) }
            busy = false
            if (result.rule != null) {
                Drafts.result = result
                text = ""
                nav.open(R_RULE + "draft")
            } else {
                error = result.errors.joinToString("\n")
            }
        }
    }
    LargeTitleScreen(
        "Automations",
        onBack = nav::back,
        actions = { CircleIconButton(Icons.Filled.Add, "Build by hand", { nav.open(R_RULE + "new") }, Modifier.padding(end = 8.dp)) },
    ) {
        item {
            Group {
                AtomField(text, { text = it }, "Whenever I… do this", singleLine = false, minLines = 3)
                Row(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CompactButton(if (busy) "Compiling…" else "Create", ::create, enabled = text.isNotBlank() && !busy)
                    Text("Atom turns it into a rule you can check and edit. The model is used only now.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        error?.let { e ->
            item { Note(e, warning = true) }
            if ("model" in e.lowercase()) item { Row(Modifier.padding(horizontal = 14.dp)) { CompactButton("Set up a model", nav::openModels, primary = false) } }
        }
        if (snap.rules.isEmpty()) {
            item { SectionLabel("Try") }
            item {
                Group {
                    EXAMPLES.forEachIndexed { i, ex ->
                        if (i > 0) GroupDivider(18.dp)
                        GroupRow(ex, onClick = { text = ex })
                    }
                }
            }
        } else {
            if (conflicts.isNotEmpty()) {
                item { SectionLabel("Conflicts") }
                item {
                    Group {
                        conflicts.forEachIndexed { i, c ->
                            if (i > 0) GroupDivider(18.dp)
                            GroupRow(c.message { id -> snap.rules.firstOrNull { it.id == id }?.name ?: "?" }, titleColor = MaterialTheme.extras.warning)
                        }
                    }
                }
            }
            item { SectionLabel("${snap.rules.count { it.enabled }}/${snap.rules.size} on") }
            item {
                Group {
                    snap.rules.forEachIndexed { i, r ->
                        if (i > 0) GroupDivider(18.dp)
                        val cost = BatteryEstimator.estimate(r)
                        val last = snap.ruleRuns.filter { it.ruleId == r.id }.maxByOrNull { it.at }
                        GroupRow(
                            r.name,
                            subtitle = Triggers.describe(r.trigger) + " · " + r.actions.size + (if (r.actions.size == 1) " step" else " steps") + " · " + cost.level.label +
                                (last?.let { " · Last: ${it.outcome.label}" } ?: ""),
                            onClick = { nav.open(R_RULE + r.id) },
                            trailing = { AtomSwitch(r.enabled, { rt.rules.setEnabled(r.id, it) }) },
                        )
                    }
                }
            }
        }
    }
}

private val BLANK = Rule(name = "", trigger = Trigger.ChargerConnected, actions = emptyList())

private enum class Editing { Trigger, Condition, Action }

@Composable
internal fun RuleEditorScreen(id: String, nav: FeatureNav) {
    val context = LocalContext.current
    val rt = rememberRuntime()
    val snap by rt.state.flow.collectAsState()
    val draft = id == "draft" || id == "new"
    val compiled = remember { if (id == "draft") Drafts.result else null }
    val saved = snap.rules.firstOrNull { it.id == id }
    var rule by remember { mutableStateOf(saved ?: compiled?.rule ?: BLANK) }
    var editing by remember { mutableStateOf<Pair<Editing, Int>?>(null) }
    var dry by remember { mutableStateOf<List<DryLine>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var dirty by remember { mutableIntStateOf(0) }
    if (!draft && saved == null) {
        LargeTitleScreen("Automation", onBack = nav::back) { item { AtomCard { Text("This automation was deleted.") } } }
        return
    }
    val tools = remember { Automations.toolInvoker?.available() }
    val issues = RuleJson.validate(rule, tools)
    val blocking = issues.filter { it.fatal }
    val cost = BatteryEstimator.estimate(rule)
    val conflicts = remember(rule, snap.rules) {
        val probe = rule.copy(id = rule.id.ifEmpty { "draft" }, enabled = true)
        ConflictDetector.conflictsFor(probe.id, ConflictDetector.find(snap.rules.filter { it.id != probe.id } + probe, ZoneId.systemDefault()))
    }
    val history = snap.ruleRuns.filter { it.ruleId == rule.id }.sortedByDescending { it.at }
    fun edit(change: (Rule) -> Rule) {
        rule = change(rule)
        dry = null
        dirty++
    }

    LargeTitleScreen(
        if (draft) "Review automation" else rule.name.ifBlank { "Automation" },
        onBack = nav::back,
        bottomBar = {
            BottomActionBar {
                PrimaryButton(if (draft) "Save and turn on" else "Save", {
                    val toSave = rule.copy(enabled = if (draft) true else rule.enabled)
                    rt.rules.save(toSave)
                    if (toSave.enabled && toSave.actions.any { it is Action.Notify || it is Action.Agent }) context.askNotificationsForResults()
                    if (id == "draft") Drafts.result = null
                    nav.back()
                }, enabled = blocking.isEmpty() && (draft || dirty > 0))
            }
        },
    ) {
        item { Group { AtomField(rule.name, { v -> edit { it.copy(name = v) } }, "Name") } }
        if (rule.sentence.isNotBlank()) item { Note("From: \"${rule.sentence}\"") }
        compiled?.warnings?.forEach { w -> item { Note(w) } }
        item { SectionLabel("When") }
        item { Group { NavRow(Triggers.describe(rule.trigger)) { editing = Editing.Trigger to 0 } } }
        item { SectionLabel("Only if") }
        item {
            Group {
                rule.conditions.forEachIndexed { i, c ->
                    GroupRow(Conditions.describe(c), onClick = { editing = Editing.Condition to i }, trailing = {
                        SmallIcon(Icons.Filled.Close, "Remove") { edit { r -> r.copy(conditions = r.conditions.filterIndexed { j, _ -> j != i }) } }
                    })
                    GroupDivider(18.dp)
                }
                GroupRow("Add condition", icon = Icons.Filled.Add, onClick = { editing = Editing.Condition to -1 })
            }
        }
        item { SectionLabel("Do, in order") }
        item {
            Group {
                rule.actions.forEachIndexed { i, a ->
                    GroupRow("${i + 1}. " + a.describe(), onClick = { editing = Editing.Action to i }, trailing = {
                        Row {
                            if (i > 0) SmallIcon(Icons.Filled.KeyboardArrowUp, "Move up") { edit { r -> r.copy(actions = r.actions.swap(i, i - 1)) } }
                            if (i < rule.actions.lastIndex) SmallIcon(Icons.Filled.KeyboardArrowDown, "Move down") { edit { r -> r.copy(actions = r.actions.swap(i, i + 1)) } }
                            SmallIcon(Icons.Filled.Close, "Remove") { edit { r -> r.copy(actions = r.actions.filterIndexed { j, _ -> j != i }) } }
                        }
                    })
                    GroupDivider(18.dp)
                }
                GroupRow("Add step", icon = Icons.Filled.Add, onClick = { editing = Editing.Action to -1 })
            }
        }
        issues.forEach { item { Note(it.message, warning = it.fatal) } }
        item { SectionLabel("Battery") }
        item { Group { GroupRow(cost.level.label, subtitle = cost.reason) } }
        if (conflicts.isNotEmpty()) {
            item { SectionLabel("Conflicts") }
            item {
                Group {
                    conflicts.forEachIndexed { i, c ->
                        if (i > 0) GroupDivider(18.dp)
                        GroupRow(c.message { cid -> if (cid == "draft" || cid == rule.id) "This automation" else snap.rules.firstOrNull { it.id == cid }?.name ?: "?" }, titleColor = MaterialTheme.extras.warning)
                    }
                }
            }
        }
        item { Requirements(rule) }
        item { Gap(8) }
        item {
            Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CompactButton("Test run", { dry = rt.rules.dryRun(rule) }, primary = false)
                if (!draft) CompactButton(if (rule.enabled) "Turn off" else "Turn on", {
                    rt.rules.setEnabled(rule.id, !rule.enabled)
                    rule = rule.copy(enabled = !rule.enabled)
                }, primary = false)
            }
        }
        dry?.let { lines ->
            item { SectionLabel("If it fired now (nothing was changed)") }
            item {
                Group {
                    lines.forEachIndexed { i, l ->
                        if (i > 0) GroupDivider(18.dp)
                        GroupRow(l.text, trailing = l.ok?.let { ok -> { Pill(if (ok) "Pass" else "Fail", MaterialTheme.colorScheme.surfaceContainerHighest) } })
                    }
                }
            }
        }
        if (!draft) {
            item { SectionLabel("History") }
            item {
                Group {
                    if (history.isEmpty()) GroupRow("Has not fired yet")
                    val now = Instant.now()
                    history.forEachIndexed { i, h ->
                        if (i > 0) GroupDivider(18.dp)
                        GroupRow(
                            h.outcome.label + " · " + ScheduleText.relative(Instant.ofEpochMilli(h.at), now),
                            subtitle = (listOf(h.event) + h.steps).joinToString("\n").take(600),
                        )
                    }
                }
            }
            item { Gap(20) }
            item { Group { GroupRow("Delete automation", onClick = { confirmDelete = true }) } }
        }
    }

    editing?.let { (what, index) ->
        when (what) {
            Editing.Trigger -> ItemDialog("When", Specs.triggers, RuleJson.encodeTrigger(rule.trigger), { RuleJson.json.decodeFromJsonElement(Trigger.serializer(), it) }, { editing = null }) { obj ->
                edit { it.copy(trigger = RuleJson.json.decodeFromJsonElement(Trigger.serializer(), obj)) }
                editing = null
            }
            Editing.Condition -> ItemDialog(
                "Only if", Specs.conditions,
                rule.conditions.getOrNull(index)?.let(RuleJson::encodeCondition) ?: Specs.fresh(Specs.conditions[0]),
                { RuleJson.json.decodeFromJsonElement(Condition.serializer(), it) }, { editing = null },
            ) { obj ->
                val c = RuleJson.json.decodeFromJsonElement(Condition.serializer(), obj)
                edit { r -> r.copy(conditions = if (index < 0) r.conditions + c else r.conditions.mapIndexed { j, o -> if (j == index) c else o }) }
                editing = null
            }
            Editing.Action -> ItemDialog(
                "Step", Specs.actions,
                rule.actions.getOrNull(index)?.let(RuleJson::encodeAction) ?: Specs.fresh(Specs.actions[0]),
                { RuleJson.json.decodeFromJsonElement(Action.serializer(), it) }, { editing = null },
            ) { obj ->
                val a = RuleJson.json.decodeFromJsonElement(Action.serializer(), obj)
                edit { r -> r.copy(actions = if (index < 0) r.actions + a else r.actions.mapIndexed { j, o -> if (j == index) a else o }) }
                editing = null
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog("Delete automation?", "\"${rule.name}\" and its history will be removed.", "Delete", { confirmDelete = false }) {
            confirmDelete = false
            rt.rules.delete(rule.id)
            nav.back()
        }
    }
}

private fun <T> List<T>.swap(a: Int, b: Int): List<T> = toMutableList().also { val t = it[a]; it[a] = it[b]; it[b] = t }

@Composable
private fun SmallIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick, Modifier.size(36.dp)) { Icon(icon, description, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
}

private data class Need(val title: String, val ok: Boolean, val detail: String, val fix: (Context) -> Unit)

@Composable
private fun Requirements(rule: Rule) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val resumed = rememberOnResume { System.nanoTime() }
    val needs = remember(rule, tick, resumed) { needsOf(context, rule, permissions::launch) }
    if (needs.isEmpty()) return
    androidx.compose.foundation.layout.Column {
        SectionLabel("Needs")
        Group {
            needs.forEachIndexed { i, n ->
                if (i > 0) GroupDivider(18.dp)
                GroupRow(n.title, subtitle = n.detail, trailing = { if (n.ok) HealthDot(true) else CompactButton("Allow", { n.fix(context) }) })
            }
        }
    }
}

private fun needsOf(context: Context, rule: Rule, request: (Array<String>) -> Unit): List<Need> {
    val out = mutableListOf<Need>()
    val t = rule.trigger
    val wantsSsid = (t is Trigger.WifiConnected && t.ssid != null) || (t is Trigger.WifiDisconnected && t.ssid != null) ||
        rule.conditions.any { it is Condition.WifiSsid } || rule.actions.any { it is Action.StopUnless && it.condition is Condition.WifiSsid }
    if (t is Trigger.Location || wantsSsid) {
        out += Need("Location", Health.location(context), if (t is Trigger.Location) "Approximate location is good to about 2 km; allow precise for small places" else "Android hides the Wi-Fi name without it") {
            request(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    if (t is Trigger.Location) {
        out += Need("Location all the time", Health.backgroundLocation(context), "So places work while Atom is closed") {
            if (!Health.location(it)) request(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION))
            else request(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        }
    }
    if ((t is Trigger.BluetoothConnected || t is Trigger.BluetoothDisconnected) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        out += Need("Nearby devices", Health.bluetooth(context), "To see which Bluetooth device connected") {
            request(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
        }
    }
    if (t is Trigger.Notification) {
        out += Need("Notification access", Health.notificationAccess(context), "Atom's notification listener feeds this trigger") {
            it.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }
    if (t is Trigger.AppOpened) {
        out += Need("Accessibility service", Health.accessibility(context), "Atom Agent reports which app is in front") {
            it.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }
    if (t is Trigger.Time) out += Health.exactAlarms(context).let { h -> Need(h.title, h.ok, h.detail) { c -> c.launch(h.fix) } }
    if (rule.actions.any { it is Action.Agent } || atom.automation.system.TriggerService.needsService(t)) {
        // The one-tap battery dialog is only offered from Scheduled tasks' health banner; here it opens the list.
        out += Health.battery(context).let { h ->
            Need(h.title, h.ok, "Lets Atom start work from the background") { c -> c.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }
    if (rule.actions.any { it is Action.Notify }) out += Health.notifications(context).let { h -> Need(h.title, h.ok, h.detail) { c -> c.launch(h.fix) } }
    return out
}
