package atom.app.ui.models

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import atom.app.R
import atom.ui.components.CompactButton
import atom.ui.components.Pill
import atom.ui.theme.AtomIcons
import atom.ui.theme.extras

/** Graphite rounded field: no indicator line, no coloured focus, white cursor. */
@Composable
internal fun FieldFrame(
    placeholder: String?,
    isEmpty: Boolean,
    isError: Boolean = false,
    singleLine: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    inner: @Composable () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(c.surfaceContainerHigh)
            .then(if (isError) Modifier.border(1.dp, c.error.copy(alpha = 0.7f), shape) else Modifier)
            .padding(start = if (leading != null) 12.dp else 16.dp, end = if (trailing != null) 6.dp else 16.dp),
        verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
    ) {
        if (leading != null) {
            Box(Modifier.padding(top = if (singleLine) 0.dp else 14.dp)) { leading() }
            Spacer(Modifier.width(10.dp))
        }
        Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
            if (isEmpty && placeholder != null) {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = c.onSurfaceVariant,
                    maxLines = if (singleLine) 1 else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            inner()
        }
        trailing?.invoke()
    }
}

@Composable
internal fun AtomTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String?,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    singleLine: Boolean = true,
    mono: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    val base: TextStyle = if (mono) MaterialTheme.extras.mono else MaterialTheme.typography.bodyLarge
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        maxLines = if (singleLine) 1 else 12,
        textStyle = base.copy(color = c.onSurface),
        cursorBrush = SolidColor(c.onSurface),
        keyboardOptions = keyboardOptions,
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner -> FieldFrame(placeholder, value.isEmpty(), isError, singleLine, leading, trailing) { inner() } },
    )
}

@Composable
internal fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    AtomTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        modifier = modifier,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
        leading = { Icon(Icons.Filled.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
    )
}

@Composable
internal fun SecretField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    var visible by remember { mutableStateOf(false) }
    val state = rememberTextFieldState(value)
    val c = MaterialTheme.colorScheme
    LaunchedEffect(state) { snapshotFlow { state.text.toString() }.collect { onValueChange(it) } }
    LaunchedEffect(value) { if (value != state.text.toString()) state.setTextAndPlaceCursorAtEnd(value) }
    BasicSecureTextField(
        state = state,
        textObfuscationMode = if (visible) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onSurface),
        cursorBrush = SolidColor(c.onSurface),
        modifier = Modifier.fillMaxWidth(),
        decorator = { inner ->
            FieldFrame(
                placeholder = placeholder,
                isEmpty = state.text.isEmpty(),
                trailing = {
                    Surface(onClick = { visible = !visible }, shape = CircleShape, color = c.surfaceContainerHigh, modifier = Modifier.size(40.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (visible) AtomIcons.VisibilityOff else AtomIcons.Eye,
                                stringResource(if (visible) R.string.cd_hide_secret else R.string.cd_show_secret),
                                Modifier.size(20.dp),
                                tint = c.onSurfaceVariant,
                            )
                        }
                    }
                },
            ) { inner() }
        },
    )
}

/** A stored secret: masked preview, with replace and remove. [masked] is like "sk-…a1b2" and never the full key. */
@Composable
internal fun SavedSecret(masked: String?, onReplace: () -> Unit, onRemove: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, null, Modifier.size(16.dp), tint = MaterialTheme.extras.success)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.secret_saved), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (masked != null) {
                Spacer(Modifier.width(10.dp))
                Text(masked, style = MaterialTheme.extras.mono, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactButton(stringResource(R.string.action_replace), onClick = onReplace, primary = false)
            CompactButton(stringResource(R.string.action_remove), onClick = onRemove, primary = false, contentColor = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
internal fun QuietPill(text: String) {
    Pill(text, MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun CheckMark(visible: Boolean, contentDescription: String? = null) {
    if (!visible) return
    Box(Modifier.size(24.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Check, contentDescription, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
internal fun Note(text: String, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = modifier.padding(start = 18.dp, end = 18.dp, top = 8.dp))
}
