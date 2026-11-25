package atom.voice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.maxLengthTrim
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import atom.ui.components.CompactButton
import atom.voice.R

/** A labelled text field inside a settings group: label, optional hint, graphite input. */
@Composable
internal fun FieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = true,
    maxChars: Int? = null,
    secret: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    val onChange = rememberUpdatedState(onValueChange)
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = c.onSurface, modifier = Modifier.weight(1f))
            if (maxChars != null) Text("${value.length}/$maxChars", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(c.surfaceContainerHigh)
                .padding(start = 14.dp, end = if (trailing != null) 4.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                if (value.isEmpty() && placeholder != null) {
                    Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant, maxLines = 1)
                }
                val state = rememberTextFieldState(value)
                // Two-way sync with the draft: user edits flow out, external resets (Save, Remove) flow in.
                LaunchedEffect(state) {
                    snapshotFlow { state.text.toString() }.collect { text -> if (text != value) onChange.value(text) }
                }
                LaunchedEffect(value) {
                    if (state.text.toString() != value) state.setTextAndPlaceCursorAtEnd(value)
                }
                val style = MaterialTheme.typography.bodyLarge.copy(color = c.onSurface)
                val limit = maxChars?.let { InputTransformation.maxLengthTrim(it) }
                if (secret) {
                    BasicSecureTextField(
                        state = state,
                        textStyle = style,
                        cursorBrush = SolidColor(c.onSurface),
                        inputTransformation = limit,
                        textObfuscationMode = TextObfuscationMode.RevealLastTyped,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    BasicTextField(
                        state = state,
                        textStyle = style,
                        cursorBrush = SolidColor(c.onSurface),
                        inputTransformation = limit,
                        lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.MultiLine(maxHeightInLines = 6),
                        keyboardOptions = KeyboardOptions(imeAction = if (singleLine) ImeAction.Done else ImeAction.Default),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            trailing?.invoke()
        }
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
    }
}

/**
 * An encrypted credential: shows "Saved" when one is stored, accepts a replacement, and offers Remove.
 * [value] is the unsaved edit ("" = unchanged).
 */
@Composable
internal fun SecretRow(
    label: String,
    saved: Boolean,
    removed: Boolean,
    value: String,
    onValueChange: (String) -> Unit,
    onRemove: () -> Unit,
    hint: String? = null,
) {
    FieldRow(
        label = label,
        value = value,
        onValueChange = onValueChange,
        hint = hint,
        placeholder = if (saved && !removed) stringResource(R.string.speech_f_key_saved) else null,
        secret = true,
        trailing = if (saved && !removed && value.isEmpty()) {
            { CompactButton(stringResource(R.string.speech_f_remove), onClick = onRemove, primary = false) }
        } else {
            null
        },
    )
}

/** A selectable row with a check mark, for single-choice lists (services, voices). */
@Composable
internal fun ChoiceRow(title: String, selected: Boolean, onClick: () -> Unit, subtitle: String? = null, enabled: Boolean = true) {
    val c = MaterialTheme.colorScheme
    val alpha = if (enabled) 1f else 0.4f
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = c.onSurface.copy(alpha = alpha))
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant.copy(alpha = alpha))
        }
        Spacer(Modifier.width(12.dp))
        if (selected) Icon(Icons.Filled.Check, null, Modifier.size(20.dp), tint = c.onSurface) else Spacer(Modifier.size(20.dp))
    }
}

@Composable
internal fun NoteText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 8.dp),
    )
}
