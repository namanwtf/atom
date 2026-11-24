@file:OptIn(ExperimentalMaterial3Api::class)

package atom.app.ui.chat

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import atom.app.R
import atom.app.agent.PendingApproval
import atom.app.agent.StepStatus
import atom.app.agent.TaskPhase
import atom.app.agent.TranscriptItem
import atom.app.data.ConversationSummary
import atom.app.ui.common.Pill
import atom.app.ui.common.formatDuration
import atom.app.ui.common.formatTokens
import atom.app.ui.common.humanizeTool
import atom.app.ui.theme.AtomIcons
import atom.app.ui.theme.AtomMark
import atom.app.ui.theme.extras
import atom.core.tool.Risk
import atom.device.Accessibility
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSetup: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val summaries by vm.summaries.collectAsStateWithLifecycle()
    val accessibilityConnected by Accessibility.connected.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val input = rememberTextFieldState()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ConversationDrawer(
                summaries = summaries,
                selectedId = state.conversationId,
                runningElsewhere = state.busyElsewhere,
                onNewChat = {
                    vm.newChat()
                    scope.launch { drawerState.close() }
                },
                onOpen = {
                    vm.open(it)
                    scope.launch { drawerState.close() }
                },
                onDelete = vm::delete,
                onModels = {
                    scope.launch { drawerState.close() }
                    onOpenModels()
                },
                onSettings = {
                    scope.launch { drawerState.close() }
                    onOpenSettings()
                },
            )
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.cd_open_drawer))
                        }
                    },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AtomMark(Modifier.size(22.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = state.title ?: stringResource(R.string.app_name),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    },
                    actions = {
                        EngineChip(state.engine, onClick = onOpenModels)
                        Spacer(Modifier.width(8.dp))
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding(),
            ) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (state.items.isEmpty() && !state.running) {
                        EmptyState(
                            engineMissing = state.engine == EngineInfo.None,
                            accessibilityOff = !accessibilityConnected,
                            onPick = { input.setTextAndPlaceCursorAtEnd(it) },
                            onOpenModels = onOpenModels,
                            onOpenSetup = onOpenSetup,
                        )
                    } else {
                        TranscriptList(state)
                    }
                }
                Banners(state, onDismiss = vm::dismissBanner, onOpenSettings = onOpenSettings)
                InputBar(
                    state = input,
                    running = state.running,
                    enabled = !state.busyElsewhere,
                    onSend = { if (vm.send(input.text.toString())) input.clearText() },
                    onStop = vm::stop,
                )
            }
        }
    }

    state.pending?.let { pending -> ConfirmDialog(pending, onAnswer = { vm.answer(pending.id, it) }) }
}

// ---------------------------------------------------------------- Transcript

@Composable
private fun TranscriptList(state: ChatUiState) {
    val listState = rememberLazyListState()
    // Reverse layout keeps the newest content anchored to the bottom while it streams, with no manual scrolling.
    val reversed = remember(state.items) { state.items.asReversed() }
    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
    ) {
        if (state.running) {
            item(key = "working") { WorkingRow(state) }
        } else if (state.phase != null && state.phase != TaskPhase.Failed) {
            item(key = "footer") { FinishedFooter(state) }
        }
        items(reversed, key = { it.key }, contentType = { it::class }) { item ->
            when (item) {
                is TranscriptItem.User -> UserBubble(item.text)
                is TranscriptItem.Assistant -> AssistantText(item.text)
                is TranscriptItem.Step -> StepRow(item)
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), contentAlignment = Alignment.CenterEnd) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            SelectionContainer {
                Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    }
}

@Composable
private fun AssistantText(text: String) {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return
    SelectionContainer {
        Text(
            trimmed,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 2.dp),
        )
    }
}

@Composable
private fun StepRow(step: TranscriptItem.Step) {
    var expanded by rememberSaveable(step.key) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.clickable { expanded = !expanded }.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(24.dp).background(colors.surfaceContainerHighest, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(stepIcon(step), contentDescription = null, modifier = Modifier.size(14.dp), tint = colors.onSurfaceVariant)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    humanizeTool(step.name),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurface,
                    maxLines = 1,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    step.argsLine,
                    style = MaterialTheme.extras.mono,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                StepStatusIndicator(step)
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CodeBlock(label = stringResource(R.string.step_arguments), text = step.argsFull)
                    step.result?.let { CodeBlock(label = stringResource(R.string.step_result), text = it) }
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(label: String, text: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        SelectionContainer {
            Text(
                text,
                style = MaterialTheme.extras.mono,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small)
                    .padding(8.dp),
            )
        }
    }
}

@Composable
private fun StepStatusIndicator(step: TranscriptItem.Step) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (step.status) {
            StepStatus.Running -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            StepStatus.AwaitingApproval -> Pill(stringResource(R.string.step_awaiting), colors.tertiaryContainer, contentColor = colors.onTertiaryContainer)
            StepStatus.Ok -> Icon(Icons.Filled.CheckCircle, stringResource(R.string.cd_step_ok), Modifier.size(16.dp), tint = MaterialTheme.extras.success)
            StepStatus.Error -> Icon(Icons.Filled.Warning, stringResource(R.string.cd_step_error), Modifier.size(16.dp), tint = colors.error)
        }
        step.durationMs?.let {
            Spacer(Modifier.width(6.dp))
            Text(formatDuration(it), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
    }
}

private fun stepIcon(step: TranscriptItem.Step): ImageVector {
    val name = step.name.lowercase()
    return when {
        "shell" in name || "command" in name -> AtomIcons.Terminal
        "screen" in name || "read" in name || "find" in name || "list" in name -> AtomIcons.Eye
        step.risk == Risk.Sensitive -> Icons.Filled.Lock
        else -> AtomIcons.Touch
    }
}

@Composable
private fun WorkingRow(state: ChatUiState) {
    val label = when {
        state.pending != null -> stringResource(R.string.status_waiting_approval)
        state.currentTool != null -> humanizeTool(state.currentTool)
        state.phase == TaskPhase.Preparing -> stringResource(R.string.status_preparing)
        else -> stringResource(R.string.status_thinking)
    }
    Row(Modifier.padding(vertical = 8.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.inputTokens > 0) {
            Spacer(Modifier.width(10.dp))
            UsageText(state)
        }
    }
}

@Composable
private fun FinishedFooter(state: ChatUiState) {
    val text = when (state.phase) {
        TaskPhase.Cancelled -> stringResource(R.string.status_stopped)
        TaskPhase.StepLimit -> stringResource(R.string.status_step_limit)
        else -> null
    }
    if (text == null && state.inputTokens == 0) return
    Row(Modifier.padding(vertical = 6.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (text != null) {
            Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
        }
        if (state.inputTokens > 0) UsageText(state)
    }
}

@Composable
private fun UsageText(state: ChatUiState) {
    Text(
        stringResource(R.string.usage_tokens, formatTokens(state.inputTokens), formatTokens(state.outputTokens)),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
    )
}

// ---------------------------------------------------------------- Empty state

@Composable
private fun EmptyState(
    engineMissing: Boolean,
    accessibilityOff: Boolean,
    onPick: (String) -> Unit,
    onOpenModels: () -> Unit,
    onOpenSetup: () -> Unit,
) {
    val suggestions = listOf(
        stringResource(R.string.suggestion_1),
        stringResource(R.string.suggestion_2),
        stringResource(R.string.suggestion_3),
        stringResource(R.string.suggestion_4),
    )
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AtomMark(Modifier.size(72.dp))
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        if (engineMissing) {
            HintCard(
                icon = AtomIcons.Chip,
                text = stringResource(R.string.empty_no_engine),
                action = stringResource(R.string.empty_choose_model),
                onAction = onOpenModels,
            )
            Spacer(Modifier.height(10.dp))
        }
        if (accessibilityOff) {
            HintCard(
                icon = AtomIcons.Eye,
                text = stringResource(R.string.empty_no_accessibility),
                action = stringResource(R.string.empty_open_setup),
                onAction = onOpenSetup,
            )
            Spacer(Modifier.height(10.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            suggestions.forEach { s -> SuggestionChip(onClick = { onPick(s) }, label = { Text(s) }) }
        }
    }
}

@Composable
private fun HintCard(icon: ImageVector, text: String, action: String, onAction: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

// ---------------------------------------------------------------- Banners + input

@Composable
private fun Banners(state: ChatUiState, onDismiss: () -> Unit, onOpenSettings: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    state.notice?.let { notice ->
        Banner(icon = AtomIcons.Bolt, text = notice, container = colors.tertiaryContainer, content = colors.onTertiaryContainer, onDismiss = if (state.running) null else onDismiss)
    }
    state.error?.let { error ->
        Banner(icon = Icons.Filled.Warning, text = error, container = colors.errorContainer, content = colors.onErrorContainer, onDismiss = onDismiss, action = stringResource(R.string.action_settings), onAction = onOpenSettings)
    }
    if (state.busyElsewhere) {
        Banner(icon = Icons.Filled.Info, text = stringResource(R.string.banner_busy_elsewhere), container = colors.secondaryContainer, content = colors.onSecondaryContainer, onDismiss = null)
    }
}

@Composable
private fun Banner(
    icon: ImageVector,
    text: String,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    onDismiss: (() -> Unit)?,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Close, stringResource(R.string.cd_dismiss), Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun InputBar(
    state: TextFieldState,
    running: Boolean,
    enabled: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Surface(
            color = colors.surfaceContainerHigh,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
        ) {
            Box(Modifier.padding(horizontal = 18.dp, vertical = 13.dp), contentAlignment = Alignment.CenterStart) {
                if (state.text.isEmpty()) {
                    Text(
                        stringResource(if (running) R.string.input_hint_running else R.string.input_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    state = state,
                    enabled = enabled && !running,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 6),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        if (running) {
            FilledIconButton(
                onClick = onStop,
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.error, contentColor = colors.onError),
            ) {
                Icon(AtomIcons.Stop, stringResource(R.string.action_stop))
            }
        } else {
            FilledIconButton(
                onClick = onSend,
                enabled = enabled && state.text.isNotBlank(),
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.action_send))
            }
        }
    }
}

// ---------------------------------------------------------------- Engine chip

@Composable
fun EngineChip(info: EngineInfo, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val (label, onDevice) = when (info) {
        EngineInfo.None -> stringResource(R.string.engine_none) to false
        is EngineInfo.Cloud -> info.model to false
        is EngineInfo.Local -> (info.name ?: stringResource(R.string.engine_model_missing)) to true
        is EngineInfo.Live -> info.label to info.onDevice
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = colors.surfaceContainerHigh,
        modifier = Modifier.widthIn(max = 200.dp),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (onDevice) AtomIcons.Chip else AtomIcons.Cloud,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = colors.primary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (onDevice) {
                Spacer(Modifier.width(6.dp))
                Pill(stringResource(R.string.badge_on_device), colors.primary, contentColor = colors.onPrimary)
            }
        }
    }
}

// ---------------------------------------------------------------- Drawer

@Composable
private fun ConversationDrawer(
    summaries: List<ConversationSummary>,
    selectedId: String?,
    runningElsewhere: Boolean,
    onNewChat: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onModels: () -> Unit,
    onSettings: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf<ConversationSummary?>(null) }
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                AtomMark(Modifier.size(26.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
            }
            FilledTonalButton(onClick = onNewChat, modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.drawer_new_chat))
            }
            Spacer(Modifier.height(12.dp))
            if (summaries.isEmpty()) {
                Text(
                    stringResource(R.string.drawer_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp)) {
                items(summaries, key = { it.id }) { s ->
                    NavigationDrawerItem(
                        label = {
                            Column {
                                Text(s.title.ifBlank { stringResource(R.string.drawer_untitled) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    DateUtils.getRelativeTimeSpanString(s.updatedAt).toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        selected = s.id == selectedId,
                        onClick = { onOpen(s.id) },
                        badge = {
                            IconButton(onClick = { confirmDelete = s }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Filled.Delete, stringResource(R.string.cd_delete_conversation), Modifier.size(16.dp))
                            }
                        },
                    )
                }
            }
            if (runningElsewhere) {
                Text(
                    stringResource(R.string.banner_busy_elsewhere),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.screen_models)) },
                icon = { Icon(AtomIcons.Chip, null) },
                selected = false,
                onClick = onModels,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.screen_settings)) },
                icon = { Icon(Icons.Filled.Settings, null) },
                selected = false,
                onClick = onSettings,
                modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp),
            )
        }
    }
    confirmDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.delete_conversation_title)) },
            text = { Text(stringResource(R.string.delete_conversation_text, s.title)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(s.id)
                    confirmDelete = null
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

// ---------------------------------------------------------------- Confirmation

@Composable
private fun ConfirmDialog(pending: PendingApproval, onAnswer: (Boolean) -> Unit) {
    var remaining by remember(pending.id) { mutableLongStateOf(secondsLeft(pending)) }
    // Ticks only while the dialog is on screen.
    LaunchedEffect(pending.id) {
        while (remaining > 0) {
            delay(1_000)
            remaining = secondsLeft(pending)
        }
    }
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(R.string.confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.confirm_text), style = MaterialTheme.typography.bodyMedium)
                Text(
                    pending.action,
                    style = MaterialTheme.extras.mono,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small)
                        .padding(10.dp),
                )
                Text(
                    stringResource(R.string.confirm_countdown, remaining),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text(stringResource(R.string.confirm_allow)) } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text(stringResource(R.string.confirm_deny)) } },
    )
}

private fun secondsLeft(p: PendingApproval): Long =
    ((p.expiresAtMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
