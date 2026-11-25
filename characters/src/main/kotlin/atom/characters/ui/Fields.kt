package atom.characters.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Labelled graphite text field: no indicator line, white cursor. Multi-line fields grow with their text. */
@Composable
internal fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    error: String? = null,
    keyboard: KeyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
) {
    Column(modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
        FieldBox(value, onValueChange, hint, singleLine, minLines, keyboard)
        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
internal fun FieldBox(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String?,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    leading: (@Composable () -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(c.surfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(10.dp))
        }
        Box(Modifier.weight(1f)) {
            if (value.isEmpty() && hint != null) {
                Text(hint, style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant.copy(alpha = 0.7f))
            }
            val state = rememberTextFieldState(value)
            val currentOnChange by rememberUpdatedState(onValueChange)
            val currentValue by rememberUpdatedState(value)
            LaunchedEffect(value) {
                if (state.text.toString() != value) state.setTextAndPlaceCursorAtEnd(value)
            }
            LaunchedEffect(state) {
                snapshotFlow { state.text.toString() }.collect { if (it != currentValue) currentOnChange(it) }
            }
            BasicTextField(
                state = state,
                lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.MultiLine(minHeightInLines = minLines),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onSurface),
                cursorBrush = SolidColor(c.onSurface),
                keyboardOptions = keyboard,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun SearchField(value: String, onValueChange: (String) -> Unit, hint: String) {
    FieldBox(
        value = value,
        onValueChange = onValueChange,
        hint = hint,
        leading = { Icon(Icons.Filled.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
    )
}

/** Integer field; empty text maps to null. */
@Composable
internal fun NumberField(label: String, value: Int?, onValueChange: (Int?) -> Unit, hint: String? = null) {
    LabeledField(
        label = label,
        value = value?.toString().orEmpty(),
        onValueChange = { text ->
            val digits = text.filterIndexed { i, ch -> ch.isDigit() || (i == 0 && ch == '-') }.take(7)
            onValueChange(digits.toIntOrNull())
        },
        hint = hint,
        singleLine = true,
        keyboard = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
internal fun ConfirmDialog(
    title: String,
    body: String?,
    confirm: String,
    dismiss: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
) {
    val c = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surfaceContainerHigh,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = body?.let { { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant) } },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, color = if (destructive) c.error else c.onSurface, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismiss, color = c.onSurface) } },
    )
}

/** Saves started when a screen is left; they outlive the composition but are tiny, one-shot and never repeat. */
internal object SaveScope {
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
}

/** Comma-separated text to a clean list and back. */
internal fun splitList(text: String): List<String> = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }

internal fun joinList(list: List<String>): String = list.joinToString(", ")
