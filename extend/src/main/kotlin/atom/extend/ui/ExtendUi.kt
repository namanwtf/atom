package atom.extend.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import atom.extend.R
import atom.ui.components.AtomDialog
import atom.ui.components.DialogButtons
import atom.ui.theme.extras

/** Graphite text field matching the grouped lists. */
@Composable
internal fun ExtendField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    mono: Boolean = false,
    secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    if (secret) {
        SecretField(value, onValueChange, placeholder, modifier)
        return
    }
    val c = MaterialTheme.colorScheme
    val style = (if (mono) MaterialTheme.extras.mono else MaterialTheme.typography.bodyLarge).copy(color = c.onSurface)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        textStyle = style,
        cursorBrush = SolidColor(c.onSurface),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = !mono),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner -> FieldFrame(placeholder, value.isEmpty()) { inner() } },
    )
}

@Composable
private fun FieldFrame(placeholder: String, empty: Boolean, inner: @Composable () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(c.surfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (empty) Text(placeholder, style = MaterialTheme.typography.bodyLarge.copy(color = c.onSurfaceVariant))
        inner()
    }
}

/** Masked entry for tokens; the text is only ever handed to [onValueChange]. */
@Composable
private fun SecretField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier) {
    val state = rememberTextFieldState(value)
    val c = MaterialTheme.colorScheme
    LaunchedEffect(state) { snapshotFlow { state.text.toString() }.collect { onValueChange(it) } }
    LaunchedEffect(value) { if (value != state.text.toString()) state.setTextAndPlaceCursorAtEnd(value) }
    BasicSecureTextField(
        state = state,
        textObfuscationMode = TextObfuscationMode.RevealLastTyped,
        textStyle = MaterialTheme.extras.mono.copy(color = c.onSurface),
        cursorBrush = SolidColor(c.onSurface),
        modifier = modifier.fillMaxWidth(),
        decorator = { inner -> FieldFrame(placeholder, state.text.isEmpty()) { inner() } },
    )
}

@Composable
internal fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp, top = 12.dp),
    )
}

@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = modifier.padding(horizontal = 18.dp, vertical = 8.dp))
}

/** A two-button dialog; [destructive] paints the confirm action in the error colour. */
@Composable
internal fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    dismiss: String? = null,
) {
    // Miuix dialog: the confirm button is the primary one either way ([destructive] is kept for callers); the
    // title says what is removed.
    AtomDialog(show = true, onDismiss = onDismiss, title = title, summary = text) {
        DialogButtons(
            confirmText = confirm,
            onConfirm = onConfirm,
            dismissText = dismiss ?: stringResource(R.string.ext_cancel),
            onDismiss = onDismiss,
        )
    }
}

/** Text entry dialog (single field). */
@Composable
internal fun InputDialog(
    title: String,
    placeholder: String,
    confirm: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    secret: Boolean = false,
    initial: String = "",
) {
    var value by remember { mutableStateOf(initial) }
    AtomDialog(show = true, onDismiss = onDismiss, title = title) {
        Column {
            ExtendField(value, { value = it }, placeholder, secret = secret, mono = secret)
            DialogButtons(
                confirmText = confirm,
                onConfirm = { onConfirm(value) },
                dismissText = stringResource(R.string.ext_cancel),
                onDismiss = onDismiss,
                confirmEnabled = value.isNotBlank(),
            )
        }
    }
}

/** A Miuix dialog with one "Got it" button, for descriptions. */
@Composable
internal fun InfoDialog(title: String, text: String, onDismiss: () -> Unit) {
    AtomDialog(show = true, onDismiss = onDismiss, title = title, summary = text) {
        atom.ui.components.AtomTextButton(stringResource(R.string.ext_got_it), onDismiss, Modifier.fillMaxWidth().padding(top = 12.dp), primary = true)
    }
}

/** Inline progress line, shown only while work is actually running. */
@Composable
internal fun WorkingLine(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurface)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One-line outcome of the last action: neutral, or in the error colour. */
@Composable
internal fun Notice(text: String, error: Boolean) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 18.dp),
        )
    }
}

internal data class Message(val text: String, val error: Boolean)

internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KiB".format(bytes / 1024.0)
    else -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
}
