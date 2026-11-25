package atom.extend.memory

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import atom.extend.Extend
import atom.extend.R
import atom.extend.ui.ConfirmDialog
import atom.extend.ui.ExtendField
import atom.extend.ui.Hint
import atom.extend.ui.Message
import atom.extend.ui.Notice
import atom.extend.ui.formatBytes
import atom.ui.components.AtomCard
import atom.ui.components.AtomSwitch
import atom.ui.components.BottomActionBar
import atom.ui.components.CompactButton
import atom.ui.components.Group
import atom.ui.components.GroupRow
import atom.ui.components.LargeTitleScreen
import atom.ui.components.SegmentedControl
import atom.ui.feature.FeatureNav
import atom.ui.theme.extras
import kotlinx.coroutines.launch

@Composable
internal fun MemoryScreen(store: MemoryStore, nav: FeatureNav) {
    val state by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var draft by rememberSaveable { mutableStateOf<String?>(null) }
    var baseHash by rememberSaveable { mutableStateOf(state.hash) }
    var conflict by remember { mutableStateOf<MemoryState?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Message?>(null) }
    val dirty = draft != null && draft != state.text
    val saved = stringResource(R.string.mem_saved)
    val cleared = stringResource(R.string.mem_cleared)

    fun save(text: String, hash: String) = scope.launch {
        when (val r = store.save(text, hash)) {
            is MemoryEdit.Saved -> {
                draft = null
                baseHash = r.state.hash
                message = Message(saved, false)
            }
            is MemoryEdit.Conflict -> conflict = r.current
            is MemoryEdit.Rejected -> message = Message(r.reason, true)
        }
    }

    BackHandler(enabled = dirty) { confirmLeave = true }

    LargeTitleScreen(
        title = stringResource(R.string.ext_entry_memory),
        onBack = { if (dirty) confirmLeave = true else nav.back() },
        bottomBar = {
            if (dirty) {
                BottomActionBar {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CompactButton(stringResource(R.string.ext_discard), onClick = { draft = null }, primary = false, modifier = Modifier.weight(1f))
                        CompactButton(stringResource(R.string.ext_save), onClick = { save(draft.orEmpty(), baseHash) }, modifier = Modifier.weight(1f))
                    }
                }
            }
        },
    ) {
        item {
            Group {
                GroupRow(
                    title = stringResource(R.string.mem_use),
                    subtitle = stringResource(R.string.mem_use_sub),
                    trailing = { AtomSwitch(state.enabled, { store.setEnabled(it) }) },
                )
            }
        }
        item {
            Spacer(Modifier.height(16.dp))
            SegmentedControl(
                options = listOf(stringResource(R.string.mem_preview) to null, stringResource(R.string.mem_edit) to null),
                selected = tab,
                onSelect = {
                    if (it == 1 && draft == null) {
                        draft = state.text
                        baseHash = state.hash
                    }
                    tab = it
                },
            )
            Spacer(Modifier.height(12.dp))
        }
        item {
            if (tab == 0) {
                AtomCard { MarkdownPreview(draft ?: state.text) }
            } else {
                ExtendField(
                    value = draft ?: state.text,
                    onValueChange = { draft = it },
                    placeholder = stringResource(R.string.mem_placeholder),
                    singleLine = false,
                    minLines = 12,
                    mono = true,
                )
            }
            message?.let { Notice(it.text, it.error) }
        }
        item {
            val tokens = MemoryText.budgetChars(Extend.contextWindow.currentContextWindow()) / 4
            Hint(stringResource(R.string.mem_size, formatBytes(MemoryText.byteSize(state.text).toLong())), Modifier.padding(top = 8.dp))
            Hint(stringResource(R.string.mem_budget, tokens))
        }
        item {
            Spacer(Modifier.height(12.dp))
            Group {
                GroupRow(
                    title = stringResource(R.string.mem_clear),
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = { confirmClear = true },
                )
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.mem_clear_title),
            text = stringResource(R.string.mem_clear_body),
            confirm = stringResource(R.string.mem_clear_confirm),
            destructive = true,
            onDismiss = { confirmClear = false },
            onConfirm = {
                confirmClear = false
                scope.launch {
                    if (store.clear(null) is MemoryEdit.Saved) {
                        draft = null
                        message = Message(cleared, false)
                    }
                }
            },
        )
    }

    conflict?.let { latest ->
        ConfirmDialog(
            title = stringResource(R.string.mem_conflict_title),
            text = stringResource(R.string.mem_conflict_body),
            confirm = stringResource(R.string.mem_conflict_keep),
            dismiss = stringResource(R.string.mem_conflict_load),
            onConfirm = {
                conflict = null
                save(draft.orEmpty(), latest.hash)
            },
            onDismiss = {
                conflict = null
                draft = null
                baseHash = latest.hash
            },
        )
    }

    if (confirmLeave) {
        ConfirmDialog(
            title = stringResource(R.string.mem_unsaved_title),
            text = stringResource(R.string.mem_unsaved_body),
            confirm = stringResource(R.string.ext_discard),
            destructive = true,
            onDismiss = { confirmLeave = false },
            onConfirm = {
                confirmLeave = false
                draft = null
                nav.back()
            },
        )
    }
}

/** Headings, bullets and paragraphs; enough to read MEMORY.md comfortably without a full Markdown renderer. */
@Composable
private fun MarkdownPreview(text: String) {
    if (text.isBlank()) {
        Text(stringResource(R.string.mem_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            var fenced = false
            text.lines().forEach { line ->
                if (line.trimStart().startsWith("```")) {
                    fenced = !fenced
                    return@forEach
                }
                val heading = Regex("^(#{1,6})\\s+(.*)$").matchEntire(line)
                when {
                    fenced -> Text(line, style = MaterialTheme.extras.mono)
                    heading != null -> Text(
                        heading.groupValues[2],
                        style = if (heading.groupValues[1].length == 1) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    line.isBlank() -> Spacer(Modifier.height(4.dp))
                    line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") ->
                        Text("•  " + line.trimStart().drop(2), style = MaterialTheme.typography.bodyMedium)
                    line.trimStart().startsWith("<!--") -> Unit
                    else -> Text(line, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
