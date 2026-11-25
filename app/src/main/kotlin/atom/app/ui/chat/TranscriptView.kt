@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package atom.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import atom.app.R
import atom.app.agent.ChatItem
import atom.app.agent.StepCall
import atom.app.agent.StepCard
import atom.characters.model.CandidateSet
import atom.web.ui.BrowserPreviewCard
import atom.web.ui.ImageResultCard
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import atom.app.agent.Transcript
import atom.app.agent.WorkEntry
import atom.app.data.Attachment
import atom.app.data.AttachmentKind
import atom.app.data.ReadingPosition
import atom.app.ui.common.formatDuration
import atom.app.ui.common.formatTokens
import atom.app.ui.common.humanizeTool
import atom.core.tool.Risk
import atom.ui.components.AtomMenu
import atom.ui.feature.RunPhase
import atom.ui.feature.StepState
import atom.ui.theme.AtomIcons
import atom.ui.theme.extras
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** Callbacks for message actions; null hides an action. */
internal class MessageActions(
    val onCopy: (String) -> Unit,
    val onRegenerate: ((ChatItem.Turn) -> Unit)?,
    val onBranch: ((ChatItem.Turn) -> Unit)?,
    val onDeleteFrom: ((Int) -> Unit)?,
    val onEdit: ((ChatItem.User) -> Unit)?,
    /** Text and message id (the turn key). */
    val onReadAloud: ((String, String) -> Unit)?,
    val laterTurns: (Int) -> Int,
    val textAt: (Int?) -> String?,
    /** Id of the message being read aloud, for a stop button on it. */
    val readingId: String? = null,
    val onStopReading: () -> Unit = {},
    /** Character chats: reply candidates by stored index, and switching between them. */
    val candidates: Map<Int, CandidateSet> = emptyMap(),
    val onCandidate: ((Int, Int) -> Unit)? = null,
    /** Character chats: edit a message in place (later turns stay). */
    val onEditInPlace: ((Int, String) -> Unit)? = null,
    /** Opens the live browser for the take-over; null hides the action on browser cards. */
    val onTakeOverBrowser: (() -> Unit)? = null,
)

private const val PAGE = 40

@Composable
internal fun TranscriptList(
    state: ChatUiState,
    bars: PaddingValues,
    actions: MessageActions,
    restore: Pair<String, ReadingPosition>?,
    onRestored: () -> Unit,
    onSavePosition: (String, String?, Int) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var window by rememberSaveable(state.conversationId) { mutableIntStateOf(PAGE) }
    val all = state.items
    val shown = remember(all, window) { if (all.size <= window) all else all.subList(all.size - window, all.size) }
    // Reverse layout keeps the newest content anchored to the bottom while it streams; scrolling up holds still.
    val reversed = remember(shown) { shown.asReversed() }
    val hasOlder = all.size > shown.size

    // Older history pages in as the user nears the top.
    LaunchedEffect(listState, hasOlder, reversed.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .filter { hasOlder && it >= reversed.size - 6 }
            .collect { window += PAGE }
    }

    // Restore the reading position once its item is present.
    LaunchedEffect(restore, all.size) {
        val (id, pos) = restore ?: return@LaunchedEffect
        if (id != state.conversationId || all.isEmpty()) return@LaunchedEffect
        val fromEnd = all.indexOfLast { it.key == pos.itemKey }.takeIf { it >= 0 }?.let { all.size - 1 - it }
        if (fromEnd != null) {
            if (fromEnd >= window) window = fromEnd + PAGE
            listState.scrollToItem(fromEnd + if (state.running) 1 else 0, pos.offset)
        }
        onRestored()
    }

    val id = state.conversationId
    DisposableEffect(id) {
        onDispose {
            if (id != null) {
                val atBottom = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 8
                val key = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String
                onSavePosition(id, key.takeIf { !atBottom && it != "working" && it != "older" }, listState.firstVisibleItemScrollOffset)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 16.dp, top = bars.calculateTopPadding() + 12.dp, bottom = bars.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.Bottom),
        ) {
            if (state.running) {
                item(key = "working", contentType = "working") { WorkingRow(state, onPause, onResume) }
            }
            items(reversed, key = { it.key }, contentType = { it::class }) { item ->
                when (item) {
                    is ChatItem.User -> UserBubble(item, actions, state.canEdit)
                    is ChatItem.Turn -> TurnView(item, actions, state.canEdit)
                    is ChatItem.Notice -> NoticeRow(item.text, item.error)
                    is ChatItem.Compacted -> CompactedRow(item.key, item.summary)
                }
            }
            if (hasOlder) item(key = "older", contentType = "older") { OlderRow { window += PAGE } }
        }
        val awayFromBottom by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 400 } }
        AnimatedVisibility(
            visible = awayFromBottom,
            enter = fadeIn(tween(QUICK)) + scaleIn(tween(QUICK), initialScale = 0.85f),
            exit = fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.85f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bars.calculateBottomPadding() + 12.dp),
        ) {
            Surface(
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shadowElevation = 6.dp,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(ChatGlyphs.ArrowDown, stringResource(R.string.chat_back_to_bottom), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun OlderRow(onLoad: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.chat_load_older),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onLoad).padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

// ---------------------------------------------------------------- User

@Composable
private fun UserBubble(item: ChatItem.User, actions: MessageActions, canEdit: Boolean) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editInPlace by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 6.dp, bottomStart = 22.dp)
    Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp), horizontalAlignment = Alignment.End) {
        if (item.steering) {
            Text(
                stringResource(R.string.chat_steered),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(end = 6.dp, bottom = 4.dp),
            )
        }
        if (item.attachments.isNotEmpty()) {
            FlowRow(
                Modifier.padding(start = 48.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item.attachments.forEach { AttachmentChip(it, onRemove = null) }
            }
        }
        Box {
            if (item.text.isNotBlank()) {
                Surface(
                    color = colors.surfaceContainerHigh,
                    contentColor = colors.onSurface,
                    shape = shape,
                    modifier = Modifier
                        .padding(start = 48.dp)
                        .widthIn(max = 340.dp)
                        .clip(shape)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                menu = true
                            },
                            onLongClickLabel = stringResource(R.string.chat_message_actions),
                        ),
                ) {
                    Text(item.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp))
                }
            }
            AtomMenu(expanded = menu, onDismiss = { menu = false }) {
                MenuAction(stringResource(R.string.chat_copy), ChatGlyphs.Copy) {
                    menu = false
                    actions.onCopy(item.text)
                }
                val edit = actions.onEdit
                if (canEdit && actions.onEditInPlace != null) {
                    MenuAction(stringResource(R.string.chat_edit), ChatGlyphs.Edit) {
                        menu = false
                        editInPlace = true
                    }
                } else if (canEdit && edit != null) {
                    MenuAction(stringResource(R.string.chat_edit), ChatGlyphs.Edit) {
                        menu = false
                        edit(item)
                    }
                }
                if (canEdit && actions.onDeleteFrom != null) {
                    MenuAction(stringResource(R.string.action_delete), Icons.Filled.Delete) {
                        menu = false
                        confirmDelete = true
                    }
                }
            }
        }
    }
    if (editInPlace) {
        EditInPlaceDialog(item.text, onSave = { actions.onEditInPlace?.invoke(item.index, it) }, onDismiss = { editInPlace = false })
    }
    if (confirmDelete) {
        DeleteFromDialog(
            later = actions.laterTurns(item.index),
            onConfirm = {
                confirmDelete = false
                actions.onDeleteFrom?.invoke(item.index)
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
internal fun AttachmentChip(a: Attachment, onRemove: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainerHighest, shape = RoundedCornerShape(50)) {
        Row(
            Modifier.padding(start = 10.dp, end = if (onRemove != null) 2.dp else 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                when (a.kind) {
                    AttachmentKind.Image -> ChatGlyphs.Image
                    AttachmentKind.File -> ChatGlyphs.File
                    AttachmentKind.Folder -> ChatGlyphs.Folder
                    AttachmentKind.Path -> ChatGlyphs.Path
                },
                null,
                Modifier.size(14.dp),
                tint = colors.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                a.name,
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp),
            )
            if (onRemove != null) {
                IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Close, stringResource(R.string.action_remove), Modifier.size(14.dp), tint = colors.onSurfaceVariant)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Turn

@Composable
private fun TurnView(turn: ChatItem.Turn, actions: MessageActions, canEdit: Boolean) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp)) {
        if (turn.work.isNotEmpty()) Timeline(turn)
        TurnCards(turn, actions.onTakeOverBrowser)
        turn.answerReasoning?.let { ReasoningBlock(it) }
        val answer = turn.answer
        if (answer != null) {
            val shownText = if (turn.streaming) smoothReveal(answer) else answer
            SelectionContainer {
                MarkdownText(shownText, Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp, end = 4.dp))
            }
        }
        if (turn.finished && (answer != null || turn.work.isNotEmpty())) TurnActions(turn, actions, canEdit)
    }
}

/**
 * Generated images (every one) and the latest browser preview of the turn, shown outside the folded timeline so
 * results stay visible.
 */
@Composable
private fun TurnCards(turn: ChatItem.Turn, onTakeOver: (() -> Unit)?) {
    val cards = remember(turn.work) {
        val all = turn.work.filterIsInstance<WorkEntry.Steps>().flatMap { g -> g.calls.mapNotNull { c -> c.card?.let { c.key to it } } }
        val images = all.filter { it.second is StepCard.Image }
        val browser = all.lastOrNull { it.second is StepCard.Browser }
        images + listOfNotNull(browser)
    }
    if (cards.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        cards.forEach { (key, card) ->
            androidx.compose.runtime.key(key) {
                when (card) {
                    is StepCard.Image -> ImageResultCard(card.text, Modifier.widthIn(max = 420.dp))
                    is StepCard.Browser -> BrowserPreviewCard(card.text, onTakeOver = if (turn.live) onTakeOver else null, modifier = Modifier.widthIn(max = 280.dp))
                }
            }
        }
    }
}

/** Reveals streamed text a few characters per frame, catching up faster when far behind. Idle once caught up. */
@Composable
private fun smoothReveal(target: String): String {
    var shown by remember { mutableIntStateOf(0) }
    LaunchedEffect(target) {
        if (shown > target.length) shown = target.length
        while (shown < target.length) {
            withFrameNanos { }
            val behind = target.length - shown
            shown = (shown + (behind / 6).coerceIn(2, 400)).coerceAtMost(target.length)
        }
    }
    return target.substring(0, shown.coerceAtMost(target.length))
}

@Composable
private fun Timeline(turn: ChatItem.Turn) {
    val colors = MaterialTheme.colorScheme
    val summary = turn.summary
    var expanded by rememberSaveable(turn.key) { mutableStateOf<Boolean?>(null) }
    // Live work is open so progress is visible; finished work folds to one line unless the user opened it.
    val open = expanded ?: turn.live
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable { expanded = !open }
                .padding(vertical = 6.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (summary.running) RunningDot() else Icon(ChatGlyphs.Brain, null, Modifier.size(15.dp), tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Text(timelineLine(summary.thoughts, summary.toolCalls, summary.failed), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Icon(ChatGlyphs.Chevron, null, Modifier.size(14.dp).rotate(if (open) 90f else 0f), tint = colors.onSurfaceVariant)
        }
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(QUICK)) + expandVertically(tween(200)),
            exit = fadeOut(tween(150)) + shrinkVertically(tween(180)),
        ) {
            Column(Modifier.padding(start = 6.dp, top = 2.dp, bottom = 4.dp)) {
                turn.work.forEach { entry ->
                    when (entry) {
                        is WorkEntry.Thought -> ThoughtRow(entry)
                        is WorkEntry.Note -> Text(
                            entry.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(start = 25.dp, top = 4.dp, bottom = 4.dp),
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                        is WorkEntry.Steps -> StepGroup(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun timelineLine(thoughts: Int, calls: Int, failed: Int): String {
    val parts = buildList {
        if (thoughts > 0) add(pluralStringResource(R.plurals.timeline_thought, thoughts, thoughts))
        if (calls > 0) add(pluralStringResource(R.plurals.timeline_calls, calls, calls))
        if (failed > 0) add(stringResource(R.string.timeline_failed, failed))
    }
    return parts.joinToString(" · ").ifEmpty { stringResource(R.string.timeline_working) }
}

@Composable
private fun ThoughtRow(t: WorkEntry.Thought) {
    val colors = MaterialTheme.colorScheme
    var open by rememberSaveable(t.key) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open = !open }.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(ChatGlyphs.Brain, null, Modifier.size(15.dp), tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Text(reasoningLabel(t), style = MaterialTheme.typography.labelLarge, color = colors.onSurface.copy(alpha = 0.85f))
        }
        if (open) {
            Text(t.text, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 25.dp, top = 6.dp))
        }
    }
}

@Composable
private fun reasoningLabel(t: WorkEntry.Thought): String {
    if (t.live) return stringResource(R.string.reasoning_live)
    val ms = t.durationMs ?: return stringResource(R.string.reasoning_done_plain)
    val seconds = ((ms + 500) / 1000).toInt()
    return pluralStringResource(R.plurals.reasoning_done, seconds, seconds)
}

/** The answer's reasoning: the last lines while it streams, then folded to "Reasoning completed · N s". */
@Composable
private fun ReasoningBlock(t: WorkEntry.Thought) {
    val colors = MaterialTheme.colorScheme
    var open by rememberSaveable(t.key) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = !t.live) { open = !open }
            .padding(vertical = 6.dp, horizontal = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (t.live) RunningDot() else Icon(ChatGlyphs.Brain, null, Modifier.size(15.dp), tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Text(reasoningLabel(t), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            if (!t.live) {
                Spacer(Modifier.width(4.dp))
                Icon(ChatGlyphs.Chevron, null, Modifier.size(14.dp).rotate(if (open) 90f else 0f), tint = colors.onSurfaceVariant)
            }
        }
        if (t.live) {
            val tail = remember(t.text) { t.text.lines().filter { it.isNotBlank() }.takeLast(3).joinToString("\n") }
            Text(
                tail,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 25.dp, top = 4.dp),
            )
        } else {
            AnimatedVisibility(visible = open, enter = fadeIn(tween(QUICK)) + expandVertically(tween(200)), exit = fadeOut(tween(150)) + shrinkVertically(tween(180))) {
                SelectionContainer {
                    Text(t.text, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 25.dp, top = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun StepGroup(group: WorkEntry.Steps) {
    val colors = MaterialTheme.colorScheme
    var open by rememberSaveable(group.key) { mutableStateOf(false) }
    val last = group.calls.last()
    val state = when {
        group.calls.any { it.state == StepState.Running } -> StepState.Running
        group.calls.any { it.state == StepState.Failed } -> StepState.Failed
        group.calls.any { it.state == StepState.Unknown } -> StepState.Unknown
        else -> StepState.Completed
    }
    val duration = group.calls.mapNotNull { it.durationMs }.takeIf { it.isNotEmpty() }?.sum()
    val running = group.calls.firstOrNull { it.state == StepState.Running }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open = !open }.padding(vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(stepIcon(last), null, Modifier.size(15.dp), tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Text(humanizeTool(group.tool), style = MaterialTheme.typography.labelLarge, color = colors.onSurface.copy(alpha = 0.9f), maxLines = 1)
            if (group.calls.size > 1) {
                Spacer(Modifier.width(6.dp))
                Text("×${group.calls.size}", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                last.lastLine ?: last.argsLine,
                style = MaterialTheme.extras.mono,
                color = colors.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            val startedAt = running?.startedAt
            if (startedAt != null) {
                LiveTimer(startedAt)
                Spacer(Modifier.width(8.dp))
            } else if (duration != null) {
                Text(formatDuration(duration), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
            }
            StepMark(state, group.calls.any { it.awaitingApproval })
        }
        AnimatedVisibility(visible = open, enter = fadeIn(tween(QUICK)) + expandVertically(tween(200)), exit = fadeOut(tween(150)) + shrinkVertically(tween(180))) {
            Column(Modifier.padding(start = 25.dp, top = 10.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                group.calls.forEach { CallDetails(it, numbered = group.calls.size > 1) }
            }
        }
    }
}

@Composable
private fun CallDetails(call: StepCall, numbered: Boolean) {
    val command = remember(call.argsFull) { if (Transcript.isTerminal(call.name)) Transcript.command(call.argsFull) else null }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (numbered) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepMark(call.state, call.awaitingApproval)
                Spacer(Modifier.width(8.dp))
                Text(call.argsLine, style = MaterialTheme.extras.mono, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (command != null) {
            DetailBlock(stringResource(R.string.step_command), command, stringResource(R.string.step_copy_command))
        } else {
            DetailBlock(stringResource(R.string.step_arguments), call.argsFull, stringResource(R.string.md_copy))
        }
        val result = call.result
        if (result != null) {
            DetailBlock(stringResource(R.string.step_result), result, stringResource(R.string.md_copy))
        } else if (call.state == StepState.Unknown) {
            Text(stringResource(R.string.step_unknown), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DetailBlock(label: String, text: String, copyLabel: String) {
    val colors = MaterialTheme.colorScheme
    val copy = rememberCopier()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(
                copyLabel,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { copy(text) }.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        SelectionContainer {
            Text(
                text,
                style = MaterialTheme.extras.mono,
                color = colors.onSurface.copy(alpha = 0.9f),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .background(colors.surfaceContainer, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StepMark(state: StepState, awaiting: Boolean) {
    val colors = MaterialTheme.colorScheme
    val extras = MaterialTheme.extras
    when {
        awaiting -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.step_awaiting), style = MaterialTheme.typography.labelSmall, color = extras.warning)
            Spacer(Modifier.width(6.dp))
            Dot(extras.warning)
        }
        state == StepState.Running -> RunningDot()
        state == StepState.Completed -> Dot(extras.success, stringResource(R.string.cd_step_ok))
        state == StepState.Failed -> Dot(colors.error, stringResource(R.string.cd_step_error))
        else -> Dot(colors.onSurfaceVariant, stringResource(R.string.step_unknown))
    }
}

private fun stepIcon(step: StepCall): ImageVector {
    val name = step.name.lowercase()
    return when {
        Transcript.isTerminal(name) -> AtomIcons.Terminal
        "screen" in name || "read" in name || "find" in name || "list" in name || "search" in name -> AtomIcons.Eye
        "url" in name || "web" in name || "browse" in name -> AtomIcons.Link
        step.risk == Risk.Sensitive -> Icons.Filled.Lock
        else -> AtomIcons.Touch
    }
}

@Composable
private fun TurnActions(turn: ChatItem.Turn, actions: MessageActions, canEdit: Boolean) {
    val copyText = actions.textAt(turn.answerIndex) ?: turn.answer
    var more by remember { mutableStateOf(false) }
    var confirmRegenerate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editInPlace by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val userIndex = turn.userIndex
    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (copyText != null) {
            ActionIcon(ChatGlyphs.Copy, stringResource(R.string.chat_copy)) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                actions.onCopy(copyText)
            }
        }
        val regenerate = actions.onRegenerate
        if (canEdit && regenerate != null && userIndex != null) {
            ActionIcon(ChatGlyphs.Regenerate, stringResource(R.string.chat_regenerate)) {
                if (actions.laterTurns(userIndex) > 0) confirmRegenerate = true else regenerate(turn)
            }
        }
        val branch = actions.onBranch
        if (canEdit && branch != null && turn.answerIndex != null) {
            ActionIcon(ChatGlyphs.Branch, stringResource(R.string.chat_branch)) { branch(turn) }
        }
        val readAloud = actions.onReadAloud
        if (readAloud != null && copyText != null) {
            if (actions.readingId == turn.key) {
                ActionIcon(ChatGlyphs.Pause, stringResource(R.string.chat_stop_reading)) { actions.onStopReading() }
            } else {
                ActionIcon(ChatGlyphs.Speaker, stringResource(R.string.chat_read_aloud)) { readAloud(copyText, turn.key) }
            }
        }
        val answerIndex = turn.answerIndex
        if (canEdit && answerIndex != null && actions.onEditInPlace != null && copyText != null) {
            ActionIcon(ChatGlyphs.Edit, stringResource(R.string.chat_edit)) { editInPlace = true }
        }
        val set = answerIndex?.let { actions.candidates[it] }
        val pick = actions.onCandidate
        if (set != null && pick != null && answerIndex != null) {
            Spacer(Modifier.width(4.dp))
            CandidateSwitcher(set, enabled = canEdit, onPrevious = { pick(answerIndex, set.selected - 1) }, onNext = { pick(answerIndex, set.selected + 1) })
        }
        if (canEdit && actions.onDeleteFrom != null && userIndex != null) {
            Box {
                ActionIcon(Icons.Filled.MoreVert, stringResource(R.string.chat_cd_more)) { more = true }
                AtomMenu(expanded = more, onDismiss = { more = false }) {
                    MenuAction(stringResource(R.string.chat_delete_turn), Icons.Filled.Delete) {
                        more = false
                        confirmDelete = true
                    }
                }
            }
        }
    }
    val editIndex = turn.answerIndex
    if (editInPlace && editIndex != null && copyText != null) {
        EditInPlaceDialog(copyText, onSave = { actions.onEditInPlace?.invoke(editIndex, it) }, onDismiss = { editInPlace = false })
    }
    if (confirmRegenerate && userIndex != null) {
        val later = actions.laterTurns(userIndex)
        AtomDialog(
            onDismissRequest = { confirmRegenerate = false },
            icon = ChatGlyphs.Regenerate,
            title = stringResource(R.string.chat_regenerate_title),
            dismissLabel = stringResource(R.string.action_cancel),
            onDismiss = { confirmRegenerate = false },
            confirmLabel = stringResource(R.string.chat_regenerate),
            onConfirm = {
                confirmRegenerate = false
                actions.onRegenerate?.invoke(turn)
            },
        ) {
            Text(pluralStringResource(R.plurals.chat_regenerate_text, later, later), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (confirmDelete && userIndex != null) {
        DeleteFromDialog(
            later = actions.laterTurns(userIndex),
            onConfirm = {
                confirmDelete = false
                actions.onDeleteFrom?.invoke(userIndex + 1)
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** "‹ 2 / 3 ›" for a character reply with alternatives. */
@Composable
private fun CandidateSwitcher(set: CandidateSet, enabled: Boolean, onPrevious: () -> Unit, onNext: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious, enabled = enabled && set.hasPrevious, modifier = Modifier.size(32.dp)) {
            Icon(ChatGlyphs.Chevron, stringResource(R.string.chat_candidate_previous), Modifier.size(14.dp).rotate(180f), tint = colors.onSurfaceVariant)
        }
        Text(set.position, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1)
        IconButton(onClick = onNext, enabled = enabled && set.hasNext, modifier = Modifier.size(32.dp)) {
            Icon(ChatGlyphs.Chevron, stringResource(R.string.chat_candidate_next), Modifier.size(14.dp), tint = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun EditInPlaceDialog(original: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val field = rememberTextFieldState()
    LaunchedEffect(original) { field.setTextAndPlaceCursorAtEnd(original) }
    AtomDialog(
        onDismissRequest = onDismiss,
        icon = ChatGlyphs.Edit,
        title = stringResource(R.string.chat_edit_in_place_title),
        dismissLabel = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.action_save_edit),
        confirmEnabled = field.text.isNotBlank(),
        onConfirm = {
            onSave(field.text.toString())
            onDismiss()
        },
    ) {
        Text(stringResource(R.string.chat_edit_in_place_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        DialogField(field, "", singleLine = false)
    }
}

@Composable
private fun DeleteFromDialog(later: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AtomDialog(
        onDismissRequest = onDismiss,
        icon = Icons.Filled.Delete,
        title = stringResource(R.string.chat_delete_message_title),
        dismissLabel = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.action_delete),
        onConfirm = onConfirm,
        destructive = true,
    ) {
        Text(
            if (later > 0) pluralStringResource(R.plurals.chat_delete_message_later, later, later) else stringResource(R.string.chat_delete_message_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ActionIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(icon, description, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun MenuAction(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, style = MaterialTheme.typography.bodyLarge) },
        leadingIcon = { Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        onClick = onClick,
        modifier = Modifier.widthIn(min = 180.dp),
    )
}

// ---------------------------------------------------------------- Notices and live status

@Composable
private fun NoticeRow(text: String, error: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Info, null, Modifier.size(14.dp), tint = if (error) colors.error else colors.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (error) colors.onSurface else colors.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun CompactedRow(key: String, summary: String) {
    val colors = MaterialTheme.colorScheme
    var open by rememberSaveable(key) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).clickable { open = !open }.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(ChatGlyphs.Compress, null, Modifier.size(14.dp), tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.chat_compacted), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
        if (open) {
            Surface(color = colors.surfaceContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                SelectionContainer {
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(14.dp))
                }
            }
        }
    }
}

@Composable
private fun WorkingRow(state: ChatUiState, onPause: () -> Unit, onResume: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val retry = state.retry
    val paused = state.phase == RunPhase.Paused
    val label = when {
        state.pending != null -> stringResource(R.string.status_waiting_approval)
        paused -> stringResource(R.string.run_paused_hint)
        state.phase == RunPhase.WaitingForControl -> stringResource(R.string.run_waiting_control)
        retry != null -> stringResource(R.string.run_retrying, retry.attempt, retry.of, rememberCountdown(retry.untilMillis))
        state.compacting -> stringResource(R.string.run_compacting)
        state.currentTool != null -> humanizeTool(state.currentTool)
        state.phase == RunPhase.Starting -> stringResource(R.string.status_preparing)
        state.replyingLabel != null -> state.replyingLabel
        else -> stringResource(R.string.status_thinking)
    }
    Column(Modifier.padding(top = 12.dp, bottom = 4.dp, start = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (paused) Dot(MaterialTheme.extras.warning) else WorkingDots()
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
            Surface(onClick = if (paused) onResume else onPause, shape = RoundedCornerShape(50), color = colors.surfaceContainerHigh) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (paused) ChatGlyphs.Play else ChatGlyphs.Pause, null, Modifier.size(13.dp), tint = colors.onSurface)
                    Spacer(Modifier.width(5.dp))
                    Text(stringResource(if (paused) R.string.run_resume else R.string.run_pause), style = MaterialTheme.typography.labelMedium, color = colors.onSurface)
                }
            }
        }
        if (retry != null) {
            Text(
                retry.reason,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.padding(start = 30.dp, top = 4.dp),
                maxLines = 2,
            )
        }
        if (state.stats.inputTokens > 0) {
            Text(
                stringResource(R.string.usage_tokens, formatTokens(state.stats.inputTokens.toInt()), formatTokens(state.stats.outputTokens.toInt())),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 30.dp, top = 2.dp),
            )
        }
    }
}
