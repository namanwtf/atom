@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package atom.app.ui.chat

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import atom.app.R
import atom.app.agent.PendingApproval
import atom.app.agent.StepStatus
import atom.app.agent.TaskPhase
import atom.app.agent.TranscriptItem
import atom.app.data.ConversationSummary
import atom.ui.components.CircleIconButton
import atom.ui.components.GlassEdge
import atom.ui.components.Group
import atom.ui.components.GroupDivider
import atom.app.ui.common.formatDuration
import atom.app.ui.common.formatTokens
import atom.ui.components.glassBar
import atom.ui.components.glassSource
import atom.app.ui.common.humanizeTool
import atom.ui.components.rememberGlassState
import atom.ui.theme.AtomIcons
import atom.ui.theme.AtomMark
import atom.ui.theme.extras
import atom.core.tool.Risk
import atom.device.Accessibility
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Chat: pure black canvas, graphite surfaces, white as the only accent. Bars are frosted glass with the transcript
 * scrolling underneath. Motion is short (150–220 ms) and nothing animates while idle.
 */

private const val QUICK = 180

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
        scrimColor = Color.Black.copy(alpha = 0.6f),
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
        val glass = rememberGlassState()
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            // Bars handle their own insets so the transcript can scroll underneath the glass.
            contentWindowInsets = WindowInsets(0),
            topBar = {
                ChatTopBar(
                    modifier = Modifier.glassBar(glass, GlassEdge.Top),
                    engine = state.engine,
                    title = state.title,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onOpenModels = onOpenModels,
                    onNewChat = vm::newChat,
                    onOpenSettings = onOpenSettings,
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
                    Banners(state, onDismiss = vm::dismissBanner, onOpenSettings = onOpenSettings)
                    Composer(
                        state = input,
                        running = state.running,
                        enabled = !state.busyElsewhere,
                        onSend = { if (vm.send(input.text.toString())) input.clearText() },
                        onStop = vm::stop,
                    )
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().glassSource(glass)) {
                if (state.items.isEmpty() && !state.running) {
                    Box(Modifier.padding(padding)) {
                        EmptyState(
                            engineMissing = state.engine == EngineInfo.None,
                            accessibilityOff = !accessibilityConnected,
                            onPick = { input.setTextAndPlaceCursorAtEnd(it) },
                            onOpenModels = onOpenModels,
                            onOpenSetup = onOpenSetup,
                        )
                    }
                } else {
                    TranscriptList(state, padding)
                }
            }
        }
    }

    state.pending?.let { pending -> ConfirmDialog(pending, onAnswer = { vm.answer(pending.id, it) }) }
}

// ---------------------------------------------------------------- Top bar

@Composable
private fun ChatTopBar(
    modifier: Modifier,
    engine: EngineInfo,
    title: String?,
    onOpenDrawer: () -> Unit,
    onOpenModels: () -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    // Translucent graphite so the frosted bar shows through the controls.
    val control = colors.surfaceContainerHigh.copy(alpha = 0.72f)
    var menuOpen by remember { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(60.dp)
            .padding(horizontal = 12.dp),
    ) {
        CircleIconButton(
            SidebarGlyph,
            stringResource(R.string.cd_open_drawer),
            onOpenDrawer,
            modifier = Modifier.align(Alignment.CenterStart),
            container = control,
        )
        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EngineChip(engine, onClick = onOpenModels)
            if (!title.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(Modifier.align(Alignment.CenterEnd)) {
            CircleIconButton(
                Icons.Filled.MoreVert,
                stringResource(R.string.chat_cd_more),
                { menuOpen = true },
                container = control,
            )
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                offset = DpOffset(0.dp, 8.dp),
                shape = RoundedCornerShape(20.dp),
                containerColor = colors.surfaceContainerHigh,
                tonalElevation = 0.dp,
            ) {
                MenuRow(stringResource(R.string.drawer_new_chat), Icons.Filled.Add) {
                    menuOpen = false
                    onNewChat()
                }
                MenuRow(stringResource(R.string.screen_settings), Icons.Filled.Settings) {
                    menuOpen = false
                    onOpenSettings()
                }
            }
        }
    }
}

@Composable
private fun MenuRow(text: String, icon: ImageVector, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    DropdownMenuItem(
        text = { Text(text, style = MaterialTheme.typography.bodyLarge) },
        leadingIcon = { Icon(icon, null, Modifier.size(20.dp)) },
        onClick = onClick,
        colors = MenuDefaults.itemColors(
            textColor = colors.onSurface,
            leadingIconColor = colors.onSurfaceVariant,
        ),
        modifier = Modifier.widthIn(min = 180.dp),
    )
}

/** The model pill: active engine with an on-device or cloud glyph; tapping opens Models. */
@Composable
fun EngineChip(info: EngineInfo, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val (label, onDevice) = when (info) {
        EngineInfo.None -> stringResource(R.string.engine_none) to false
        is EngineInfo.Cloud -> info.model to false
        is EngineInfo.Local -> (info.name ?: stringResource(R.string.engine_model_missing)) to true
        is EngineInfo.Live -> info.label to info.onDevice
    }
    val glyphTint = when {
        info is EngineInfo.Local && info.missing -> MaterialTheme.extras.warning
        info == EngineInfo.None -> colors.onSurfaceVariant
        else -> colors.onSurface.copy(alpha = 0.85f)
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = colors.surfaceContainerHigh.copy(alpha = 0.72f),
        modifier = Modifier.height(36.dp).widthIn(max = 220.dp),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (onDevice) AtomIcons.Chip else AtomIcons.Cloud,
                contentDescription = stringResource(if (onDevice) R.string.badge_on_device else R.string.tab_cloud),
                modifier = Modifier.size(15.dp),
                tint = glyphTint,
            )
            Spacer(Modifier.width(7.dp))
            Text(
                label,
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

// ---------------------------------------------------------------- Transcript

@Composable
private fun TranscriptList(state: ChatUiState, bars: PaddingValues) {
    val listState = rememberLazyListState()
    // Reverse layout keeps the newest content anchored to the bottom while it streams, with no manual scrolling.
    val reversed = remember(state.items) { state.items.asReversed() }
    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = Modifier.fillMaxSize(),
        // The list runs under the glass bars; padding keeps the first and last items clear of them.
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 16.dp,
            top = bars.calculateTopPadding() + 12.dp,
            bottom = bars.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.Bottom),
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
    Box(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp), contentAlignment = Alignment.CenterEnd) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 6.dp, bottomStart = 22.dp),
            modifier = Modifier.padding(start = 48.dp).widthIn(max = 320.dp),
        ) {
            SelectionContainer {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                )
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
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp, end = 4.dp),
        )
    }
}

@Composable
private fun StepRow(step: TranscriptItem.Step) {
    var expanded by rememberSaveable(step.key) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { expanded = !expanded }
            .padding(start = 2.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(stepIcon(step), contentDescription = null, modifier = Modifier.size(15.dp), tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Text(
                humanizeTool(step.name),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurface.copy(alpha = 0.9f),
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                step.argsLine,
                style = MaterialTheme.extras.mono,
                color = colors.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            step.durationMs?.let {
                Text(formatDuration(it), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
            }
            StepStatusMark(step.status)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(QUICK)) + expandVertically(tween(200)),
            exit = fadeOut(tween(150)) + shrinkVertically(tween(180)),
        ) {
            Column(Modifier.padding(start = 25.dp, top = 10.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CodeBlock(label = stringResource(R.string.step_arguments), text = step.argsFull)
                step.result?.let { CodeBlock(label = stringResource(R.string.step_result), text = it) }
            }
        }
    }
}

@Composable
private fun CodeBlock(label: String, text: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        SelectionContainer {
            Text(
                text,
                style = MaterialTheme.extras.mono,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun StepStatusMark(status: StepStatus) {
    val colors = MaterialTheme.colorScheme
    when (status) {
        StepStatus.Running -> RunningDot()
        StepStatus.AwaitingApproval -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.step_awaiting), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.extras.warning)
            Spacer(Modifier.width(6.dp))
            Dot(MaterialTheme.extras.warning)
        }
        StepStatus.Ok -> Dot(MaterialTheme.extras.success, stringResource(R.string.cd_step_ok))
        StepStatus.Error -> Dot(colors.error, stringResource(R.string.cd_step_error))
    }
}

@Composable
private fun Dot(color: Color, description: String? = null) {
    Box(
        Modifier
            .size(6.dp)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .background(color, CircleShape),
    )
}

/** Breathing dot for a step in flight. Only composed while the step runs, so the animation stops with it. */
@Composable
private fun RunningDot() {
    val transition = rememberInfiniteTransition(label = "step")
    val alpha = transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "stepAlpha",
    )
    val color = MaterialTheme.colorScheme.onSurface
    Box(Modifier.size(6.dp).graphicsLayer { this.alpha = alpha.value }.background(color, CircleShape))
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
    Row(Modifier.padding(top = 12.dp, bottom = 4.dp, start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        WorkingDots()
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.inputTokens > 0) {
            Spacer(Modifier.width(10.dp))
            UsageText(state)
        }
    }
}

/** Three calm dots in a travelling wave. Composed only while a task runs; alpha is applied in the draw layer. */
@Composable
private fun WorkingDots() {
    val transition = rememberInfiniteTransition(label = "working")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "workingPhase",
    )
    val color = MaterialTheme.colorScheme.onSurface
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            Box(
                Modifier
                    .size(6.dp)
                    .graphicsLayer {
                        val d = ((phase.value - i * 0.18f) % 1f + 1f) % 1f
                        alpha = 0.22f + 0.78f * (1f - abs(d * 2f - 1f))
                    }
                    .background(color, CircleShape),
            )
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
    Row(Modifier.padding(top = 10.dp, bottom = 2.dp, start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
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
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
    )
}

// ---------------------------------------------------------------- Empty state

private class Suggestion(val icon: ImageVector, val text: String)

@Composable
private fun EmptyState(
    engineMissing: Boolean,
    accessibilityOff: Boolean,
    onPick: (String) -> Unit,
    onOpenModels: () -> Unit,
    onOpenSetup: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val suggestions = listOf(
        Suggestion(AtomIcons.Eye, stringResource(R.string.suggestion_1)),
        Suggestion(Icons.Filled.Notifications, stringResource(R.string.suggestion_2)),
        Suggestion(Icons.Filled.PlayArrow, stringResource(R.string.suggestion_3)),
        Suggestion(Icons.Filled.Info, stringResource(R.string.suggestion_4)),
    )
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            GlowingMark()
            Text(
                stringResource(R.string.chat_empty_headline),
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Light),
                color = colors.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 300.dp),
            )
            Spacer(Modifier.height(36.dp))
            if (engineMissing || accessibilityOff) {
                Group {
                    if (engineMissing) {
                        NudgeRow(AtomIcons.Chip, stringResource(R.string.empty_no_engine), stringResource(R.string.empty_choose_model), onOpenModels)
                    }
                    if (engineMissing && accessibilityOff) GroupDivider(inset = 48.dp)
                    if (accessibilityOff) {
                        NudgeRow(AtomIcons.Eye, stringResource(R.string.empty_no_accessibility), stringResource(R.string.empty_open_setup), onOpenSetup)
                    }
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

/** The emblem over a soft grey halo. Drawn once from a cached brush; nothing animates. */
@Composable
private fun GlowingMark() {
    val glow = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .size(160.dp)
            .drawWithCache {
                val brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to glow.copy(alpha = 0.10f),
                        0.45f to glow.copy(alpha = 0.035f),
                        1f to Color.Transparent,
                    ),
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
    Surface(
        onClick = { onPick(s.text) },
        color = colors.surfaceContainer,
        shape = RoundedCornerShape(26.dp),
        modifier = modifier,
    ) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 16.dp)) {
            Box(Modifier.size(32.dp).background(colors.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.Center) {
                Icon(s.icon, null, Modifier.size(16.dp), tint = colors.onSurface.copy(alpha = 0.85f))
            }
            Spacer(Modifier.height(14.dp))
            Text(
                s.text,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NudgeRow(icon: ImageVector, text: String, action: String, onAction: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface, modifier = Modifier.weight(1f))
        TextButton(onClick = onAction) {
            Text(action, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = colors.primary)
        }
    }
}

// ---------------------------------------------------------------- Banners + composer

@Composable
private fun Banners(state: ChatUiState, onDismiss: () -> Unit, onOpenSettings: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    state.notice?.let { notice ->
        QuietBanner(icon = AtomIcons.Bolt, tint = MaterialTheme.extras.warning, text = notice, onDismiss = if (state.running) null else onDismiss)
    }
    state.error?.let { error ->
        QuietBanner(
            icon = Icons.Filled.Warning,
            tint = colors.error,
            text = error,
            onDismiss = onDismiss,
            action = stringResource(R.string.action_settings),
            onAction = onOpenSettings,
        )
    }
    if (state.busyElsewhere) {
        QuietBanner(icon = Icons.Filled.Info, tint = colors.onSurfaceVariant, text = stringResource(R.string.banner_busy_elsewhere), onDismiss = null)
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
) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainer.copy(alpha = 0.92f), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.heightIn(min = 44.dp).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(16.dp), tint = tint)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = colors.onSurface, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
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

@Composable
private fun Composer(
    state: TextFieldState,
    running: Boolean,
    enabled: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainerHigh, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
        Column {
            Box(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 2.dp)) {
                if (state.text.isEmpty()) {
                    Text(
                        stringResource(if (running) R.string.input_hint_running else R.string.input_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
            Row(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SendButton(
                    running = running,
                    canSend = enabled && state.text.isNotBlank(),
                    onSend = onSend,
                    onStop = onStop,
                )
            }
        }
    }
}

/** White disc with a black arrow when there is text; graphite when empty; a white stop square while running. */
@Composable
private fun SendButton(running: Boolean, canSend: Boolean, onSend: () -> Unit, onStop: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val active = !running && canSend
    val container by animateColorAsState(
        if (active) colors.primary else colors.surfaceContainerHighest,
        tween(QUICK),
        label = "sendContainer",
    )
    val arrow by animateColorAsState(
        if (active) colors.onPrimary else colors.onSurfaceVariant.copy(alpha = 0.6f),
        tween(QUICK),
        label = "sendArrow",
    )
    val description = stringResource(if (running) R.string.action_stop else R.string.action_send)
    Surface(
        onClick = if (running) onStop else onSend,
        enabled = running || canSend,
        shape = CircleShape,
        color = container,
        modifier = Modifier.size(40.dp).semantics { contentDescription = description },
    ) {
        Crossfade(targetState = running, animationSpec = tween(160), label = "sendGlyph") { isRunning ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (isRunning) {
                    Box(Modifier.size(12.dp).background(colors.onSurface, RoundedCornerShape(3.dp)))
                } else {
                    Icon(ArrowUpGlyph, null, Modifier.size(20.dp), tint = arrow)
                }
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
    val colors = MaterialTheme.colorScheme
    var confirmDelete by remember { mutableStateOf<ConversationSummary?>(null) }
    val edge = colors.outlineVariant
    ModalDrawerSheet(
        drawerContainerColor = colors.background,
        drawerShape = RectangleShape,
        modifier = Modifier
            .widthIn(max = 320.dp)
            .drawWithContent {
                drawContent()
                val x = size.width - 0.5f
                drawLine(edge, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            },
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                AtomMark(Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
            }
            NewChatButton(onNewChat, Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(20.dp))
            if (summaries.isEmpty()) {
                Text(
                    stringResource(R.string.drawer_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 26.dp, vertical = 8.dp),
                )
            } else {
                Text(
                    stringResource(R.string.chat_recent),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 26.dp, bottom = 6.dp),
                )
            }
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(summaries, key = { it.id }) { s ->
                    ConversationRow(
                        summary = s,
                        selected = s.id == selectedId,
                        onClick = { onOpen(s.id) },
                        onDelete = { confirmDelete = s },
                    )
                }
            }
            if (runningElsewhere) {
                Row(Modifier.padding(horizontal = 26.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RunningDot()
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.banner_busy_elsewhere),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), thickness = 1.dp, color = colors.outlineVariant)
            DrawerFooterRow(AtomIcons.Chip, stringResource(R.string.screen_models), onModels)
            DrawerFooterRow(Icons.Filled.Settings, stringResource(R.string.screen_settings), onSettings)
            Spacer(Modifier.height(8.dp))
        }
    }
    confirmDelete?.let { s ->
        AtomDialog(
            onDismissRequest = { confirmDelete = null },
            icon = Icons.Filled.Delete,
            title = stringResource(R.string.delete_conversation_title),
            dismissLabel = stringResource(R.string.action_cancel),
            onDismiss = { confirmDelete = null },
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                onDelete(s.id)
                confirmDelete = null
            },
        ) {
            Text(
                stringResource(R.string.delete_conversation_text, s.title.ifBlank { stringResource(R.string.drawer_untitled) }),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NewChatButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = colors.primary,
        contentColor = colors.onPrimary,
        modifier = modifier.fillMaxWidth().height(48.dp),
    ) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.drawer_new_chat), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Tap to open; long-press to delete. The selected row also shows a delete button. */
@Composable
private fun ConversationRow(summary: ConversationSummary, selected: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val background by animateColorAsState(if (selected) colors.surfaceContainerHigh else Color.Transparent, tween(QUICK), label = "rowBg")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDelete()
                },
                onLongClickLabel = stringResource(R.string.cd_delete_conversation),
            )
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                summary.title.ifBlank { stringResource(R.string.drawer_untitled) },
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                DateUtils.getRelativeTimeSpanString(summary.updatedAt).toString(),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (selected) {
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Delete, stringResource(R.string.cd_delete_conversation), Modifier.size(18.dp), tint = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DrawerFooterRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .padding(horizontal = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
    }
}

// ---------------------------------------------------------------- Dialogs

/** Graphite rounded dialog with a graphite secondary and a white primary button. */
@Composable
private fun AtomDialog(
    onDismissRequest: () -> Unit,
    icon: ImageVector?,
    title: String,
    dismissLabel: String,
    onDismiss: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismissRequest) {
        Surface(shape = RoundedCornerShape(28.dp), color = colors.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 24.dp, bottom = 18.dp)) {
                if (icon != null) {
                    Box(Modifier.size(40.dp).background(colors.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(icon, null, Modifier.size(20.dp), tint = colors.onSurface)
                    }
                    Spacer(Modifier.height(16.dp))
                }
                Text(title, style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
                Spacer(Modifier.height(10.dp))
                content()
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DialogButton(dismissLabel, primary = false, onClick = onDismiss, modifier = Modifier.weight(1f))
                    DialogButton(confirmLabel, primary = true, onClick = onConfirm, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DialogButton(text: String, primary: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (primary) colors.primary else colors.surfaceContainerHighest,
        contentColor = if (primary) colors.onPrimary else colors.onSurface,
        modifier = modifier.height(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

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
            Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .background(colors.surfaceContainer, RoundedCornerShape(16.dp))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(pending.action, style = MaterialTheme.extras.mono, color = colors.onSurface)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.confirm_countdown, remaining),
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
        )
    }
}

private fun secondsLeft(p: PendingApproval): Long =
    ((p.expiresAtMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(0)

// ---------------------------------------------------------------- Glyphs

/** Two offset strokes: the sidebar toggle. */
private val SidebarGlyph: ImageVector by lazy { strokeGlyph("Sidebar", "M4,8.5h16M4,15.5h10") }

/** Up arrow for send (Material-style geometry, Apache-2.0). */
private val ArrowUpGlyph: ImageVector by lazy { strokeGlyph("ArrowUp", "M12,19V5.5M6.5,11L12,5.5L17.5,11", width = 2.2f) }

private fun strokeGlyph(name: String, path: String, width: Float = 2f): ImageVector =
    ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData = addPathNodes(path),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        .build()
