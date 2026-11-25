package atom.linux.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import atom.linux.LinuxEnv
import atom.linux.LinuxState
import atom.linux.R
import atom.linux.ShellEnv
import atom.linux.term.SessionEntry
import atom.linux.term.SessionMode
import atom.linux.term.SimpleBlock
import atom.linux.term.SimpleSession
import atom.linux.term.TerminalKeys
import atom.linux.term.TerminalScreen as Screen
import atom.linux.term.TerminalSessions
import atom.ui.components.AtomMenu
import atom.ui.components.CircleIconButton
import atom.ui.components.SegmentedControl
import kotlinx.coroutines.launch

/** Full-screen terminal with Console (PTY) and Simple (blocks) modes and up to six sessions per mode. */
@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val linux = remember { LinuxEnv.get(context) }
    val linuxState by linux.state.collectAsStateWithLifecycle()
    val sessions by TerminalSessions.sessions.collectAsStateWithLifecycle()
    var modeIndex by rememberSaveable { mutableStateOf(0) }
    val mode = if (modeIndex == 0) SessionMode.Console else SessionMode.Simple
    var selectedConsole by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedSimple by rememberSaveable { mutableStateOf<String?>(null) }
    var fontSize by rememberSaveable { mutableFloatStateOf(13f) }
    var rootAvailable by remember { mutableStateOf<Boolean?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var grid by remember { mutableStateOf(80 to 24) }
    var autoOpened by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val mods = remember { Modifiers() }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var keyboardShown by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        rootAvailable = linux.probeRoot().root
        if (linux.state.value is LinuxState.Ready) linux.refresh()
    }

    val inMode = sessions.filter { it.mode == mode }
    val selectedId = if (mode == SessionMode.Console) selectedConsole else selectedSimple
    val selected = inMode.firstOrNull { it.id == selectedId } ?: inMode.lastOrNull()

    fun open(env: ShellEnv) {
        error = null
        try {
            val e = TerminalSessions.open(context, mode, env, grid.first, grid.second)
            if (mode == SessionMode.Console) selectedConsole = e.id else selectedSimple = e.id
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }

    fun select(id: String) {
        if (mode == SessionMode.Console) selectedConsole = id else selectedSimple = id
    }

    // The first visit opens one session so the screen is never empty.
    LaunchedEffect(mode, rootAvailable) {
        if (!autoOpened && inMode.isEmpty() && rootAvailable != null) {
            autoOpened = true
            open(if (linux.state.value is LinuxState.Ready && !(linux.state.value as LinuxState.Ready).rootLost) ShellEnv.Linux else ShellEnv.Android)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(TerminalColors.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.terminal_cd_back), onBack, size = 40.dp)
            SegmentedControl(
                options = listOf(stringResource(R.string.terminal_mode_console) to null, stringResource(R.string.terminal_mode_simple) to null),
                selected = modeIndex,
                onSelect = { modeIndex = it },
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { fontSize = (fontSize - 1f).coerceAtLeast(MIN_FONT) }, modifier = Modifier.size(36.dp)) {
                Text("A−", color = TerminalColors.foreground, fontSize = 13.sp)
            }
            IconButton(onClick = { fontSize = (fontSize + 1f).coerceAtMost(MAX_FONT) }, modifier = Modifier.size(36.dp)) {
                Text("A+", color = TerminalColors.foreground, fontSize = 13.sp)
            }
        }

        SessionTabs(
            sessions = inMode,
            selected = selected,
            canOpen = TerminalSessions.canOpen(mode),
            rootAvailable = rootAvailable == true,
            linuxAvailable = (linuxState as? LinuxState.Ready)?.rootLost == false,
            onSelect = { select(it.id) },
            onOpen = ::open,
            onRestart = { TerminalSessions.restart(context, it.id, grid.first, grid.second) },
            onClose = { TerminalSessions.close(it.id) },
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val entry = selected
            when {
                entry == null -> {
                    Text(
                        error?.let { stringResource(R.string.terminal_error, it) } ?: stringResource(R.string.terminal_empty),
                        color = if (error != null) MaterialTheme.colorScheme.error else TerminalColors.foreground.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                    // Measure anyway so the first session starts at the right size.
                    TerminalGrid(Screen.EMPTY, fontSize, onGridSize = { c, r -> grid = c to r }, onTap = {}, onZoom = {}, modifier = Modifier.fillMaxSize())
                }
                mode == SessionMode.Console -> ConsoleView(
                    entry = entry,
                    fontSize = fontSize,
                    onGridSize = { c, r ->
                        grid = c to r
                        entry.terminal?.resize(c, r)
                    },
                    onTap = {
                        focus.requestFocus()
                        keyboard?.show()
                        keyboardShown = true
                    },
                    onZoom = { z -> fontSize = (fontSize * z).coerceIn(MIN_FONT, MAX_FONT) },
                    onRestart = { TerminalSessions.restart(context, entry.id, grid.first, grid.second) },
                )
                else -> SimpleView(entry, fontSize, onRestart = { TerminalSessions.restart(context, entry.id, grid.first, grid.second) })
            }
            if (error != null && entry != null) {
                Text(
                    stringResource(R.string.terminal_error, error!!),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.BottomCenter).background(Color(0xCC000000)).padding(8.dp),
                )
            }
        }

        if (mode == SessionMode.Console) {
            val terminal = selected?.terminal
            KeyBar(
                mods = mods,
                appCursor = { terminal?.screen?.value?.applicationCursorKeys == true },
                onSend = { terminal?.write(it) },
                onPaste = {
                    val text = clipboardText(context)
                    if (terminal != null && !text.isNullOrEmpty()) terminal.write(TerminalKeys.paste(text, terminal.screen.value.bracketedPaste))
                },
                onToggleKeyboard = {
                    if (keyboardShown) keyboard?.hide() else {
                        focus.requestFocus()
                        keyboard?.show()
                    }
                    keyboardShown = !keyboardShown
                },
            )
            KeyboardSink(
                focus = focus,
                onText = { text -> terminal?.write(mods.apply(text).replace('\n', '\r')) },
                onBytes = { terminal?.write(it) },
                appCursor = { terminal?.screen?.value?.applicationCursorKeys == true },
            )
            LaunchedEffect(selected?.id) { runCatching { focus.requestFocus() } }
        }
    }
}

private const val MIN_FONT = 7f
private const val MAX_FONT = 28f

internal fun clipboardText(context: Context): String? {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
    return clip.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}

internal fun copyText(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("terminal", text))
    Toast.makeText(context, context.getString(R.string.terminal_copied), Toast.LENGTH_SHORT).show()
}

@Composable
private fun SessionTabs(
    sessions: List<SessionEntry>,
    selected: SessionEntry?,
    canOpen: Boolean,
    rootAvailable: Boolean,
    linuxAvailable: Boolean,
    onSelect: (SessionEntry) -> Unit,
    onOpen: (ShellEnv) -> Unit,
    onRestart: (SessionEntry) -> Unit,
    onClose: (SessionEntry) -> Unit,
) {
    var newMenu by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<SessionEntry?>(null) }
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (s in sessions) {
            val active = s === selected
            val ended = s.terminal?.exitCode?.collectAsStateWithLifecycle()?.value != null
            Box {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (active) c.surfaceContainerHighest else c.surfaceContainer)
                        .combinedClickable(onClick = { onSelect(s) }, onLongClick = { actionsFor = s })
                        .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        s.title + if (ended) " ·" else "",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (active) c.onSurface else c.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = { actionsFor = s }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.MoreVert, null, Modifier.size(16.dp), tint = c.onSurfaceVariant)
                    }
                }
                AtomMenu(expanded = actionsFor === s, onDismiss = { actionsFor = null }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.terminal_restart)) }, onClick = { actionsFor = null; onRestart(s) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.terminal_close)) }, onClick = { actionsFor = null; onClose(s) })
                }
            }
        }
        Box {
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(c.surfaceContainer)
                    .combinedClickable(enabled = canOpen, onClick = { newMenu = true })
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Add, null, Modifier.size(16.dp), tint = if (canOpen) c.onSurface else c.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.terminal_new), style = MaterialTheme.typography.labelLarge, color = if (canOpen) c.onSurface else c.onSurfaceVariant)
            }
            AtomMenu(expanded = newMenu, onDismiss = { newMenu = false }) {
                DropdownMenuItem(text = { Text(ShellEnv.Android.label) }, onClick = { newMenu = false; onOpen(ShellEnv.Android) })
                DropdownMenuItem(
                    text = { Text(if (rootAvailable) ShellEnv.Root.label else stringResource(R.string.terminal_root_unavailable)) },
                    enabled = rootAvailable,
                    onClick = { newMenu = false; onOpen(ShellEnv.Root) },
                )
                DropdownMenuItem(
                    text = { Text(if (linuxAvailable) ShellEnv.Linux.label else stringResource(R.string.terminal_linux_unavailable)) },
                    enabled = linuxAvailable,
                    onClick = { newMenu = false; onOpen(ShellEnv.Linux) },
                )
            }
        }
        if (!canOpen) {
            Text(stringResource(R.string.terminal_limit, TerminalSessions.MAX_PER_MODE), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
    }
}

@Composable
private fun ConsoleView(
    entry: SessionEntry,
    fontSize: Float,
    onGridSize: (Int, Int) -> Unit,
    onTap: () -> Unit,
    onZoom: (Float) -> Unit,
    onRestart: () -> Unit,
) {
    val generation by entry.generation.collectAsStateWithLifecycle()
    val terminal = remember(entry.id, generation) { entry.terminal } ?: return
    val screen by terminal.screen.collectAsStateWithLifecycle()
    val exit by terminal.exitCode.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        TerminalGrid(screen, fontSize, onGridSize, onTap, onZoom, Modifier.weight(1f).fillMaxWidth())
        exit?.let { code -> ExitBar(code, onRestart) }
    }
}

@Composable
private fun ExitBar(code: Int, onRestart: () -> Unit) {
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

// ================================================================ Simple mode

@Composable
private fun SimpleView(entry: SessionEntry, fontSize: Float, onRestart: () -> Unit) {
    val context = LocalContext.current
    val generation by entry.generation.collectAsStateWithLifecycle()
    val simple: SimpleSession = remember(entry.id, generation) { entry.simple } ?: return
    val terminal = remember(entry.id, generation) { entry.terminal } ?: return
    val blocks by simple.blocks.collectAsStateWithLifecycle()
    val exit by terminal.exitCode.collectAsStateWithLifecycle()
    var input by rememberSaveable(entry.id) { mutableStateOf("") }
    val running = blocks.lastOrNull()?.running == true
    val listState = rememberLazyListState()
    val family = remember { TerminalFont.get(context) }
    val style = remember(fontSize, family) { terminalTextStyle(fontSize, family) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(blocks.size, blocks.lastOrNull()?.rows?.size) {
        if (blocks.isNotEmpty()) listState.scrollToItem(blocks.size - 1, Int.MAX_VALUE / 2)
    }

    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val charPx = remember(style) { measurer.measure(AnnotatedString("M".repeat(20)), style).size.width / 20f }
            val cols = with(density) { ((maxWidth - 32.dp).toPx() / charPx).toInt() }.coerceIn(20, 400)
            LaunchedEffect(cols) { simple.resize(cols, 40) }
            if (blocks.isEmpty()) {
                Text(
                    stringResource(R.string.terminal_simple_empty),
                    color = TerminalColors.foreground.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    textAlign = TextAlign.Center,
                )
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                items(blocks, key = { it.id }) { b ->
                    BlockCard(
                        block = b,
                        style = style,
                        onCopyCommand = { b.command?.let { copyText(context, it) } },
                        onCopyOutput = { copyText(context, b.outputText()) },
                        onReuse = { input = b.command.orEmpty() },
                    )
                }
            }
        }
        exit?.let { code -> ExitBar(code, onRestart) }
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF0B0B0B)).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(if (running) R.string.terminal_simple_input_hint else R.string.terminal_simple_hint)) },
                textStyle = style.copy(fontSize = 14.sp),
                singleLine = false,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = {
                    if (input.isNotEmpty() || running) {
                        simple.send(input)
                        input = ""
                        scope.launch { if (blocks.isNotEmpty()) listState.scrollToItem(blocks.size - 1) }
                    }
                }),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TerminalColors.foreground, unfocusedTextColor = TerminalColors.foreground),
                shape = RoundedCornerShape(14.dp),
            )
            if (running) {
                TextButton(onClick = { simple.interrupt() }) { Text(stringResource(R.string.terminal_interrupt)) }
            }
            TextButton(
                onClick = {
                    simple.send(input)
                    input = ""
                },
                enabled = input.isNotEmpty() || running,
            ) { Text(stringResource(if (running) R.string.terminal_send else R.string.terminal_run)) }
        }
    }
}

@Composable
private fun BlockCard(
    block: SimpleBlock,
    style: androidx.compose.ui.text.TextStyle,
    onCopyCommand: () -> Unit,
    onCopyOutput: () -> Unit,
    onReuse: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0E0E10))
            .combinedClickable(onClick = {}, onLongClick = { menu = true })
            .padding(10.dp),
    ) {
        if (block.command != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$ " + block.command,
                    style = style.copy(color = c.primary),
                    modifier = Modifier.weight(1f),
                )
                val status = when {
                    block.running -> stringResource(R.string.terminal_running)
                    else -> stringResource(R.string.terminal_exit_code, block.exitCode ?: -1)
                }
                Text(
                    status,
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        block.running -> c.onSurfaceVariant
                        block.exitCode == 0 -> Color(0xFF7FD6AE)
                        else -> c.error
                    },
                )
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.MoreVert, null, Modifier.size(16.dp), tint = c.onSurfaceVariant)
                    }
                    AtomMenu(expanded = menu, onDismiss = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.terminal_copy_command)) }, onClick = { menu = false; onCopyCommand() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.terminal_copy_output)) }, onClick = { menu = false; onCopyOutput() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.terminal_reuse)) }, onClick = { menu = false; onReuse() })
                    }
                }
            }
        }
        if (block.rows.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Column {
                    val lineHeight = with(LocalDensity.current) { style.lineHeight.toDp() }
                    for (row in block.rows) TerminalLine(row, -1, false, style, lineHeight)
                }
            }
        }
    }
}
