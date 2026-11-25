@file:OptIn(ExperimentalMaterial3Api::class)

package atom.app.ui.chat

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import atom.app.R
import atom.app.agent.CompactResult
import atom.app.agent.PendingApproval
import atom.ui.components.AtomMenu
import atom.ui.components.CircleIconButton
import atom.ui.components.GlassEdge
import atom.ui.components.Group
import atom.ui.components.GroupDivider
import atom.ui.components.glassBar
import atom.ui.components.glassSource
import atom.ui.components.rememberGlassState
import atom.ui.theme.AtomIcons
import atom.ui.theme.AtomMark
import atom.ui.theme.extras
import atom.device.Accessibility
import atom.app.ui.models.ModelPickerSheet
import atom.voice.tts.ReadAloud
import atom.voice.tts.ReadAloudState
import atom.voice.ui.DictationButton
import atom.web.browser.BrowserCards
import androidx.compose.foundation.text.input.TextFieldState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Chat: pure black canvas, graphite surfaces, white as the only accent. Bars are frosted glass with the transcript
 * scrolling underneath. Motion is short (150–220 ms) and nothing animates while idle.
 */

/**
 * The home chat. The model pill opens the per-conversation model picker ([onPickModel] replaces it). [micSlot]
 * replaces the default dictation button. [onOpenRoute] opens feature routes (voice settings from the mic, the
 * browser take-over from a browser card); without it those links are hidden.
 */
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSetup: () -> Unit,
    onPickModel: (() -> Unit)? = null,
    micSlot: (@Composable () -> Unit)? = null,
    onOpenTerminal: (() -> Unit)? = null,
    onOpenBrowser: (() -> Unit)? = null,
    onOpenCharacters: (() -> Unit)? = null,
    onOpenPermissions: (() -> Unit)? = null,
    onReadAloud: ((String) -> Unit)? = null,
    permissionsNeedAttention: Boolean = false,
    onOpenRoute: ((String) -> Unit)? = null,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val rows by vm.drawer.collectAsStateWithLifecycle()
    val query by vm.search.collectAsStateWithLifecycle()
    val attachments by vm.attachments.collectAsStateWithLifecycle()
    val editing by vm.editing.collectAsStateWithLifecycle()
    val prefill by vm.prefill.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val restore by vm.restore.collectAsStateWithLifecycle()
    val accessibilityConnected by Accessibility.connected.collectAsStateWithLifecycle()
    val subagents by vm.subagents.collectAsStateWithLifecycle()
    val reading by ReadAloud.state.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }
    var showSubagents by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val copy = rememberCopier()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val input = rememberTextFieldState()
    var showStats by remember { mutableStateOf(false) }
    var showPath by remember { mutableStateOf(false) }
    var exportId by remember { mutableStateOf<String?>(null) }
    val pickModel = onPickModel ?: { showPicker = true }
    val permissions = onOpenPermissions ?: onOpenSetup

    LaunchedEffect(prefill) {
        prefill?.let {
            input.setTextAndPlaceCursorAtEnd(it)
            vm.consumePrefill()
        }
    }
    LaunchedEffect(events) {
        events.forEach { e ->
            vm.consumeEvent(e)
            val text = when (e) {
                is ChatEvent.Toast -> if (e.arg != null) context.getString(e.message, e.arg) else context.getString(e.message)
                is ChatEvent.Compacted -> when (val r = e.result) {
                    is CompactResult.Done -> context.getString(R.string.compact_done, r.summarized)
                    CompactResult.NothingToCompact -> context.getString(R.string.compact_nothing)
                    CompactResult.Busy -> context.getString(R.string.compact_busy)
                    is CompactResult.Failed -> context.getString(R.string.compact_failed, r.message)
                }
            }
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris ->
        if (uris.isNotEmpty()) vm.addImages(context, uris)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.addFiles(context, uris)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::addFolder) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri: Uri? ->
        val id = exportId
        exportId = null
        if (uri == null || id == null) return@rememberLauncherForActivityResult
        scope.launch {
            val content = vm.exportMarkdown(id)?.second ?: return@launch
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) } }.isSuccess
            }
            Toast.makeText(context, context.getString(if (ok) R.string.export_done else R.string.export_failed), Toast.LENGTH_SHORT).show()
        }
    }
    fun export(id: String) {
        scope.launch {
            val (name, _) = vm.exportMarkdown(id) ?: return@launch
            exportId = id
            exporter.launch(name)
        }
    }

    val drawerCallbacks = DrawerCallbacks(
        onNewChat = {
            vm.newChat()
            input.clearText()
            scope.launch { drawerState.close() }
        },
        onOpen = {
            vm.open(it)
            scope.launch { drawerState.close() }
        },
        onPin = vm::togglePin,
        onRename = vm::rename,
        onDelete = vm::delete,
        onExport = ::export,
        onModels = {
            scope.launch { drawerState.close() }
            onOpenModels()
        },
        onCharacters = onOpenCharacters?.let { open -> { scope.launch { drawerState.close() }; open() } },
        onPermissions = {
            scope.launch { drawerState.close() }
            permissions()
        },
        onSettings = {
            scope.launch { drawerState.close() }
            onOpenSettings()
        },
    )

    val actions = MessageActions(
        onCopy = {
            copy(it)
            Toast.makeText(context, R.string.chat_copied, Toast.LENGTH_SHORT).show()
        },
        onRegenerate = vm::regenerate,
        onBranch = { vm.branch(it, context.getString(R.string.chat_branch_suffix)) },
        onDeleteFrom = vm::deleteFrom,
        onEdit = vm::startEdit,
        onReadAloud = onReadAloud?.let { speak -> { text, _ -> speak(text) } } ?: vm::readAloud,
        laterTurns = vm::laterTurns,
        textAt = vm::textAt,
        readingId = when (val r = reading) {
            is ReadAloudState.Speaking -> r.id
            is ReadAloudState.Preparing -> r.id
            ReadAloudState.Idle -> null
        },
        onStopReading = vm::stopReading,
        candidates = state.candidates,
        onCandidate = if (state.characterChat) vm::selectCandidate else null,
        onEditInPlace = if (state.characterChat) vm::editInPlace else null,
        onTakeOverBrowser = state.conversationId?.let { id -> onOpenRoute?.let { open -> { open(BrowserCards.takeOverRoute(id)) } } },
    )

    val mic: @Composable () -> Unit = micSlot ?: {
        DictationButton(
            onInsert = { insertAtCursor(input, it) },
            onSend = { text ->
                if (vm.send(text)) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            },
            onOpenSettings = onOpenRoute?.let { open -> { open(VOICE_SETTINGS_ROUTE) } },
        )
    }

    val composerCallbacks = ComposerCallbacks(
        onSend = {
            if (vm.send(input.text.toString())) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                input.clearText()
            }
        },
        onStop = vm::stop,
        onCancelEdit = vm::cancelEdit,
        onRemoveAttachment = vm::removeAttachment,
        onPickImages = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onPickFiles = { filePicker.launch(arrayOf("*/*")) },
        onPickFolder = { folderPicker.launch(null) },
        onEnterPath = { showPath = true },
        onReasoningToggle = vm::setReasoningEnabled,
        onEffort = vm::setEffort,
        onRemoveQueued = vm::removeQueued,
        onSendNow = vm::sendNow,
    )

    val drawerContent: @Composable () -> Unit = {
        ConversationDrawer(
            rows = rows,
            selectedId = state.conversationId,
            query = query,
            onQuery = { vm.search.value = it },
            permissionsNeedAttention = permissionsNeedAttention,
            callbacks = drawerCallbacks,
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        val chat: @Composable () -> Unit = {
            ChatPane(
                state = state,
                attachments = attachments,
                editing = editing,
                input = input,
                actions = actions,
                composerCallbacks = composerCallbacks,
                micSlot = mic,
                restore = restore,
                subagents = subagents.size,
                subagentsRunning = subagents.any { it.status == atom.extend.subagent.SubagentStatus.Running },
                onOpenSubagents = { showSubagents = true },
                vm = vm,
                engineMissing = state.engine.name == null || state.engine.missing,
                accessibilityOff = !accessibilityConnected,
                showDrawerButton = !wide,
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onPickModel = pickModel,
                onOpenModels = onOpenModels,
                onOpenSetup = onOpenSetup,
                onOpenSettings = onOpenSettings,
                onOpenTerminal = onOpenTerminal,
                onOpenBrowser = onOpenBrowser,
                onNewChat = {
                    vm.newChat()
                    input.clearText()
                },
                onStats = { showStats = true },
                onExport = { state.conversationId?.let(::export) },
                onPick = { input.setTextAndPlaceCursorAtEnd(it) },
            )
        }
        if (wide) {
            Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                Box(Modifier.width(320.dp).fillMaxHeight().edgeLine()) { drawerContent() }
                Box(Modifier.weight(1f)) { chat() }
            }
        } else {
            ModalNavigationDrawer(
                drawerState = drawerState,
                scrimColor = Color.Black.copy(alpha = 0.6f),
                drawerContent = {
                    ModalDrawerSheet(
                        drawerContainerColor = MaterialTheme.colorScheme.background,
                        drawerShape = RectangleShape,
                        modifier = Modifier.widthIn(max = 320.dp).edgeLine(),
                    ) { drawerContent() }
                },
            ) { chat() }
        }
    }

    state.pending?.let { pending -> ApprovalDialog(pending, onAnswer = { vm.answer(pending.id, it) }) }
    if (showPicker) {
        ModelPickerSheet(
            selected = state.selection,
            onSelect = {
                vm.setModel(it)
                showPicker = false
            },
            onDismiss = { showPicker = false },
            onManage = {
                showPicker = false
                onOpenModels()
            },
        )
    }
    if (showSubagents) SubagentSheet(subagents, onDismiss = { showSubagents = false })
    if (showStats) StatsDialog(state, onCompact = { vm.compact() }, onDismiss = { showStats = false })
    if (showPath) {
        val field = rememberTextFieldState()
        AtomDialog(
            onDismissRequest = { showPath = false },
            icon = ChatGlyphs.Path,
            title = stringResource(R.string.attach_path_title),
            dismissLabel = stringResource(R.string.action_cancel),
            onDismiss = { showPath = false },
            confirmLabel = stringResource(R.string.attach_path_add),
            confirmEnabled = field.text.startsWith("/"),
            onConfirm = {
                if (vm.addPath(field.text.toString())) showPath = false
            },
        ) {
            Text(stringResource(R.string.attach_path_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            DialogField(field, "/sdcard/Download/report.pdf", mono = true)
        }
    }
}

/** Route of the voice feature's settings screen, opened from dictation errors. */
private const val VOICE_SETTINGS_ROUTE = "voice/settings"

/** Dictated text goes in at the cursor as an editable draft; existing text is never overwritten. */
private fun insertAtCursor(input: TextFieldState, text: String) {
    val clean = text.trim()
    if (clean.isEmpty()) return
    input.edit {
        val current = asCharSequence()
        val start = selection.min
        val end = selection.max
        val lead = if (start > 0 && !current[start - 1].isWhitespace()) " " else ""
        val trail = if (end < current.length && !current[end].isWhitespace()) " " else ""
        replace(start, end, lead + clean + trail)
    }
}

private fun Modifier.edgeLine(): Modifier = this.then(
    Modifier.drawWithContent {
        drawContent()
        val x = size.width - 0.5f
        drawLine(Color(0x22FFFFFF), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
    },
)

@Composable
private fun ChatPane(
    state: ChatUiState,
    attachments: List<atom.app.data.Attachment>,
    editing: EditState?,
    input: androidx.compose.foundation.text.input.TextFieldState,
    actions: MessageActions,
    composerCallbacks: ComposerCallbacks,
    micSlot: @Composable () -> Unit,
    restore: Pair<String, atom.app.data.ReadingPosition>?,
    subagents: Int,
    subagentsRunning: Boolean,
    onOpenSubagents: () -> Unit,
    vm: ChatViewModel,
    engineMissing: Boolean,
    accessibilityOff: Boolean,
    showDrawerButton: Boolean,
    onOpenDrawer: () -> Unit,
    onPickModel: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTerminal: (() -> Unit)?,
    onOpenBrowser: (() -> Unit)?,
    onNewChat: () -> Unit,
    onStats: () -> Unit,
    onExport: () -> Unit,
    onPick: (String) -> Unit,
) {
    val glass = rememberGlassState()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // Bars handle their own insets so the transcript can scroll underneath the glass.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            ChatTopBar(
                modifier = Modifier.glassBar(glass, GlassEdge.Top),
                state = state,
                showDrawerButton = showDrawerButton,
                onOpenDrawer = onOpenDrawer,
                onPickModel = onPickModel,
                onNewChat = onNewChat,
                onOpenSettings = onOpenSettings,
                onOpenTerminal = onOpenTerminal,
                onOpenBrowser = onOpenBrowser,
                onCompact = vm::compact,
                onStats = onStats,
                onExport = onExport,
            )
        },
        bottomBar = {
            Column(
                Modifier
                    .glassBar(glass, GlassEdge.Bottom)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (subagents > 0) SubagentChip(subagents, subagentsRunning, onOpenSubagents)
                Banners(state, onOpenModels = onOpenModels)
                Composer(input, state, attachments, editing, composerCallbacks, micSlot)
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().glassSource(glass)) {
            if (state.items.isEmpty() && !state.running) {
                Box(Modifier.padding(padding)) {
                    EmptyState(engineMissing, accessibilityOff, onPick, onPickModel, onOpenSetup)
                }
            } else {
                TranscriptList(
                    state = state,
                    bars = padding,
                    actions = actions,
                    restore = restore,
                    onRestored = vm::consumeRestore,
                    onSavePosition = vm::savePosition,
                    onPause = vm::pause,
                    onResume = vm::resume,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Top bar

@Composable
private fun ChatTopBar(
    modifier: Modifier,
    state: ChatUiState,
    showDrawerButton: Boolean,
    onOpenDrawer: () -> Unit,
    onPickModel: () -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTerminal: (() -> Unit)?,
    onOpenBrowser: (() -> Unit)?,
    onCompact: () -> Unit,
    onStats: () -> Unit,
    onExport: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val control = colors.surfaceContainerHigh.copy(alpha = 0.72f)
    var menu by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth().statusBarsPadding().height(60.dp).padding(horizontal = 12.dp)) {
        if (showDrawerButton) {
            CircleIconButton(ChatGlyphs.Sidebar, stringResource(R.string.cd_open_drawer), onOpenDrawer, modifier = Modifier.align(Alignment.CenterStart), container = control)
        }
        Box(Modifier.align(Alignment.Center).padding(horizontal = 96.dp)) {
            val title = state.title?.takeIf { it.isNotBlank() }
            if (title == null) {
                ModelCapsule(state.engine, onPickModel)
            } else {
                Column(
                    Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onPickModel).padding(horizontal = 10.dp, vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            state.engine.name ?: stringResource(R.string.engine_none),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.engine.missing) MaterialTheme.extras.warning else colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(14.dp), tint = colors.onSurfaceVariant)
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
            state.context?.let { ctx ->
                ContextRing(ctx, onStats)
                Spacer(Modifier.width(6.dp))
            }
            Box {
                CircleIconButton(Icons.Filled.MoreVert, stringResource(R.string.chat_cd_more), { menu = true }, container = control)
                AtomMenu(expanded = menu, onDismiss = { menu = false }) {
                    MenuAction(stringResource(R.string.drawer_new_chat), Icons.Filled.Add) {
                        menu = false
                        onNewChat()
                    }
                    onOpenTerminal?.let { open ->
                        MenuAction(stringResource(R.string.menu_open_terminal), AtomIcons.Terminal) {
                            menu = false
                            open()
                        }
                    }
                    onOpenBrowser?.let { open ->
                        MenuAction(stringResource(R.string.menu_open_browser), AtomIcons.Link) {
                            menu = false
                            open()
                        }
                    }
                    if (state.conversationId != null) {
                        MenuAction(stringResource(R.string.menu_compact), ChatGlyphs.Compress) {
                            menu = false
                            onCompact()
                        }
                        MenuAction(stringResource(R.string.menu_stats), ChatGlyphs.Stats) {
                            menu = false
                            onStats()
                        }
                        MenuAction(stringResource(R.string.drawer_export), ChatGlyphs.Export) {
                            menu = false
                            onExport()
                        }
                    }
                    MenuAction(stringResource(R.string.screen_settings), Icons.Filled.Settings) {
                        menu = false
                        onOpenSettings()
                    }
                }
            }
        }
    }
}

/** The model pill: provider glyph, model name, chevron. */
@Composable
private fun ModelCapsule(info: EngineInfo, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = colors.surfaceContainerHigh.copy(alpha = 0.72f), modifier = Modifier.height(36.dp).widthIn(max = 240.dp)) {
        Row(Modifier.padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (info.onDevice) AtomIcons.Chip else AtomIcons.Cloud,
                stringResource(if (info.onDevice) R.string.badge_on_device else R.string.tab_cloud),
                Modifier.size(15.dp),
                tint = if (info.missing) MaterialTheme.extras.warning else colors.onSurface.copy(alpha = 0.85f),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                info.name ?: stringResource(R.string.engine_none),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(2.dp))
            Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
        }
    }
}

/** Context usage as a thin ring; tapping it opens the session stats. */
@Composable
private fun ContextRing(ctx: ContextInfo, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val extras = MaterialTheme.extras
    val fraction = ctx.fraction ?: 0f
    val arc = when {
        fraction >= 0.85f -> colors.error
        fraction >= 0.7f -> extras.warning
        else -> colors.onSurface
    }
    val track = colors.surfaceContainerHighest
    val description = stringResource(R.string.context_ring_cd, (fraction * 100).toInt())
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = colors.surfaceContainerHigh.copy(alpha = 0.72f),
        modifier = Modifier.size(36.dp).semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(20.dp)) {
                val stroke = 2.5.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                drawArc(arc, -90f, 360f * fraction, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
    }
}

// ---------------------------------------------------------------- Banners

@Composable
private fun Banners(state: ChatUiState, onOpenModels: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var dismissedNotice by remember(state.notice) { mutableStateOf(false) }
    state.notice?.takeIf { !dismissedNotice }?.let { notice ->
        QuietBanner(AtomIcons.Bolt, MaterialTheme.extras.warning, notice, onDismiss = { dismissedNotice = true })
    }
    val failure = (state.items.lastOrNull() as? atom.app.agent.ChatItem.Notice)?.takeIf { it.error && !state.running }
    var dismissedError by remember(failure?.key) { mutableStateOf(false) }
    if (failure != null && !dismissedError) {
        QuietBanner(
            Icons.Filled.Warning,
            colors.error,
            failure.text,
            onDismiss = { dismissedError = true },
            action = stringResource(R.string.screen_models),
            onAction = onOpenModels,
        )
    }
}

@Composable
private fun QuietBanner(icon: ImageVector, tint: Color, text: String, onDismiss: (() -> Unit)?, action: String? = null, onAction: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainer.copy(alpha = 0.92f), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = 44.dp).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(16.dp), tint = tint)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = colors.onSurface, modifier = Modifier.weight(1f).padding(vertical = 6.dp), maxLines = 4, overflow = TextOverflow.Ellipsis)
            if (action != null && onAction != null) {
                TextButton(onClick = onAction) {
                    Text(action, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = colors.primary)
                }
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Close, stringResource(R.string.cd_dismiss), Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Empty state

private class Suggestion(val icon: ImageVector, val text: String)

@Composable
private fun EmptyState(engineMissing: Boolean, accessibilityOff: Boolean, onPick: (String) -> Unit, onOpenModels: () -> Unit, onOpenSetup: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val suggestions = listOf(
        Suggestion(AtomIcons.Eye, stringResource(R.string.chat_suggest_screen)),
        Suggestion(AtomIcons.Touch, stringResource(R.string.chat_suggest_message)),
        Suggestion(AtomIcons.Link, stringResource(R.string.chat_suggest_web)),
        Suggestion(Icons.Filled.Info, stringResource(R.string.chat_suggest_memory)),
    )
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            GlowingMark()
            Text(
                stringResource(R.string.chat_empty_title),
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Light),
                color = colors.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.empty_subtitle), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 300.dp))
            Spacer(Modifier.height(36.dp))
            if (engineMissing || accessibilityOff) {
                Group {
                    if (engineMissing) NudgeRow(AtomIcons.Chip, stringResource(R.string.chat_configure_model), stringResource(R.string.empty_choose_model), onOpenModels)
                    if (engineMissing && accessibilityOff) GroupDivider(inset = 48.dp)
                    if (accessibilityOff) NudgeRow(AtomIcons.Eye, stringResource(R.string.empty_no_accessibility), stringResource(R.string.empty_open_setup), onOpenSetup)
                }
                Spacer(Modifier.height(12.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                suggestions.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { s -> SuggestionCard(s, onPick, Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GlowingMark() {
    val glow = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier.size(160.dp).drawWithCache {
            val brush = Brush.radialGradient(
                colorStops = arrayOf(0f to glow.copy(alpha = 0.10f), 0.45f to glow.copy(alpha = 0.035f), 1f to Color.Transparent),
                center = Offset(size.width / 2f, size.height / 2f),
                radius = size.minDimension / 2f,
            )
            onDrawBehind { drawCircle(brush) }
        },
        contentAlignment = Alignment.Center,
    ) {
        AtomMark(Modifier.size(64.dp))
    }
}

@Composable
private fun SuggestionCard(s: Suggestion, onPick: (String) -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = { onPick(s.text) }, color = colors.surfaceContainer, shape = RoundedCornerShape(26.dp), modifier = modifier) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 16.dp)) {
            Box(Modifier.size(32.dp).background(colors.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.Center) {
                Icon(s.icon, null, Modifier.size(16.dp), tint = colors.onSurface.copy(alpha = 0.85f))
            }
            Spacer(Modifier.height(14.dp))
            Text(s.text, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun NudgeRow(icon: ImageVector, text: String, action: String, onAction: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface, modifier = Modifier.weight(1f))
        TextButton(onClick = onAction) {
            Text(action, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = colors.primary)
        }
    }
}

// ---------------------------------------------------------------- Approval

@Composable
private fun ApprovalDialog(pending: PendingApproval, onAnswer: (Boolean) -> Unit) {
    val remaining = rememberCountdown(pending.expiresAtMillis)
    val colors = MaterialTheme.colorScheme
    AtomDialog(
        onDismissRequest = {},
        icon = Icons.Filled.Lock,
        title = stringResource(R.string.confirm_title),
        dismissLabel = stringResource(R.string.confirm_deny),
        onDismiss = { onAnswer(false) },
        confirmLabel = stringResource(R.string.confirm_allow),
        onConfirm = { onAnswer(true) },
    ) {
        Text(stringResource(R.string.confirm_text), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier.fillMaxWidth().heightIn(max = 220.dp).background(colors.surfaceContainer, RoundedCornerShape(16.dp)).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(pending.action, style = MaterialTheme.extras.mono, color = colors.onSurface)
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.confirm_countdown, remaining), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
    }
}
