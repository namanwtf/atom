@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package atom.automation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import atom.automation.schedule.ScheduleText
import atom.ui.components.ArrowRow
import atom.ui.components.AtomDialog
import atom.ui.components.AtomMenu
import atom.ui.components.DialogButtons
import atom.ui.components.SpinnerOption
import atom.ui.components.SpinnerRow
import atom.ui.components.AtomMenuItem
import atom.ui.components.CompactButton
import atom.ui.components.GroupRow
import atom.ui.components.RowChevron
import atom.ui.components.RowValue
import atom.ui.components.StatusDot
import atom.ui.theme.extras
import java.time.DayOfWeek

@Composable
internal fun AtomField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    number: Boolean = false,
    label: String? = null,
) {
    val c = MaterialTheme.colorScheme
    TextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, color = c.onSurfaceVariant) },
        label = label?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = minLines,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        textStyle = MaterialTheme.typography.bodyLarge,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = c.onSurface,
            focusedLabelColor = c.onSurfaceVariant,
            unfocusedLabelColor = c.onSurfaceVariant,
        ),
    )
}

/** A row whose value is picked from the Miuix dropdown. */
@Composable
internal fun <T> ChoiceRow(title: String, value: T, options: List<T>, label: (T) -> String, onPick: (T) -> Unit, subtitle: String? = null) {
    // A stored value that is no longer offered (a removed model, an unknown field value) still shows its label.
    val shown = if (value in options) options else options + value
    SpinnerRow(
        title = title,
        options = shown.map { SpinnerOption(label(it)) },
        selectedIndex = shown.indexOf(value),
        onSelect = { i -> shown.getOrNull(i)?.let(onPick) },
        summary = subtitle,
    )
}

@Composable
internal fun TimeRow(title: String, minuteOfDay: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    GroupRow(title, onClick = { open = true }, trailing = { RowValue(ScheduleText.clock(minuteOfDay)) })
    if (open) TimeDialog(minuteOfDay, { open = false }) { onChange(it); open = false }
}

@Composable
internal fun TimeDialog(minuteOfDay: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val state = rememberTimePickerState(minuteOfDay / 60, minuteOfDay % 60, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        confirmButton = { TextButton({ onPick(state.hour * 60 + state.minute) }) { Text("Set", color = MaterialTheme.colorScheme.onSurface) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        text = { TimePicker(state) },
    )
}

@Composable
internal fun DayPicker(days: Set<DayOfWeek>, onChange: (Set<DayOfWeek>) -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        DayOfWeek.entries.forEach { d ->
            val on = d in days
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (on) c.primary else c.surfaceContainerHighest)
                    .clickable { onChange(if (on) days - d else days + d) },
                contentAlignment = Alignment.Center,
            ) {
                Text(ScheduleText.dayShort(d).take(2), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = if (on) c.onPrimary else c.onSurface)
            }
        }
    }
}

/** Wrapping row of pill choices. */
@Composable
internal fun <T> PillChoices(options: List<T>, selected: T?, label: (T) -> String, onPick: (T) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { o -> CompactButton(label(o), { onPick(o) }, primary = o == selected) }
    }
}

@Composable
internal fun NavRow(title: String, subtitle: String? = null, value: String? = null, onClick: () -> Unit) =
    ArrowRow(title, onClick, summary = subtitle, value = value)

@Composable
internal fun HealthDot(ok: Boolean) = StatusDot(if (ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.extras.warning)

@Composable
internal fun Note(text: String, modifier: Modifier = Modifier, warning: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (warning) MaterialTheme.extras.warning else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 18.dp, vertical = 6.dp),
    )
}

@Composable
internal fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AtomDialog(show = true, onDismiss = onDismiss, title = title, summary = text) {
        DangerButtons(confirm, onConfirm, onDismiss)
    }
}

/** The Miuix dialog button pair with the confirm button in the error colour, for deletes and clears. */
@Composable
private fun DangerButtons(confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        top.yukonga.miuix.kmp.basic.TextButton(text = "Cancel", onClick = onDismiss, modifier = Modifier.weight(1f))
        androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
        top.yukonga.miuix.kmp.basic.TextButton(
            text = confirm,
            onClick = onConfirm,
            modifier = Modifier.weight(1f),
            colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColors(
                color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.error,
                textColor = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onError,
            ),
        )
    }
}

@Composable
internal fun OutlineTag(text: String) {
    Box(
        Modifier
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun Gap(h: Int = 12) = Spacer(Modifier.height(h.dp))

@Composable
internal fun FieldBox(content: @Composable () -> Unit) = Column(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) { content() }
