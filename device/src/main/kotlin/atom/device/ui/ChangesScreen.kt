package atom.device.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import atom.device.undo.JournalEntry
import atom.device.undo.Undo
import atom.ui.components.AtomCard
import atom.ui.components.CompactButton
import atom.ui.components.Group
import atom.ui.components.GroupDivider
import atom.ui.components.GroupRow
import atom.ui.components.LargeTitleScreen
import atom.ui.components.Pill
import atom.ui.components.SectionLabel
import atom.ui.feature.FeatureNav
import atom.ui.theme.extras
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The undo journal: every change Atom's tools made, newest first, each with one-tap Undo. */
@Composable
fun ChangesScreen(nav: FeatureNav) {
    val context = LocalContext.current
    val journal = remember { Undo.journal(context) }
    val entries by journal.entries.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<Long?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmToday by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val time = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val day = remember { DateTimeFormatter.ofPattern("EEEE d MMMM") }

    fun undo(e: JournalEntry) {
        busy = e.id
        scope.launch {
            val r = withContext(Dispatchers.IO) { journal.undo(e.id) }
            message = (if (r.ok) "Undone: " else "Could not undo: ") + r.message
            busy = null
        }
    }

    if (confirmToday) {
        AlertDialog(
            onDismissRequest = { confirmToday = false },
            title = { Text("Undo everything from today?") },
            text = { Text("Atom puts back each setting it changed today, newest first.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmToday = false
                    busy = -1
                    scope.launch {
                        val results = withContext(Dispatchers.IO) { journal.undoToday() }
                        message = "Undid ${results.count { it.second.ok }} of ${results.size} changes." +
                            results.filterNot { it.second.ok }.joinToString("") { "\n✗ ${it.first.summary}: ${it.second.message}" }
                        busy = null
                    }
                }) { Text("Undo all") }
            },
            dismissButton = { TextButton(onClick = { confirmToday = false }) { Text("Cancel") } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }

    LargeTitleScreen(title = "Change history", onBack = nav::back) {
        item {
            AtomCard {
                Text(
                    "Every switch, level, rule or setting Atom changes is recorded with how it was before. Undo puts it back and checks it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val todayCount = journal.today().count { it.undoable }
                CompactButton(
                    if (todayCount == 0) "Nothing to undo today" else "Undo everything from today ($todayCount)",
                    { confirmToday = true },
                    enabled = todayCount > 0 && busy == null,
                )
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        if (entries.isEmpty()) {
            item {
                Spacer(Modifier.height(24.dp))
                Text("No changes yet.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val byDay = entries.asReversed().groupBy { Instant.ofEpochMilli(it.atMillis).atZone(zone).toLocalDate() }
        for ((date, list) in byDay) {
            item(key = "d$date") {
                SectionLabel(if (date == LocalDate.now(zone)) "Today" else day.format(date))
                Group {
                    list.forEachIndexed { i, e ->
                        if (i > 0) GroupDivider(18.dp)
                        GroupRow(
                            title = e.summary,
                            subtitle = buildString {
                                append(time.format(Instant.ofEpochMilli(e.atMillis).atZone(zone)))
                                append(" · ").append(e.tool)
                                if (!e.verified) append(" · unconfirmed")
                                e.undoNote?.let { append("\n").append(it) }
                            },
                            trailing = {
                                when {
                                    e.undoneAtMillis != null -> Pill("Undone", MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.extras.success)
                                    e.undoData == null -> Pill("Can't undo", MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                                    else -> CompactButton(if (busy == e.id) "Undoing…" else "Undo", { undo(e) }, primary = false, enabled = busy == null)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
