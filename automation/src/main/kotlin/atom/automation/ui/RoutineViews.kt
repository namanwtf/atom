package atom.automation.ui

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import atom.automation.R
import atom.automation.task.Routine
import atom.automation.task.ScheduledTask
import atom.automation.task.TaskEngine
import atom.ui.components.ArrowRow
import atom.ui.components.AtomDialog
import atom.ui.components.AtomTextField
import atom.ui.components.DialogButtons
import atom.ui.components.Group
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** String resources of one routine. */
private class RoutineTexts(val title: Int, val whenText: Int, val blurb: Int, val prompt: Int)

private val Routine.texts: RoutineTexts
    get() = when (this) {
        Routine.MorningBrief -> RoutineTexts(R.string.routine_morning_title, R.string.routine_morning_when, R.string.routine_morning_blurb, R.string.routine_morning_prompt)
        Routine.PriceWatch -> RoutineTexts(R.string.routine_price_title, R.string.routine_price_when, R.string.routine_price_blurb, R.string.routine_price_prompt)
        Routine.WeeklyCleanup -> RoutineTexts(R.string.routine_cleanup_title, R.string.routine_cleanup_when, R.string.routine_cleanup_blurb, R.string.routine_cleanup_prompt)
        Routine.EveningWrapUp -> RoutineTexts(R.string.routine_evening_title, R.string.routine_evening_when, R.string.routine_evening_blurb, R.string.routine_evening_prompt)
    }

/** The unsaved task the editor opens with: the routine's settings and its wording in the app's language. */
internal fun routineDraft(context: Context, routine: Routine, what: String, where: String, now: Long): ScheduledTask {
    val t = routine.texts
    return if (routine == Routine.PriceWatch) {
        val item = what.trim()
        routine.draft(
            name = if (item.isEmpty()) context.getString(t.title) else context.getString(R.string.routine_price_name, item.take(40)),
            prompt = context.getString(t.prompt, item, where.trim(), TaskEngine.NO_CHANGE),
            now = now,
        )
    } else {
        routine.draft(context.getString(t.title), context.getString(t.prompt), now)
    }
}

/**
 * Opens [routine] in the editor; Price watch first asks what and where. [onOpen] gets the editor route under
 * `automation/task/`.
 */
internal class RoutineLauncher(private val ask: (Routine) -> Unit, private val onOpen: (String) -> Unit) {
    fun open(routine: Routine) = if (routine.asksFirst) ask(routine) else onOpen(routine.route())
}

/** The routines as one-tap cards in a row, for a list that already has tasks. */
@Composable
internal fun RoutineCards(launcher: RoutineLauncher) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Routine.entries.forEach { r ->
            val t = r.texts
            Card(modifier = Modifier.width(220.dp), insideMargin = PaddingValues(16.dp), showIndication = true, onClick = { launcher.open(r) }) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(t.title), style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface, maxLines = 1)
                    Text(stringResource(t.whenText), style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.primary, maxLines = 1)
                    Text(stringResource(t.blurb), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 2)
                }
            }
        }
    }
}

/** The empty list's card: what scheduled tasks are for, and the routines as rows. */
@Composable
internal fun RoutinesEmptyCard(launcher: RoutineLauncher) {
    Group(Modifier.padding(top = 12.dp)) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.routines_empty_title), style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface)
            Text(stringResource(R.string.routines_empty_body), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Routine.entries.forEach { r ->
            val t = r.texts
            ArrowRow(
                title = stringResource(t.title),
                onClick = { launcher.open(r) },
                summary = stringResource(t.whenText) + " · " + stringResource(t.blurb),
            )
        }
    }
}

/** Price watch's question: what to watch and where. Continue opens the editor with both in the prompt. */
@Composable
internal fun PriceWatchDialog(show: Boolean, onDismiss: () -> Unit, onContinue: (what: String, where: String) -> Unit) {
    var what by rememberSaveable { mutableStateOf("") }
    var where by rememberSaveable { mutableStateOf("") }
    AtomDialog(
        show = show,
        onDismiss = onDismiss,
        title = stringResource(R.string.routine_price_ask_title),
        summary = stringResource(R.string.routine_price_ask_summary),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AtomTextField(what, { what = it.take(200) }, label = stringResource(R.string.routine_price_what), singleLine = true)
            AtomTextField(where, { where = it.take(500) }, label = stringResource(R.string.routine_price_where), singleLine = true)
            Box {
                DialogButtons(
                    confirmText = stringResource(R.string.routine_price_continue),
                    onConfirm = {
                        onContinue(what, where)
                        what = ""
                        where = ""
                    },
                    dismissText = stringResource(R.string.routine_cancel),
                    onDismiss = onDismiss,
                    confirmEnabled = what.isNotBlank() && where.isNotBlank(),
                )
            }
        }
    }
}
