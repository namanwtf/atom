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
import atom.linux.TerminalText
import atom.ui.theme.extras
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.vector.ImageVector
import atom.linux.Distro
import atom.linux.term.SessionStatus
import atom.linux.term.SessionStatuses
import atom.ui.components.AtomDialog
import atom.ui.components.AtomRow
import atom.ui.components.AtomTextButton
import atom.ui.theme.AtomIcons
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Tasks
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen terminal with Console (PTY) and Simple (blocks) modes and up to six sessions per mode. The toolbar
 * has the environment switch (Android / the Linux distribution) on the left and Sessions, Background tasks and the
 * Console / Simple toggle on the right.
 */
@Composable
fun TerminalScreen(onBack: () -> Unit, openRoute: (String) -> Unit = {}) {
    val context = LocalContext.current
    val linux = remember { LinuxEnv.get(context) }
    val linuxState by linux.state.collectAsStateWithLifecycle()
    val sessions by TerminalSessions.sessions.collectAsStateWithLifecycle()
    var modeIndex by rememberSaveable { mutableStateOf(0) }
    val mode = if (modeIndex == 0) SessionMode.Console else SessionMode.Simple
    // Which side of the environment switch is selected; null until the first visit decides.
    var linuxSide by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var selectedConsole by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedSimple by rememberSaveable { mutableStateOf<String?>(null) }
    var fontSize by rememberSaveable { mutableFloatStateOf(TerminalText.clamp(linux.config.value.terminalFontSp)) }
    var rootAvailable by remember { mutableStateOf<Boolean?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var grid by remember { mutableStateOf(80 to 24) }
    var showSessions by remember { mutableStateOf(false) }
    val mods = remember { Modifiers() }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var keyboardShown by remember { mutableStateOf(true) }

    // Remember the size (pinch) once it settles; nothing runs while it is unchanged.
    LaunchedEffect(fontSize) {
        delay(600)
        withContext(Dispatchers.IO) { linux.setTerminalFontSp(fontSize) }
    }

    LaunchedEffect(Unit) {
        rootAvailable = linux.probeRoot().root
        if (linux.state.value is LinuxState.Ready) linux.refresh()
    }

    val ready = linuxState as? LinuxState.Ready
    val linuxUsable = ready != null && !ready.rootLost
    val distroName = (ready?.distro ?: (linuxState as? LinuxState.Installing)?.distro ?: Distro.Debian).shortName
    val onLinux = linuxSide ?: false

    fun sideOf(e: SessionEntry) = e.env == ShellEnv.Linux
    val inMode = sessions.filter { it.mode == mode }
    val onSide = inMode.filter { sideOf(it) == onLinux }
    val selectedId = if (mode == SessionMode.Console) selectedConsole else selectedSimple
    val selected = onSide.firstOrNull { it.id == selectedId } ?: onSide.lastOrNull()

    fun select(e: SessionEntry) {
        if (mode == SessionMode.Console) selectedConsole = e.id else selectedSimple = e.id
        linuxSide = sideOf(e)
    }

    fun openSession(env: ShellEnv) {
        error = null
        try {
            select(TerminalSessions.open(context, mode, env, grid.first, grid.second))
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }

    fun restart(e: SessionEntry) = TerminalSessions.restart(context, e.id, grid.first, grid.second)

    // The first visit picks Linux when it is usable and opens one session so the screen is never empty.
    LaunchedEffect(rootAvailable) {
        if (rootAvailable == null || linuxSide != null) return@LaunchedEffect
        val wantLinux = linux.state.value.let { it is LinuxState.Ready && !it.rootLost }
        linuxSide = wantLinux
        if (sessions.none { it.mode == mode && sideOf(it) == wantLinux }) openSession(if (wantLinux) ShellEnv.Linux else ShellEnv.Android)
    }

    // Coming back to a session that ended in the meantime reconnects it.
    LaunchedEffect(selected?.id) {
        val e = selected ?: return@LaunchedEffect
        if (e.terminal?.exitCode?.value != null) {
            restart(e)
            Toast.makeText(context, context.getString(R.string.terminal_reconnected), Toast.LENGTH_SHORT).show()
        }
    }

    fun switchSide(toLinux: Boolean) {
        if (toLinux == onLinux) return
        linuxSide = toLinux
        val label = if (toLinux) distroName else ShellEnv.Android.label
        Toast.makeText(context, context.getString(R.string.terminal_switched, label), Toast.LENGTH_SHORT).show()
        val existing = inMode.lastOrNull { sideOf(it) == toLinux }
        when {
            existing != null -> select(existing)
            toLinux && !linuxUsable -> Unit // the body explains and offers the install
            TerminalSessions.canOpen(mode) -> openSession(if (toLinux) ShellEnv.Linux else ShellEnv.Android)
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
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.terminal_cd_back), onBack, size = 40.dp)
            SegmentedControl(
                options = listOf(ShellEnv.Android.label to null, distroName to null),
                selected = if (onLinux) 1 else 0,
                onSelect = { switchSide(it == 1) },
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            ToolbarIcon(MiuixIcons.GridView, stringResource(R.string.terminal_cd_sessions)) { showSessions = true }
            ToolbarIcon(MiuixIcons.Tasks, stringResource(R.string.terminal_cd_jobs)) { openRoute(LinuxFeature.ROUTE_JOBS) }
            if (mode == SessionMode.Console) {
                ToolbarIcon(MiuixIcons.ListView, stringResource(R.string.terminal_cd_simple)) { modeIndex = 1 }
            } else {
                ToolbarIcon(AtomIcons.Terminal, stringResource(R.string.terminal_cd_console)) { modeIndex = 0 }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val entry = selected
            when {
                entry == null && onLinux && !linuxUsable -> {
                    // Measure anyway so the first session starts at the right size.
                    TerminalGrid(Screen.EMPTY, fontSize, onGridSize = { c, r -> grid = c to r }, onTap = {}, onZoom = {}, modifier = Modifier.fillMaxSize())
                    LinuxMissing(
                        state = linuxState,
                        onInstall = { openRoute(LinuxFeature.ROUTE_HOME) },
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                entry == null -> {
                    TerminalGrid(Screen.EMPTY, fontSize, onGridSize = { c, r -> grid = c to r }, onTap = {}, onZoom = {}, modifier = Modifier.fillMaxSize())
                    Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            error?.let { stringResource(R.string.terminal_error, it) } ?: stringResource(R.string.terminal_empty),
                            color = if (error != null) MaterialTheme.colorScheme.error else TerminalColors.foreground.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                        if (TerminalSessions.canOpen(mode)) {
                            Spacer(Modifier.height(12.dp))
                            AtomTextButton(stringResource(R.string.terminal_new_session), { openSession(if (onLinux) ShellEnv.Linux else ShellEnv.Android) }, primary = true)
                        }
                    }
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
                    onRestart = { restart(entry) },
                )
                else -> SimpleView(entry, fontSize, onRestart = { restart(entry) })
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

        if (mode == SessionMode.Console && selected != null) {
            val terminal = selected.terminal
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
            LaunchedEffect(selected.id) { runCatching { focus.requestFocus() } }
        }
    }

    SessionsDialog(
        show = showSessions,
        sessions = inMode,
        current = selected,
        canOpen = TerminalSessions.canOpen(mode),
        rootAvailable = rootAvailable == true,
        newEnv = if (onLinux && linuxUsable) ShellEnv.Linux else ShellEnv.Android,
        onDismiss = { showSessions = false },
        onSelect = {
            showSessions = false
            select(it)
        },
        onNew = { env ->
            showSessions = false
            openSession(env)
        },
        onRestart = { restart(it) },
        onClose = { TerminalSessions.close(it.id) },
    )
}

private const val MIN_FONT = TerminalText.MIN_SP
private const val MAX_FONT = TerminalText.MAX_SP

internal fun clipboardText(context: Context): String? {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
    return clip.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}

internal fun copyText(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("terminal", text))
    Toast.makeText(context, context.getString(R.string.terminal_copied), Toast.LENGTH_SHORT).show()
}

@Composable
private fun ToolbarIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(icon, description, Modifier.size(22.dp), tint = TerminalColors.foreground)
    }
}

/** Shown on the Linux side of the switch while Linux cannot start: not installed, installing, or root lost. */
@Composable
private fun LinuxMissing(state: LinuxState, onInstall: () -> Unit, modifier: Modifier = Modifier) {
    val text = when {
        state is LinuxState.Installing -> stringResource(R.string.terminal_installing)
        state is LinuxState.Ready && state.rootLost -> stringResource(R.string.terminal_root_lost)
        else -> stringResource(R.string.terminal_not_installed)
    }
    val button = if (state is LinuxState.NotInstalled || state is LinuxState.Failed) R.string.terminal_install_now else R.string.terminal_open_linux
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = TerminalColors.foreground, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        AtomTextButton(stringResource(button), onInstall, primary = true)
    }
}

/** "Sessions": New session, then one row per session with its labels (Current / Running / Idle / Exited). */
@Composable
private fun SessionsDialog(
    show: Boolean,
    sessions: List<SessionEntry>,
    current: SessionEntry?,
    canOpen: Boolean,
    rootAvailable: Boolean,
    newEnv: ShellEnv,
    onDismiss: () -> Unit,
    onSelect: (SessionEntry) -> Unit,
    onNew: (ShellEnv) -> Unit,
    onRestart: (SessionEntry) -> Unit,
    onClose: (SessionEntry) -> Unit,
) {
    AtomDialog(show = show, onDismiss = onDismiss, title = stringResource(R.string.terminal_sessions)) {
        Column {
            AtomTextButton(stringResource(R.string.terminal_new_session), { onNew(newEnv) }, Modifier.fillMaxWidth(), primary = true, enabled = canOpen)
            if (rootAvailable) {
                Spacer(Modifier.height(8.dp))
                AtomTextButton(stringResource(R.string.terminal_new_root_session), { onNew(ShellEnv.Root) }, Modifier.fillMaxWidth(), enabled = canOpen)
            }
            if (!canOpen) {
                Text(
                    stringResource(R.string.terminal_limit, TerminalSessions.MAX_PER_MODE),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            if (sessions.isEmpty()) {
                Text(stringResource(R.string.terminal_no_sessions), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            val now = System.currentTimeMillis()
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                for (s in sessions) {
                    val exited = s.terminal?.exitCode?.collectAsStateWithLifecycle()?.value != null
                    val labels = SessionStatuses.of(current = s === current, exited = exited, busy = s.busy(now))
                    val summary = labels.map { statusLabel(it) }.joinToString(" · ")
                    AtomRow(
                        title = s.title,
                        summary = summary,
                        onClick = { onSelect(s) },
                        end = {
                            Row(Modifier.align(Alignment.CenterVertically), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                AtomTextButton(stringResource(R.string.terminal_restart), { onRestart(s) })
                                AtomTextButton(stringResource(R.string.terminal_close), { onClose(s) })
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            AtomTextButton(stringResource(R.string.terminal_done), onDismiss, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun statusLabel(s: SessionStatus): String = stringResource(
    when (s) {
        SessionStatus.Current -> R.string.terminal_status_current
        SessionStatus.Running -> R.string.terminal_status_running
        SessionStatus.Idle -> R.string.terminal_status_idle
        SessionStatus.Exited -> R.string.terminal_status_exited
    },
)

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
        TextButton(onClick = onRestart) { Text(stringResource(R.string.terminal_reconnect)) }
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
                        block.exitCode == 0 -> MaterialTheme.extras.success
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
