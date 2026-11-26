@file:OptIn(ExperimentalLayoutApi::class)

package atom.app.ui.chat

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.MutatorMutex
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import atom.app.R
import atom.app.agent.CompactResult
import atom.app.agent.PendingApproval
import atom.app.ui.common.logoForKey
import atom.app.ui.models.ModelPickerSheet
import atom.characters.model.Character
import atom.characters.ui.CharacterAvatar
import atom.device.Accessibility
import atom.ui.components.AtomMenu
import atom.ui.components.GlassEdge
import atom.ui.components.glassBand
import atom.ui.components.glassPane
import atom.ui.components.glassSource
import atom.ui.components.rememberGlassChrome
import atom.ui.components.rememberGlassState
import atom.ui.theme.AtomEmblem
import atom.ui.theme.AtomIcons
import atom.ui.theme.extras
import atom.voice.speech.Speech
import atom.voice.tts.ReadAloud
import atom.voice.tts.ReadAloudState
import atom.voice.ui.DictationButton
import atom.web.browser.BrowserCards
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The home chat: a pure page with round glass buttons and a model capsule floating over the transcript, the
 * composer floating at the bottom over a fade, and the conversation list as a push drawer beside it.
 */

/**
 * The home chat. The model capsule opens the per-conversation model picker ([onPickModel] replaces it).
 * [micSlot] replaces the default dictation button. [onOpenRoute] opens feature routes (Tools, Skills, Scheduled
 * Tasks, voice settings, the browser take-over); without it those links are hidden.
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
    val speech by Speech.config.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }
    var showSubagents by remember { mutableStateOf(false) }
    var needsModel by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val copy = rememberCopier()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val drawer = remember { PushDrawerState() }
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
                ChatEvent.NeedsModel -> {
                    needsModel = true
                    null
                }
                is ChatEvent.Toast -> when {
                    e.arg != null && e.arg2 != null -> context.getString(e.message, e.arg, e.arg2)
                    e.arg != null -> context.getString(e.message, e.arg)
                    else -> context.getString(e.message)
                }
                is ChatEvent.Compacted -> when (val r = e.result) {
                    is CompactResult.Done -> context.getString(R.string.compact_done, r.summarized)
                    CompactResult.NothingToCompact -> context.getString(R.string.compact_nothing)
                    CompactResult.Busy -> context.getString(R.string.compact_busy)
                    is CompactResult.Failed -> context.getString(R.string.compact_failed, r.message)
                }
            }
            if (text != null) Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }
    // The "configure a model" banner goes away by itself once a usable model is chosen.
    LaunchedEffect(state.engine.ready) { if (state.engine.ready) needsModel = false }

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
    fun closeDrawer() {
        scope.launch { drawer.animateTo(false) }
    }
    fun closeThen(action: () -> Unit): () -> Unit = {
        closeDrawer()
        action()
    }

    val drawerCallbacks = DrawerCallbacks(
        onNewChat = {
            vm.newChat()
            input.clearText()
            closeDrawer()
        },
        onOpen = {
            vm.open(it)
            closeDrawer()
        },
        onPin = vm::togglePin,
        onRename = vm::rename,
        onDelete = vm::delete,
        onExport = ::export,
        onModels = closeThen(onOpenModels),
        onCharacters = onOpenCharacters?.let { closeThen(it) },
        onTools = onOpenRoute?.let { open -> closeThen { open(ROUTE_TOOLS) } },
        onSkills = onOpenRoute?.let { open -> closeThen { open(ROUTE_SKILLS) } },
        onScheduled = onOpenRoute?.let { open -> closeThen { open(ROUTE_SCHEDULED) } },
        onPermissions = closeThen(permissions),
        onSettings = closeThen(onOpenSettings),
    )

    val actions = MessageActions(
        onCopy = {
            copy(it)
            // Android 13+ confirms clipboard writes itself; older versions get a toast.
            if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.S_V2) {
                Toast.makeText(context, R.string.chat_copied, Toast.LENGTH_SHORT).show()
            }
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
        onStats = { showStats = true },
        stats = state.stats,
        editingIndex = editing?.index,
        onContinueEditing = { path -> vm.reattachImage(context, path) },
        conversationId = state.conversationId,
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
        onStats = { showStats = true },
    )

    val drawerContent: @Composable () -> Unit = {
        ConversationDrawer(
            rows = rows,
            selectedId = state.conversationId,
            query = query,
            onQuery = { vm.search.value = it },
            permissionsNeedAttention = permissionsNeedAttention,
            activeModel = engineLabel(state.engine),
            open = drawer.target,
            callbacks = drawerCallbacks,
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val wide = maxWidth >= 840.dp
        val chat: @Composable () -> Unit = {
            ChatPane(
                state = state,
                attachments = attachments,
                editing = editing,
                editReplacesLater = editing?.let { vm.laterTurns(it.index) > 0 } == true,
                input = input,
                actions = actions,
                composerCallbacks = composerCallbacks,
                micSlot = mic,
                restore = restore,
                subagents = subagents.size,
                subagentsRunning = subagents.any { it.status == atom.extend.subagent.SubagentStatus.Running },
                onOpenSubagents = { showSubagents = true },
                vm = vm,
                accessibilityOff = !accessibilityConnected,
                needsModel = needsModel,
                onDismissNeedsModel = { needsModel = false },
                autoReadAloud = if (speech.ttsConfigured) speech.autoReadAloud else null,
                onToggleAutoRead = { on -> Speech.update { it.copy(autoReadAloud = on) } },
                showDrawerButton = !wide,
                onOpenDrawer = { scope.launch { drawer.animateTo(true) } },
                onPickModel = pickModel,
                onOpenModels = onOpenModels,
                onOpenSetup = onOpenSetup,
                onOpenSettings = onOpenSettings,
                onOpenTerminal = onOpenTerminal,
                onOpenBrowser = onOpenBrowser,
                onOpenCharacter = state.character?.let { c -> onOpenRoute?.let { open -> { open(atom.characters.ui.CharacterRoutes.detail(c.id)) } } },
                onNewChat = {
                    vm.newChat()
                    input.clearText()
                },
                onStats = { showStats = true },
                onExport = { state.conversationId?.let(::export) },
                onSuggestion = { prompt ->
                    if (vm.send(prompt)) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                },
            )
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(320.dp).fillMaxHeight().background(paneColor()).edgeLine()) { drawerContent() }
                Box(Modifier.weight(1f)) { chat() }
            }
        } else {
            PushDrawer(drawer, pane = drawerContent, content = chat)
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
            DialogField(field, stringResource(R.string.attach_path_hint), mono = true)
        }
    }
}

/** Route of the voice feature's settings screen, opened from dictation errors. */
private const val VOICE_SETTINGS_ROUTE = "voice/settings"
private const val ROUTE_TOOLS = "device/tools"
private const val ROUTE_SKILLS = "extend/skills"
private const val ROUTE_SCHEDULED = "automation/tasks"

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

/** The drawer pane: in dark mode lifted a little from the black chat (45% towards the card colour). */
@Composable
internal fun paneColor(): Color {
    val c = MaterialTheme.colorScheme
    return if (homeInk().dark) lerp(c.surface, c.surfaceContainer, 0.45f) else c.surface
}

@Composable
private fun Modifier.edgeLine(): Modifier {
    val line = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)
    return this.then(
        Modifier.drawWithContent {
            drawContent()
            val x = size.width - 0.5f
            drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5f)
        },
    )
}

// ---------------------------------------------------------------- Push drawer

/** The side pane and the chat sit side by side and slide together; [target] is where it is heading. */
@Stable
internal class PushDrawerState {
    var offset by mutableFloatStateOf(0f)
        private set
    var width by mutableFloatStateOf(0f)
        internal set
    var target by mutableStateOf(false)
        private set
    private val mutex = MutatorMutex()

    val progress: Float get() = if (width <= 0f) 0f else (offset / width).coerceIn(0f, 1f)

    /** Fully open and not moving. */
    val settledOpen: Boolean get() = target && width > 0f && offset >= width - 0.5f

    /** Settles with a critically damped spring, as the old pane did. */
    suspend fun animateTo(open: Boolean, velocity: Float = 0f) {
        target = open
        mutex.mutate {
            animate(offset, if (open) width else 0f, velocity, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 146f)) { v, _ -> offset = v }
        }
    }

    suspend fun stop() = mutex.mutate(MutatePriority.UserInput) {}

    fun dragBy(delta: Float) {
        offset = (offset + delta).coerceIn(0f, width)
    }

    suspend fun settle(velocity: Float) {
        val open = when {
            velocity > 900f -> true
            velocity < -900f -> false
            else -> progress >= 0.5f
        }
        animateTo(open, velocity)
    }

    fun clamp() {
        offset = if (target) width else offset.coerceIn(0f, width)
    }
}

/** Share of the pane width the chat moves by when the pane is glass (the rest of the pane overlaps the chat). */
private const val GLASS_PUSH = 0.3f

@Composable
private fun PushDrawer(state: PushDrawerState, pane: @Composable () -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val dir = if (rtl) -1f else 1f
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    // With blur on, the pane is frosted glass over the chat: the chat moves only part of the way and shows through,
    // blurred. Without blur the pane is solid and pushes the chat all the way, as before. The blur itself is drawn
    // only once the pane has settled open: while it slides the pane is solid, so nothing re-blurs every frame.
    val glass = rememberGlassChrome().blur
    val chatGlass = rememberGlassState()
    val push = if (glass) GLASS_PUSH else 1f
    BackHandler(enabled = state.target) { scope.launch { state.animateTo(false) } }
    LaunchedEffect(state.target) {
        if (state.target) {
            keyboard?.hide()
            focus.clearFocus()
        }
    }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .draggable(
                state = rememberDraggableState { delta -> state.dragBy(delta * dir) },
                orientation = Orientation.Horizontal,
                onDragStarted = { state.stop() },
                onDragStopped = { velocity -> state.settle(velocity * dir) },
            ),
    ) {
        val paneWidth = min(maxWidth * 0.84f, 340.dp)
        val widthPx = with(density) { paneWidth.toPx() }
        SideEffect {
            if (state.width != widthPx) {
                state.width = widthPx
                state.clamp()
            }
        }
        // Chat page, pushed right by the pane.
        Box(Modifier.fillMaxSize().offset { IntOffset((state.offset * push).roundToInt(), 0) }) {
            Box(Modifier.fillMaxSize().then(if (glass) Modifier.glassSource(chatGlass) else Modifier)) { content() }
            val progress = state.progress
            if (progress > 0f) {
                val closeLabel = stringResource(R.string.home_close_drawer)
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = state.progress }
                        .background(Color.Black.copy(alpha = 0.16f))
                        .pointerInput(Unit) { detectTapGestures { scope.launch { state.animateTo(false) } } }
                        .semantics {
                            contentDescription = closeLabel
                            onClick(label = closeLabel) {
                                scope.launch { state.animateTo(false) }
                                true
                            }
                        },
                )
            }
        }
        // The pane slides in from the start edge by the same amount. Fully closed, it is not composed at all, so
        // screen readers and keyboard focus never land on hidden controls (the list and search live in the
        // view model and come back as they were).
        if (state.offset > 0f || state.target) {
            Box(
                Modifier
                    .width(paneWidth)
                    .fillMaxHeight()
                    .offset { IntOffset((state.offset - widthPx).roundToInt(), 0) }
                    .glassPane(chatGlass, paneColor(), glass && state.settledOpen)
                    .edgeLine(),
            ) { pane() }
        }
    }
}

// ---------------------------------------------------------------- Chat pane

@Composable
private fun ChatPane(
    state: ChatUiState,
    attachments: List<atom.app.data.Attachment>,
    editing: EditState?,
    editReplacesLater: Boolean,
    input: TextFieldState,
    actions: MessageActions,
    composerCallbacks: ComposerCallbacks,
    micSlot: @Composable () -> Unit,
    restore: Pair<String, atom.app.data.ReadingPosition>?,
    subagents: Int,
    subagentsRunning: Boolean,
    onOpenSubagents: () -> Unit,
    vm: ChatViewModel,
    accessibilityOff: Boolean,
    needsModel: Boolean,
    onDismissNeedsModel: () -> Unit,
    autoReadAloud: Boolean?,
    onToggleAutoRead: (Boolean) -> Unit,
    showDrawerButton: Boolean,
    onOpenDrawer: () -> Unit,
    onPickModel: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTerminal: (() -> Unit)?,
    onOpenBrowser: (() -> Unit)?,
    onOpenCharacter: (() -> Unit)?,
    onNewChat: () -> Unit,
    onStats: () -> Unit,
    onExport: () -> Unit,
    onSuggestion: (String) -> Unit,
) {
    val density = LocalDensity.current
    val bg = MaterialTheme.colorScheme.background
    var topPx by remember { mutableIntStateOf(0) }
    var bottomPx by remember { mutableIntStateOf(0) }
    val bars = with(density) { PaddingValues(top = topPx.toDp(), bottom = bottomPx.toDp()) }
    // The transcript is the glass source; the bands blur it only while it actually sits under them.
    val glass = rememberGlassState()
    val cover = remember { BarCover() }
    Box(Modifier.fillMaxSize().background(bg)) {
        when {
            state.loadFailed -> LoadFailed(bars, onRetry = vm::retryLoad)
            state.items.isEmpty() && !state.running -> EmptyState(
                bars = bars,
                character = state.character.takeIf { state.characterChat },
                accessibilityOff = accessibilityOff,
                onSuggestion = onSuggestion,
                onOpenSetup = onOpenSetup,
            )
            else -> TranscriptList(
                state = state,
                bars = bars,
                glass = glass,
                cover = cover,
                actions = actions,
                restore = restore,
                onRestored = vm::consumeRestore,
                onSavePosition = vm::savePosition,
                onPause = vm::pause,
                onResume = vm::resume,
            )
        }
        // Frosted band behind the top bar: status bar + the 60dp bar + a fading tail under the buttons. It takes no
        // input, so taps in the tail still reach the transcript.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .glassBand(glass, GlassEdge.Top, covered = cover.top, fade = TOP_BAND_FADE)
                .statusBarsPadding()
                .height(TOP_BAR_HEIGHT + TOP_BAND_TAIL),
        )
        ChatTopBar(
            modifier = Modifier.align(Alignment.TopCenter).onSizeChanged { topPx = it.height },
            state = state,
            showDrawerButton = showDrawerButton,
            autoReadAloud = autoReadAloud,
            onToggleAutoRead = onToggleAutoRead,
            onOpenDrawer = onOpenDrawer,
            onPickModel = onPickModel,
            onOpenCharacter = onOpenCharacter,
            onNewChat = onNewChat,
            onOpenSettings = onOpenSettings,
            onOpenTerminal = onOpenTerminal,
            onOpenBrowser = onOpenBrowser,
            onCompact = vm::compact,
            onStats = onStats,
            onExport = onExport,
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { bottomPx = it.height }
                .glassBand(glass, GlassEdge.Bottom, covered = cover.bottom, fade = BOTTOM_BAND_FADE)
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (subagents > 0) SubagentChip(subagents, subagentsRunning, onOpenSubagents)
            Banners(state, needsModel, onDismissNeedsModel, onOpenModels = onOpenModels, onContinue = vm::continueRun)
            Composer(input, state, attachments, editing, editReplacesLater, composerCallbacks, micSlot)
        }
    }
}

@Composable
private fun LoadFailed(bars: PaddingValues, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(bars), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.chat_load_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onRetry).padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

// ---------------------------------------------------------------- Top bar

private val TOP_BAR_HEIGHT = 60.dp

/** How far the top band reaches below the bar, so it can fade out under the buttons instead of ending at them. */
private val TOP_BAND_TAIL = 16.dp

/** The top band fades from below the 44dp buttons (8dp above the bar's end) through the tail. */
private val TOP_BAND_FADE = TOP_BAND_TAIL + 8.dp

/**
 * The bottom band fades in over the composer area's 14dp top padding and on beside the (opaque) composer, so text
 * in the side gutters melts away too.
 */
private val BOTTOM_BAND_FADE = 30.dp

@Composable
private fun ChatTopBar(
    modifier: Modifier,
    state: ChatUiState,
    showDrawerButton: Boolean,
    autoReadAloud: Boolean?,
    onToggleAutoRead: (Boolean) -> Unit,
    onOpenDrawer: () -> Unit,
    onPickModel: () -> Unit,
    onOpenCharacter: (() -> Unit)?,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTerminal: (() -> Unit)?,
    onOpenBrowser: (() -> Unit)?,
    onCompact: () -> Unit,
    onStats: () -> Unit,
    onExport: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val capsuleEnabled = !state.running && state.hasProviders
    // The frosted band behind this bar is drawn by ChatPane (glassBand), so the bar itself stays transparent.
    Box(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(TOP_BAR_HEIGHT)
            .padding(horizontal = 12.dp)
            // Taps on empty bar space do not fall through to the transcript.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        if (showDrawerButton) {
            GlassIconButton(HomeGlyphs.Sidebar, stringResource(R.string.cd_open_drawer), onOpenDrawer, modifier = Modifier.align(Alignment.CenterStart))
        }
        Box(Modifier.align(Alignment.Center).padding(horizontal = 52.dp), contentAlignment = Alignment.Center) {
            val title = state.title?.takeIf { it.isNotBlank() }
            if (title == null) {
                ModelCapsule(state.engine, capsuleEnabled, onPickModel)
            } else {
                TitleBlock(title, state.engine, state.character.takeIf { state.characterChat }, capsuleEnabled, onPickModel, onOpenCharacter)
            }
        }
        Box(Modifier.align(Alignment.CenterEnd)) {
            GlassIconButton(Icons.Filled.MoreVert, stringResource(R.string.chat_cd_more), { menu = true })
            AtomMenu(expanded = menu, onDismiss = { menu = false }) {
                MenuAction(stringResource(R.string.drawer_new_chat), HomeGlyphs.NewMessage) {
                    menu = false
                    onNewChat()
                }
                onOpenTerminal?.let { open ->
                    MenuAction(stringResource(R.string.menu_open_terminal), Icons.Outlined.Terminal) {
                        menu = false
                        open()
                    }
                }
                onOpenBrowser?.let { open ->
                    MenuAction(stringResource(R.string.menu_open_browser), Icons.Outlined.Language) {
                        menu = false
                        open()
                    }
                }
                if (autoReadAloud != null) {
                    MenuAction(
                        stringResource(if (autoReadAloud) R.string.menu_auto_read_off else R.string.menu_auto_read_on),
                        if (autoReadAloud) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                    ) {
                        menu = false
                        onToggleAutoRead(!autoReadAloud)
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

/** The provider logo, or a server glyph on an accent disc when there is none (or nothing usable is selected). */
@Composable
private fun BrandMark(info: EngineInfo, size: androidx.compose.ui.unit.Dp) {
    val ink = homeInk()
    val res = if (info.ready || info.live) logoForKey(info.logo, ink.dark) else null
    if (res != null) {
        Image(painterResource(res), contentDescription = null, modifier = Modifier.size(size))
    } else {
        val colors = MaterialTheme.colorScheme
        Box(Modifier.size(size).background(colors.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Dns, null, Modifier.size(size * 0.56f), tint = colors.onPrimaryContainer)
        }
    }
}

/** The model capsule: brand logo, model name ("Not selected" without a usable model) and a chevron. */
@Composable
private fun ModelCapsule(info: EngineInfo, enabled: Boolean, onClick: () -> Unit) {
    val ink = homeInk()
    val shape = RoundedCornerShape(20.dp)
    val description = stringResource(R.string.home_choose_model)
    Row(
        Modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(ink.capsule)
            .border(0.5.dp, ink.glassEdge, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(start = 13.dp, end = if (enabled) 10.dp else 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandMark(info, 15.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            capsuleLabel(info),
            fontSize = 14.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 180.dp),
        )
        if (enabled) {
            Spacer(Modifier.width(2.dp))
            Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(16.dp), tint = ink.chevron)
        }
    }
}

@Composable
private fun capsuleLabel(info: EngineInfo): String = engineLabel(info)

/** The capsule's and the drawer's model label: the same [ModelStatus] that Settings shows. */
@Composable
internal fun engineLabel(info: EngineInfo): String =
    if (info.live) info.name.orEmpty() else info.status.label(LocalContext.current)

/** The send-blocked banner: the specific reason when there is one. */
@Composable
private fun needsModelText(info: EngineInfo): String {
    val provider = info.providerName
    return when {
        info.reason == NotReady.SignIn && provider != null -> stringResource(R.string.home_sign_in_needed, provider)
        info.reason == NotReady.KeyUnreadable && provider != null -> stringResource(R.string.mdl_route_key_unreadable, provider)
        info.reason == NotReady.NeedsPack -> stringResource(R.string.route_needs_pack)
        else -> stringResource(R.string.chat_configure_model)
    }
}

/** A titled conversation: the title over a small model line; character chats add the avatar on the left. */
@Composable
private fun TitleBlock(
    title: String,
    info: EngineInfo,
    character: Character?,
    enabled: Boolean,
    onPickModel: () -> Unit,
    onOpenCharacter: (() -> Unit)?,
) {
    val ink = homeInk()
    val description = stringResource(R.string.home_choose_model)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (character != null) {
            val ring = if (ink.dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f)
            val open = stringResource(R.string.home_open_character)
            CharacterAvatar(
                character,
                Modifier
                    .size(28.dp)
                    .border(0.5.dp, ring, CircleShape)
                    .clip(CircleShape)
                    .then(if (onOpenCharacter != null) Modifier.clickable(onClick = onOpenCharacter).semantics { contentDescription = open } else Modifier),
            )
            Spacer(Modifier.width(8.dp))
        }
        Column(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = enabled, onClick = onPickModel)
                .semantics { contentDescription = description }
                .padding(horizontal = 10.dp, vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                title,
                fontSize = 15.5.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandMark(info, 11.dp)
                Spacer(Modifier.width(4.dp))
                Text(
                    capsuleLabel(info),
                    fontSize = 11.5.sp,
                    lineHeight = 14.sp,
                    color = if (info.missing) MaterialTheme.extras.warning else ink.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 168.dp),
                )
                if (enabled) Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(13.dp), tint = ink.chevron)
            }
        }
    }
}

// ---------------------------------------------------------------- Banners

@Composable
private fun Banners(
    state: ChatUiState,
    needsModel: Boolean,
    onDismissNeedsModel: () -> Unit,
    onOpenModels: () -> Unit,
    onContinue: (prompt: String) -> Boolean,
) {
    val colors = MaterialTheme.colorScheme
    if (needsModel) {
        QuietBanner(
            AtomIcons.Chip,
            colors.primary,
            needsModelText(state.engine),
            onDismiss = onDismissNeedsModel,
            action = stringResource(R.string.screen_models),
            onAction = {
                onDismissNeedsModel()
                onOpenModels()
            },
        )
    }
    val importNotice by atom.app.data.legacy.LegacyImport.notice.collectAsStateWithLifecycle()
    val context = LocalContext.current
    importNotice?.let { text ->
        QuietBanner(AtomIcons.Download, colors.primary, text, onDismiss = { atom.app.data.legacy.LegacyImport.dismissNotice(context) }, maxLines = 10)
    }
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
    // The last run stopped at its step limit: offer to carry on in a new run of the same conversation.
    val stopTexts = setOf(stringResource(R.string.run_step_limit), stringResource(R.string.run_stuck))
    val stepLimit = (state.items.lastOrNull() as? atom.app.agent.ChatItem.Notice)?.takeIf { !it.error && !state.running && it.text in stopTexts }
    var dismissedStepLimit by remember(stepLimit?.key) { mutableStateOf(false) }
    if (stepLimit != null && !dismissedStepLimit) {
        val prompt = stringResource(R.string.run_continue_prompt)
        QuietBanner(
            AtomIcons.Bolt,
            colors.primary,
            stringResource(if (stepLimit.text == stopTexts.first()) R.string.run_step_limit_banner else R.string.run_stuck_banner),
            onDismiss = { dismissedStepLimit = true },
            action = stringResource(R.string.run_continue),
            onAction = { if (onContinue(prompt)) dismissedStepLimit = true },
        )
    }
}

@Composable
private fun QuietBanner(
    icon: ImageVector,
    tint: Color,
    text: String,
    onDismiss: (() -> Unit)?,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    maxLines: Int = 4,
) {
    val colors = MaterialTheme.colorScheme
    val ink = homeInk()
    val shape = RoundedCornerShape(16.dp)
    Surface(color = ink.solidInk(0.08f, 0.04f), shape = shape, modifier = Modifier.fillMaxWidth().border(0.5.dp, ink.cardBorder, shape)) {
        Row(Modifier.heightIn(min = 44.dp).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(16.dp), tint = tint)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = colors.onSurface, modifier = Modifier.weight(1f).padding(vertical = 6.dp), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
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

private class Suggestion(val icon: ImageVector, val title: Int, val prompt: Int)

private val SUGGESTIONS = listOf(
    Suggestion(Icons.Rounded.DocumentScanner, R.string.chat_suggest_screen, R.string.chat_suggest_screen_prompt),
    Suggestion(Icons.Rounded.RocketLaunch, R.string.chat_suggest_message, R.string.chat_suggest_message_prompt),
    Suggestion(Icons.Rounded.Language, R.string.chat_suggest_web, R.string.chat_suggest_web_prompt),
    Suggestion(Icons.Rounded.Terminal, R.string.chat_suggest_memory, R.string.chat_suggest_memory_prompt),
)

@Composable
private fun EmptyState(
    bars: PaddingValues,
    character: Character?,
    accessibilityOff: Boolean,
    onSuggestion: (String) -> Unit,
    onOpenSetup: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val keyboardOpen = WindowInsets.isImeVisible
    // Centred between the top bar and the composer, raised a little (56dp) like the old home.
    BoxWithConstraints(Modifier.fillMaxSize().padding(top = bars.calculateTopPadding(), bottom = bars.calculateBottomPadding())) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (character != null) {
                CharacterEmpty(character)
            } else {
                HomeEmpty(keyboardOpen, accessibilityOff, onSuggestion, onOpenSetup)
            }
        }
    }
}

@Composable
private fun CharacterEmpty(character: Character) {
    val colors = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CharacterAvatar(character, Modifier.size(68.dp).clip(CircleShape))
        Spacer(Modifier.height(14.dp))
        Text(
            character.displayName,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.2).sp,
            color = colors.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.home_story_begins), fontSize = 13.sp, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun HomeEmpty(keyboardOpen: Boolean, accessibilityOff: Boolean, onSuggestion: (String) -> Unit, onOpenSetup: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // The emblem stays out of the way while typing, when vertical space is short.
            if (!keyboardOpen) {
                AtomEmblem(76.dp)
                Spacer(Modifier.height(18.dp))
            }
            Text(
                stringResource(R.string.chat_empty_title),
                fontSize = 22.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-0.2).sp,
                color = colors.onSurface,
                textAlign = TextAlign.Center,
            )
            AnimatedVisibility(
                visible = !keyboardOpen,
                enter = fadeIn(tween(220)) + slideInVertically(spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)) { it / 3 },
                exit = fadeOut(tween(130)) + slideOutVertically(tween(180)) { it / 4 },
            ) {
                Column {
                    Spacer(Modifier.height(26.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SUGGESTIONS.chunked(2).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { s ->
                                    val prompt = stringResource(s.prompt)
                                    SuggestionCard(s.icon, stringResource(s.title), Modifier.weight(1f)) { onSuggestion(prompt) }
                                }
                            }
                        }
                        if (accessibilityOff) AccessibilityNudge(onOpenSetup)
                    }
                }
            }
    }
}

/** 15dp card on a faint ink fill with a hairline; an accent-tinted icon disc and one line of text. */
@Composable
private fun SuggestionCard(icon: ImageVector, title: String, modifier: Modifier, onClick: () -> Unit) {
    val ink = homeInk()
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(15.dp)
    Row(
        modifier
            .clip(shape)
            .background(ink.cardFill)
            .border(0.5.dp, ink.cardBorder, shape)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).background(ink.accent(0.16f, 0.10f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(15.dp), tint = colors.primary)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            fontSize = 13.5.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Normal,
            letterSpacing = (-0.15).sp,
            color = colors.onSurface.copy(alpha = 0.88f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Shown under the cards only while the accessibility service is off; opens setup. */
@Composable
private fun AccessibilityNudge(onOpenSetup: () -> Unit) {
    val ink = homeInk()
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(15.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ink.cardFill)
            .border(0.5.dp, ink.cardBorder, shape)
            .clickable(onClick = onOpenSetup)
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).background(ink.accent(0.16f, 0.10f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(AtomIcons.Eye, null, Modifier.size(15.dp), tint = colors.primary)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.empty_no_accessibility),
            fontSize = 13.5.sp,
            lineHeight = 18.sp,
            color = colors.onSurface.copy(alpha = 0.88f),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onOpenSetup) {
            Text(stringResource(R.string.empty_open_setup), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = colors.primary)
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
