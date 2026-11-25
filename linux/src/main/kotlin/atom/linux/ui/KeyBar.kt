package atom.linux.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import atom.linux.R
import atom.linux.term.TerminalKeys
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Ctrl/Alt state: a tap arms it for one key, a long press locks it until tapped again. */
internal enum class ModState { Off, Once, Locked }

internal class Modifiers {
    var ctrl by mutableStateOf(ModState.Off)
    var alt by mutableStateOf(ModState.Off)

    /** Called after a key used the modifiers: one-shot ones disarm, locked ones stay. */
    fun consumed() {
        if (ctrl == ModState.Once) ctrl = ModState.Off
        if (alt == ModState.Once) alt = ModState.Off
    }

    val ctrlOn get() = ctrl != ModState.Off
    val altOn get() = alt != ModState.Off

    /** Applies the armed modifiers to typed [text] (first character only) and disarms one-shot ones. */
    fun apply(text: String): String {
        if (text.isEmpty() || (!ctrlOn && !altOn)) return text
        val out = TerminalKeys.modified(text.take(1), ctrlOn, altOn) + text.drop(1)
        consumed()
        return out
    }
}

/**
 * The key bar: Esc, Tab, Ctrl/Alt (tap once, long-press to lock), arrows that repeat while held, Enter, Home/End,
 * PgUp/PgDn, common symbols, Paste and a keyboard toggle.
 */
@Composable
internal fun KeyBar(
    mods: Modifiers,
    appCursor: () -> Boolean,
    onSend: (String) -> Unit,
    onPaste: () -> Unit,
    onToggleKeyboard: () -> Unit,
) {
    fun key(k: TerminalKeys.Key): String {
        val s = TerminalKeys.key(k, appCursor(), alt = mods.altOn, ctrl = mods.ctrlOn)
        mods.consumed()
        return s
    }
    Column(Modifier.fillMaxWidth().background(Color(0xFF0B0B0B)).padding(vertical = 3.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KeyCap(stringResource(R.string.terminal_key_esc)) { onSend(TerminalKeys.ESC); mods.consumed() }
            KeyCap(stringResource(R.string.terminal_key_tab)) { onSend(TerminalKeys.TAB); mods.consumed() }
            ModKey(stringResource(R.string.terminal_key_ctrl), mods.ctrl) { mods.ctrl = it }
            ModKey(stringResource(R.string.terminal_key_alt), mods.alt) { mods.alt = it }
            RepeatKey(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.terminal_cd_left)) { onSend(key(TerminalKeys.Key.Left)) }
            RepeatKey(Icons.Filled.KeyboardArrowUp, stringResource(R.string.terminal_cd_up)) { onSend(key(TerminalKeys.Key.Up)) }
            RepeatKey(Icons.Filled.KeyboardArrowDown, stringResource(R.string.terminal_cd_down)) { onSend(key(TerminalKeys.Key.Down)) }
            RepeatKey(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.terminal_cd_right)) { onSend(key(TerminalKeys.Key.Right)) }
            KeyCap(stringResource(R.string.terminal_key_enter)) { onSend(TerminalKeys.ENTER); mods.consumed() }
            KeyCap(stringResource(R.string.terminal_key_home)) { onSend(key(TerminalKeys.Key.Home)) }
            KeyCap(stringResource(R.string.terminal_key_end)) { onSend(key(TerminalKeys.Key.End)) }
            KeyCap(stringResource(R.string.terminal_key_pgup)) { onSend(key(TerminalKeys.Key.PageUp)) }
            KeyCap(stringResource(R.string.terminal_key_pgdn)) { onSend(key(TerminalKeys.Key.PageDown)) }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KeyCap(stringResource(R.string.terminal_keyboard), onClick = onToggleKeyboard)
            KeyCap(stringResource(R.string.terminal_paste), onClick = onPaste)
            for (c in SYMBOLS) KeyCap(c, small = true) { onSend(mods.apply(c)) }
        }
    }
}

private val SYMBOLS = listOf("|", "-", "/", "~", "_", "$", "&", "*", ":", ";", "\"", "'", "`", "<", ">", "=", "(", ")", "{", "}", "[", "]", "\\", "#", "!", "?", "%", "^", "@")

@Composable
private fun KeyCap(label: String, active: Boolean = false, small: Boolean = false, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier
            .height(36.dp)
            .widthIn(min = if (small) 34.dp else 44.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (active) c.primary else Color(0xFF1C1C1E))
            .pointerInput(onClick) { detectTapGestures(onTap = { onClick() }) }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = if (active) c.onPrimary else TerminalColors.foreground)
    }
}

/** Tap: one-shot on/off. Long press: lock. Locked shows a bar under the label. */
@Composable
private fun ModKey(label: String, state: ModState, onChange: (ModState) -> Unit) {
    val c = MaterialTheme.colorScheme
    val current by rememberUpdatedState(state)
    Box(
        Modifier
            .height(36.dp)
            .widthIn(min = 48.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (state != ModState.Off) c.primary else Color(0xFF1C1C1E))
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onChange(if (current == ModState.Off) ModState.Once else ModState.Off) },
                    onLongPress = { onChange(ModState.Locked) },
                )
            }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (state == ModState.Locked) "$label ▪" else label,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            color = if (state != ModState.Off) c.onPrimary else TerminalColors.foreground,
        )
    }
}

/** Sends once on press, then repeats while held (after 400 ms, every 50 ms). The timer exists only while pressed. */
@Composable
private fun RepeatKey(icon: ImageVector, description: String, onKey: () -> Unit) {
    val scope = rememberCoroutineScope()
    val send by rememberUpdatedState(onKey)
    Box(
        Modifier
            .size(width = 44.dp, height = 36.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(Color(0xFF1C1C1E))
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    send()
                    val repeat = scope.launch {
                        delay(400)
                        while (isActive) {
                            send()
                            delay(50)
                        }
                    }
                    tryAwaitRelease()
                    repeat.cancel()
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = TerminalColors.foreground)
    }
}

// The TextFieldValue API is used on purpose: it exposes the IME composition, so the sentinel is only reset
// between compositions.
@Suppress("DEPRECATION")
@Composable
internal fun KeyboardSink(
    focus: FocusRequester,
    onText: (String) -> Unit,
    onBytes: (String) -> Unit,
    appCursor: () -> Boolean,
) {
    var value by remember { mutableStateOf(TextFieldValue(SENTINEL, TextRange(SENTINEL.length))) }
    BasicTextField(
        value = value,
        onValueChange = { new ->
            val old = value.text
            val now = new.text
            var prefix = 0
            while (prefix < old.length && prefix < now.length && old[prefix] == now[prefix]) prefix++
            val deleted = old.length - prefix
            val inserted = now.substring(prefix)
            if (deleted > 0) onBytes(TerminalKeys.BACKSPACE.repeat(deleted))
            if (inserted.isNotEmpty()) onText(inserted)
            value = if (new.composition == null && (now.length > 64 || !now.startsWith(SENTINEL) || now.contains('\n'))) {
                TextFieldValue(SENTINEL, TextRange(SENTINEL.length))
            } else {
                new
            }
        },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password, // no suggestions, nothing learned from what is typed
            imeAction = ImeAction.Send,
        ),
        keyboardActions = KeyboardActions(onAny = { onBytes(TerminalKeys.ENTER) }),
        singleLine = true,
        modifier = Modifier
            .size(1.dp)
            .alpha(0f)
            .focusRequester(focus)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val app = appCursor()
                val seq = when (e.key) {
                    Key.Enter, Key.NumPadEnter -> TerminalKeys.ENTER
                    Key.Backspace -> TerminalKeys.BACKSPACE
                    Key.Tab -> TerminalKeys.TAB
                    Key.Escape -> TerminalKeys.ESC
                    Key.DirectionUp -> TerminalKeys.key(TerminalKeys.Key.Up, app, e.isAltPressed, e.isCtrlPressed, e.isShiftPressed)
                    Key.DirectionDown -> TerminalKeys.key(TerminalKeys.Key.Down, app, e.isAltPressed, e.isCtrlPressed, e.isShiftPressed)
                    Key.DirectionLeft -> TerminalKeys.key(TerminalKeys.Key.Left, app, e.isAltPressed, e.isCtrlPressed, e.isShiftPressed)
                    Key.DirectionRight -> TerminalKeys.key(TerminalKeys.Key.Right, app, e.isAltPressed, e.isCtrlPressed, e.isShiftPressed)
                    Key.MoveHome -> TerminalKeys.key(TerminalKeys.Key.Home, app)
                    Key.MoveEnd -> TerminalKeys.key(TerminalKeys.Key.End, app)
                    Key.PageUp -> TerminalKeys.key(TerminalKeys.Key.PageUp, app)
                    Key.PageDown -> TerminalKeys.key(TerminalKeys.Key.PageDown, app)
                    Key.Insert -> TerminalKeys.key(TerminalKeys.Key.Insert, app)
                    Key.Delete -> TerminalKeys.key(TerminalKeys.Key.Delete, app)
                    else -> {
                        val cp = e.utf16CodePoint
                        if ((e.isCtrlPressed || e.isAltPressed) && cp > 0) {
                            TerminalKeys.modified(String(Character.toChars(cp)), e.isCtrlPressed, e.isAltPressed)
                        } else {
                            null
                        }
                    }
                }
                if (seq != null) onBytes(seq)
                seq != null
            },
    )
}

private const val SENTINEL = " "
