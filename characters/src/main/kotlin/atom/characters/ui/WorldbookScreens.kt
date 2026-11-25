package atom.characters.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import atom.characters.R
import atom.characters.model.Placement
import atom.characters.model.SecondaryLogic
import atom.characters.model.WorldEntry
import atom.characters.model.Worldbook
import atom.characters.session.CharacterSession
import atom.characters.worldbook.EntryIssue
import atom.characters.worldbook.WorldbookMatcher
import atom.ui.components.AtomCard
import atom.ui.components.AtomMenu
import atom.ui.components.AtomMenuItem
import atom.ui.components.AtomSwitch
import atom.ui.components.CircleIconButton
import atom.ui.components.CompactButton
import atom.ui.components.Group
import atom.ui.components.GroupDivider
import atom.ui.components.GroupRow
import atom.ui.components.LargeTitleScreen
import atom.ui.components.RowValue
import atom.ui.components.SectionLabel
import atom.ui.components.SegmentedControl
import atom.ui.feature.FeatureNav
import java.util.UUID
import kotlinx.coroutines.launch

private data class BookSettings(val name: String, val scanDepth: Int?, val budget: Int?, val recursive: Boolean)

@Composable
internal fun WorldbookScreen(id: String, nav: FeatureNav) {
    val store = CharacterSession.store
    LaunchedEffect(Unit) { store.load() }
    val all by store.characters.collectAsState()
    val c = all.firstOrNull { it.id == id }
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf<BookSettings?>(null) }
    LaunchedEffect(c != null) {
        if (c != null && settings == null) {
            val b = c.worldbook ?: Worldbook()
            settings = BookSettings(b.name, b.scanDepth, b.tokenBudget, b.recursiveScanning)
        }
    }
    val latest by rememberUpdatedState(settings)
    DisposableEffect(id) {
        onDispose {
            val s = latest ?: return@onDispose
            SaveScope.scope.launch { store.update(id) { ch -> ch.copy(worldbook = applySettings(ch.worldbook ?: Worldbook(), s)) } }
        }
    }

    LargeTitleScreen(
        title = stringResource(R.string.characters_worldbook),
        onBack = nav::back,
        actions = {
            CircleIconButton(Icons.Filled.Add, stringResource(R.string.characters_wb_add), {
                scope.launch {
                    val entry = WorldEntry(id = UUID.randomUUID().toString().take(8))
                    val updated = store.update(id) { ch ->
                        val book = applySettings(ch.worldbook ?: Worldbook(), settings ?: return@update ch)
                        ch.copy(worldbook = book.copy(entries = book.entries + entry))
                    }
                    if (updated != null) nav.open(CharacterRoutes.entry(id, entry.id))
                }
            }, modifier = Modifier.padding(end = 8.dp))
        },
    ) {
        val s = settings ?: return@LargeTitleScreen
        if (c == null) return@LargeTitleScreen
        item { SectionLabel(stringResource(R.string.characters_wb_settings)) }
        item { LabeledField(stringResource(R.string.characters_wb_name), s.name, { settings = s.copy(name = it) }, singleLine = true) }
        item { NumberField(stringResource(R.string.characters_wb_scan_depth), s.scanDepth, { settings = s.copy(scanDepth = it) }, stringResource(R.string.characters_wb_scan_depth_subtitle)) }
        item { NumberField(stringResource(R.string.characters_wb_budget), s.budget, { settings = s.copy(budget = it) }, stringResource(R.string.characters_wb_budget_subtitle)) }
        item {
            Spacer(Modifier.height(14.dp))
            Group {
                GroupRow(
                    title = stringResource(R.string.characters_wb_recursive),
                    subtitle = stringResource(R.string.characters_wb_recursive_subtitle),
                    onClick = { settings = s.copy(recursive = !s.recursive) },
                    trailing = { AtomSwitch(s.recursive, { settings = s.copy(recursive = it) }) },
                )
            }
        }

        item { SectionLabel(stringResource(R.string.characters_wb_entries)) }
        val entries = c.worldbook?.entries.orEmpty()
        if (entries.isEmpty()) {
            item { AtomCard { Text(stringResource(R.string.characters_wb_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        } else {
            item {
                Group {
                    entries.forEachIndexed { i, e ->
                        if (i > 0) GroupDivider(inset = 18.dp)
                        val issues = WorldbookMatcher.issues(e)
                        GroupRow(
                            title = e.name.ifBlank { e.keys.firstOrNull() ?: stringResource(R.string.characters_wb_untitled) },
                            subtitle = when {
                                issues.isNotEmpty() -> issueText(issues.first())
                                e.alwaysActive -> stringResource(R.string.characters_wb_always)
                                else -> e.keys.joinToString(", ")
                            } + if (e.compatibilityNote != null) " · " + stringResource(R.string.characters_wb_note) else "",
                            enabled = e.enabled,
                            onClick = { nav.open(CharacterRoutes.entry(id, e.id)) },
                            trailing = {
                                AtomSwitch(e.enabled, { on ->
                                    scope.launch { store.update(id) { ch -> ch.copy(worldbook = ch.worldbook?.replace(e.id) { it.copy(enabled = on) }) } }
                                })
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun applySettings(b: Worldbook, s: BookSettings) = b.copy(
    name = s.name.trim(),
    scanDepth = (s.scanDepth ?: Worldbook.DEFAULT_SCAN_DEPTH).coerceIn(0, 1000),
    tokenBudget = (s.budget ?: 0).coerceAtLeast(0),
    recursiveScanning = s.recursive,
)

private fun Worldbook.replace(entryId: String, change: (WorldEntry) -> WorldEntry) =
    copy(entries = entries.map { if (it.id == entryId) change(it) else it })

@Composable
private fun issueText(issue: EntryIssue): String = stringResource(
    when (issue) {
        EntryIssue.Disabled -> R.string.characters_issue_disabled
        EntryIssue.EmptyContent -> R.string.characters_issue_empty
        EntryIssue.NoKeywords -> R.string.characters_issue_no_keys
    },
)

private data class EntryDraft(val entry: WorldEntry, val keys: String, val secondary: String) {
    fun toEntry() = entry.copy(keys = splitKeys(keys), secondaryKeys = splitKeys(secondary))
}

/** Keywords split on commas, except inside a /regex/ key. */
internal fun splitKeys(text: String): List<String> {
    val out = ArrayList<String>()
    val current = StringBuilder()
    var inRegex = false
    for ((i, ch) in text.withIndex()) {
        when {
            ch == '/' && current.isBlank() && !inRegex -> { inRegex = true; current.append(ch) }
            ch == '/' && inRegex && (i == 0 || text[i - 1] != '\\') -> { inRegex = false; current.append(ch) }
            ch == ',' && !inRegex -> { out += current.toString(); current.clear() }
            else -> current.append(ch)
        }
    }
    out += current.toString()
    return out.map { it.trim() }.filter { it.isNotEmpty() }
}

@Composable
internal fun EntryScreen(id: String, entryId: String, nav: FeatureNav) {
    val store = CharacterSession.store
    LaunchedEffect(Unit) { store.load() }
    val all by store.characters.collectAsState()
    val loaded by store.loaded.collectAsState()
    val stored = all.firstOrNull { it.id == id }?.worldbook?.entries?.firstOrNull { it.id == entryId }
    var draft by remember { mutableStateOf<EntryDraft?>(null) }
    var deleted by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var logicMenu by remember { mutableStateOf(false) }
    LaunchedEffect(stored != null) {
        if (stored != null && draft == null) draft = EntryDraft(stored, joinList(stored.keys), joinList(stored.secondaryKeys))
    }
    val latest by rememberUpdatedState(draft)
    val wasDeleted by rememberUpdatedState(deleted)
    DisposableEffect(id, entryId) {
        onDispose {
            val d = latest ?: return@onDispose
            if (wasDeleted) return@onDispose
            SaveScope.scope.launch { store.update(id) { ch -> ch.copy(worldbook = ch.worldbook?.replace(entryId) { d.toEntry() }) } }
        }
    }

    LargeTitleScreen(title = stringResource(R.string.characters_entry), onBack = nav::back) {
        val d = draft
        if (d == null) {
            if (loaded && stored == null) {
                item { Text(stringResource(R.string.characters_not_found), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(18.dp)) }
            }
            return@LargeTitleScreen
        }
        val e = d.entry
        fun set(change: WorldEntry.() -> WorldEntry) {
            draft = d.copy(entry = e.change())
        }
        val issues = WorldbookMatcher.issues(d.toEntry())
        if (issues.isNotEmpty() || e.compatibilityNote != null) {
            item {
                AtomCard {
                    issues.forEach { Text(issueText(it), style = MaterialTheme.typography.bodyMedium) }
                    e.compatibilityNote?.let { note ->
                        Text(stringResource(R.string.characters_wb_note), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(note, style = MaterialTheme.typography.bodyMedium)
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                            CompactButton(stringResource(R.string.characters_clear), { set { copy(compatibilityNote = null) } }, primary = false)
                        }
                    }
                }
            }
        }
        item { LabeledField(stringResource(R.string.characters_entry_name), e.name, { v -> set { copy(name = v) } }, singleLine = true) }
        item { LabeledField(stringResource(R.string.characters_entry_content), e.content, { v -> set { copy(content = v) } }, minLines = 4) }
        item {
            LabeledField(stringResource(R.string.characters_entry_keys), d.keys, { draft = d.copy(keys = it) }, hint = stringResource(R.string.characters_entry_keys_hint), singleLine = true)
        }
        item {
            Spacer(Modifier.height(14.dp))
            Group {
                SwitchRow(stringResource(R.string.characters_entry_enabled), null, e.enabled) { v -> set { copy(enabled = v) } }
                GroupDivider(inset = 18.dp)
                SwitchRow(stringResource(R.string.characters_wb_always), stringResource(R.string.characters_entry_always_subtitle), e.alwaysActive) { v -> set { copy(alwaysActive = v) } }
            }
        }

        item { SectionLabel(stringResource(R.string.characters_entry_secondary)) }
        item {
            Group {
                SwitchRow(stringResource(R.string.characters_entry_require_secondary), null, e.requireSecondary) { v -> set { copy(requireSecondary = v) } }
                GroupDivider(inset = 18.dp)
                Box {
                    GroupRow(
                        title = stringResource(R.string.characters_entry_logic),
                        enabled = e.requireSecondary,
                        onClick = { logicMenu = true },
                        trailing = { RowValue(logicLabel(e.secondaryLogic)) },
                    )
                    AtomMenu(logicMenu, { logicMenu = false }) {
                        SecondaryLogic.entries.forEach { logic ->
                            AtomMenuItem(logicLabel(logic), e.secondaryLogic == logic) {
                                logicMenu = false
                                set { copy(secondaryLogic = logic) }
                            }
                        }
                    }
                }
            }
        }
        item { LabeledField(stringResource(R.string.characters_entry_secondary), d.secondary, { draft = d.copy(secondary = it) }, hint = stringResource(R.string.characters_entry_keys_hint), singleLine = true) }

        item { SectionLabel(stringResource(R.string.characters_entry_placement)) }
        item {
            SegmentedControl(
                options = listOf(stringResource(R.string.characters_placement_before) to null, stringResource(R.string.characters_placement_after) to null),
                selected = if (e.placement == Placement.BeforeDefinition) 0 else 1,
                onSelect = { i -> set { copy(placement = if (i == 0) Placement.BeforeDefinition else Placement.AfterDefinition) } },
            )
        }
        item { NumberField(stringResource(R.string.characters_entry_order), e.insertionOrder, { v -> set { copy(insertionOrder = v ?: 0) } }, stringResource(R.string.characters_entry_order_subtitle)) }
        item { NumberField(stringResource(R.string.characters_entry_priority), e.priority, { v -> set { copy(priority = v) } }, stringResource(R.string.characters_entry_priority_subtitle)) }
        item { NumberField(stringResource(R.string.characters_entry_scan_depth), e.scanDepth, { v -> set { copy(scanDepth = v?.coerceIn(0, 1000)) } }, stringResource(R.string.characters_entry_scan_depth_subtitle)) }
        item {
            Spacer(Modifier.height(14.dp))
            Group {
                SwitchRow(stringResource(R.string.characters_entry_case), null, e.caseSensitive) { v -> set { copy(caseSensitive = v) } }
                GroupDivider(inset = 18.dp)
                SwitchRow(stringResource(R.string.characters_entry_whole_words), null, e.wholeWords) { v -> set { copy(wholeWords = v) } }
                GroupDivider(inset = 18.dp)
                SwitchRow(stringResource(R.string.characters_entry_exclude_recursion), null, e.excludeRecursion) { v -> set { copy(excludeRecursion = v) } }
                GroupDivider(inset = 18.dp)
                SwitchRow(stringResource(R.string.characters_entry_prevent_recursion), null, e.preventRecursion) { v -> set { copy(preventRecursion = v) } }
            }
        }
        item {
            Spacer(Modifier.height(14.dp))
            Group {
                GroupRow(
                    title = stringResource(R.string.characters_entry_delete),
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = { confirmDelete = true },
                )
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.characters_entry_delete) + "?",
            body = null,
            confirm = stringResource(R.string.characters_delete),
            dismiss = stringResource(R.string.characters_cancel),
            onConfirm = {
                confirmDelete = false
                deleted = true
                SaveScope.scope.launch {
                    store.update(id) { ch -> ch.copy(worldbook = ch.worldbook?.let { b -> b.copy(entries = b.entries.filterNot { it.id == entryId }) }) }
                }
                nav.back()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    GroupRow(title = title, subtitle = subtitle, onClick = { onChange(!checked) }, trailing = { AtomSwitch(checked, onChange) })
}

@Composable
private fun logicLabel(logic: SecondaryLogic): String = stringResource(
    when (logic) {
        SecondaryLogic.AndAny -> R.string.characters_logic_and_any
        SecondaryLogic.AndAll -> R.string.characters_logic_and_all
        SecondaryLogic.NotAny -> R.string.characters_logic_not_any
        SecondaryLogic.NotAll -> R.string.characters_logic_not_all
    },
)
