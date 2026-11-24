package atom.app.ui.terminal

import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import atom.app.R
import atom.app.ui.common.CircleIconButton
import atom.linux.LinuxState
import atom.linux.term.TerminalKeys
import atom.linux.term.TerminalScreen as Screen
import atom.linux.term.TerminalSession

/** Full-screen terminal: Android shell, root shell, or the installed Linux distro. */
@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: TerminalViewModel = viewModel { TerminalViewModel(context.applicationContext) }
    val kind by vm.kind.collectAsStateWithLifecycle()
    val rootAvailable by vm.rootAvailable.collectAsStateWithLifecycle()
    val linuxState by vm.linux.state.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var fontSize by rememberSaveable { mutableFloatStateOf(13f) }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    Column(
        Modifier
            .fillMaxSize()
            .background(TerminalColors.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        TopBar(
            kind = kind,
            rootEnabled = rootAvailable == true,
            linuxEnabled = linuxState is LinuxState.Ready,
            onKind = vm::select,
            onBack = onBack,
            onSmaller = { fontSize = (fontSize - 1f).coerceAtLeast(MIN_FONT) },
            onLarger = { fontSize = (fontSize + 1f).coerceAtMost(MAX_FONT) },
            onPaste = {
                val s = session ?: return@TopBar
                val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                if (!text.isNullOrEmpty()) s.write(TerminalKeys.paste(text, s.screen.value.bracketedPaste))
            },
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val s = session
            if (s != null) {
                SessionView(
                    session = s,
                    fontSize = fontSize,
                    onGridSize = vm::onSize,
                    onTap = {
                        focus.requestFocus()
                        keyboard?.show()
                    },
                    onZoom = { z -> fontSize = (fontSize * z).coerceIn(MIN_FONT, MAX_FONT) },
                    onRestart = vm::restart,
                )
            } else {
                // Measure even before a session exists, so it starts at the right size.
                TerminalGrid(
                    screen = Screen.EMPTY,
                    fontSizeSp = fontSize,
                    onGridSize = vm::onSize,
                    onTap = {},
                    onZoom = {},
                    modifier = Modifier.fillMaxSize(),
                )
                Text(
                    text = error?.let { stringResource(R.string.terminal_error, it) } ?: stringResource(R.string.terminal_starting),
                    color = if (error != null) MaterialTheme.colorScheme.error else TerminalColors.foreground.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }

        ExtraKeys(
            ctrl = ctrl,
            alt = alt,
            onCtrl = { ctrl = !ctrl },
            onAlt = { alt = !alt },
            onKey = { bytes ->
                session?.write(bytes)
                ctrl = false
                alt = false
            },
            appCursor = { session?.screen?.value?.applicationCursorKeys == true },
        )

        KeyboardSink(
            focus = focus,
            onText = { text ->
                val s = session ?: return@KeyboardSink
                val out = if (ctrl || alt) {
                    TerminalKeys.modified(text.take(1), ctrl, alt) + text.drop(1)
                } else {
                    text
                }
                ctrl = false
                alt = false
                s.write(out.replace('\n', '\r'))
            },
            onBytes = { session?.write(it) },
            appCursor = { session?.screen?.value?.applicationCursorKeys == true },
        )
    }

    LaunchedEffect(Unit) {
        focus.requestFocus()
    }
}

private const val MIN_FONT = 7f
private const val MAX_FONT = 28f

@Composable
private fun SessionView(
    session: TerminalSession,
    fontSize: Float,
    onGridSize: (Int, Int) -> Unit,
    onTap: () -> Unit,
    onZoom: (Float) -> Unit,
    onRestart: () -> Unit,
) {
    val screen by session.screen.collectAsStateWithLifecycle()
    val exit by session.exitCode.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        TerminalGrid(
            screen = screen,
            fontSizeSp = fontSize,
            onGridSize = onGridSize,
            onTap = onTap,
            onZoom = onZoom,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        val code = exit
        if (code != null) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.terminal_exited, code),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRestart) { Text(stringResource(R.string.terminal_restart)) }
            }
        }
    }
}

@Composable
private fun TopBar(
    kind: TerminalKind,
    rootEnabled: Boolean,
    linuxEnabled: Boolean,
    onKind: (TerminalKind) -> Unit,
    onBack: () -> Unit,
    onSmaller: () -> Unit,
    onLarger: () -> Unit,
    onPaste: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.terminal_cd_back), onBack, size = 40.dp)
        val options = listOf(
            Triple(TerminalKind.Android, R.string.terminal_kind_android, true),
            Triple(TerminalKind.Root, R.string.terminal_kind_root, rootEnabled),
            Triple(TerminalKind.Linux, R.string.terminal_kind_linux, linuxEnabled),
        )
        SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
            options.forEachIndexed { i, (k, label, enabled) ->
                SegmentedButton(
                    selected = kind == k,
                    onClick = { onKind(k) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    icon = {},
                    label = { Text(stringResource(label), maxLines = 1, fontSize = 13.sp) },
                )
            }
        }
        IconButton(onClick = onSmaller, modifier = Modifier.size(36.dp)) {
            Text("A−", color = TerminalColors.foreground, fontSize = 13.sp)
        }
        IconButton(onClick = onLarger, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Add, stringResource(R.string.terminal_font_larger), tint = TerminalColors.foreground)
        }
        TextButton(onClick = onPaste) { Text(stringResource(R.string.terminal_paste), fontSize = 13.sp) }
    }
}

@Composable
private fun ExtraKeys(
    ctrl: Boolean,
    alt: Boolean,
    onCtrl: () -> Unit,
    onAlt: () -> Unit,
    onKey: (String) -> Unit,
    appCursor: () -> Boolean,
) {
    fun key(k: TerminalKeys.Key) = TerminalKeys.key(k, appCursor(), alt = alt, ctrl = ctrl)
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF0B0B0B))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KeyCap(stringResource(R.string.terminal_key_esc)) { onKey(TerminalKeys.ESC) }
        KeyCap(stringResource(R.string.terminal_key_tab)) { onKey(TerminalKeys.TAB) }
        KeyCap(stringResource(R.string.terminal_key_ctrl), active = ctrl, onClick = onCtrl)
        KeyCap(stringResource(R.string.terminal_key_alt), active = alt, onClick = onAlt)
        IconKey(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.terminal_cd_left)) { onKey(key(TerminalKeys.Key.Left)) }
        IconKey(Icons.Filled.KeyboardArrowUp, stringResource(R.string.terminal_cd_up)) { onKey(key(TerminalKeys.Key.Up)) }
        IconKey(Icons.Filled.KeyboardArrowDown, stringResource(R.string.terminal_cd_down)) { onKey(key(TerminalKeys.Key.Down)) }
        IconKey(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.terminal_cd_right)) { onKey(key(TerminalKeys.Key.Right)) }
        KeyCap(stringResource(R.string.terminal_key_home)) { onKey(key(TerminalKeys.Key.Home)) }
        KeyCap(stringResource(R.string.terminal_key_end)) { onKey(key(TerminalKeys.Key.End)) }
        for (c in listOf("|", "-", "/", "~")) {
            KeyCap(c) { onKey(TerminalKeys.modified(c, ctrl, alt)) }
        }
    }
}

@Composable
private fun KeyCap(label: String, active: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (active) MaterialTheme.colorScheme.primary else Color(0xFF1C1C1E),
        contentColor = if (active) MaterialTheme.colorScheme.onPrimary else TerminalColors.foreground,
        modifier = Modifier.height(38.dp),
    ) {
        Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(label, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        }
    }
}

@Composable
private fun IconKey(icon: ImageVector, description: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF1C1C1E),
        contentColor = TerminalColors.foreground,
        modifier = Modifier.size(width = 42.dp, height = 38.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, description, Modifier.size(22.dp)) }
    }
}

/**
 * An invisible text field that receives the soft keyboard. It always holds a one-space sentinel so a
 * backspace on "empty" still produces an edit; each change is diffed against the previous text and sent as
 * DEL bytes plus inserted text, which also handles IME corrections. Hardware keys are mapped directly.
 */
// The TextFieldValue API is used on purpose: it exposes the IME composition, so the sentinel is only reset
// between compositions.
@Suppress("DEPRECATION")
@Composable
private fun KeyboardSink(
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
