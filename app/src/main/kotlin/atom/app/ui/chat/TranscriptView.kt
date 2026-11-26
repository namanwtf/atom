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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.collectAsState
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
import atom.core.agent.SessionStats
import androidx.compose.material3.minimumInteractiveComponentSize
import atom.core.tool.Risk
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloat
import atom.ui.components.AtomMenu
import atom.ui.components.glassSource
import atom.ui.feature.RunPhase
import atom.ui.feature.StepState
import atom.ui.theme.AtomIcons
import atom.ui.theme.extras
import dev.chrisbanes.haze.HazeState
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
    /** The stats pill under the latest answer opens the session stats. */
    val onStats: (() -> Unit)? = null,
    val stats: SessionStats = SessionStats(),
    /** Stored index of the user message being edited (its bubble gets an accent border). */
    val editingIndex: Int? = null,
    /** "Continue editing" in the image viewer: puts a chat image back into the composer. */
    val onContinueEditing: ((String) -> Unit)? = null,
    /** Conversation whose live browser the "Open current browser" step action opens. */
    val conversationId: String? = null,
)

private const val PAGE = 40

@Composable
internal fun TranscriptList(
    state: ChatUiState,
    bars: PaddingValues,
    glass: HazeState,
    cover: BarCover,
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
    // The stats pill goes under the newest answer only: the numbers are for the whole session.
    val lastTurnKey = remember(all) { all.lastOrNull { it is ChatItem.Turn }?.key }

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

    // Tell the bars whether text sits under them. The list is reversed: forward is towards older messages (up).
    LaunchedEffect(listState, cover) {
        snapshotFlow { listState.canScrollForward to listState.canScrollBackward }
            .distinctUntilChanged()
            .collect { (older, newer) ->
                cover.top = older
                cover.bottom = newer
            }
    }
    DisposableEffect(cover) {
        onDispose {
            cover.top = false
            cover.bottom = false
        }
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
            modifier = Modifier.fillMaxSize().glassSource(glass),
            // Past the bars and their fading edges, so the first and last messages come fully clear of them.
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = bars.calculateTopPadding() + 20.dp, bottom = bars.calculateBottomPadding() + 14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.Bottom),
        ) {
            if (state.running) {
                item(key = "working", contentType = "working") { WorkingRow(state, onPause, onResume) }
            }
            items(reversed, key = { it.key }, contentType = { it::class }) { item ->
                when (item) {
                    is ChatItem.User -> UserBubble(item, actions, state.canEdit)
                    is ChatItem.Turn -> TurnView(item, actions, state.canEdit, showStats = item.key == lastTurnKey && !state.running)
                    is ChatItem.Notice -> NoticeRow(item.text, item.error)
                    is ChatItem.Compacted -> CompactedRow(item.key, item.summary)
                }
            }
            if (hasOlder) item(key = "older", contentType = "older") { OlderRow { window += PAGE } }
        }
        val awayFromBottom by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 400 } }
        AnimatedVisibility(
            visible = awayFromBottom,
            enter = fadeIn(tween(160)) + scaleIn(tween(180), initialScale = 0.82f),
            exit = fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.86f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bars.calculateBottomPadding() + 8.dp),
        ) {
            GlassIconButton(
                ChatGlyphs.ArrowDown,
                stringResource(R.string.chat_back_to_bottom),
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                size = 40.dp,
                iconSize = 18.dp,
            )
        }
    }
}

/** Whether transcript content currently sits under the top bar and under the composer (drives their blur). */
@Stable
internal class BarCover {
    var top by mutableStateOf(false)
    var bottom by mutableStateOf(false)
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
    val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 4.dp, bottomStart = 22.dp)
    val editingThis = actions.editingIndex == item.index
    Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp), horizontalAlignment = Alignment.End) {
        if (item.steering) {
            Text(
                stringResource(R.string.chat_steered),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(end = 6.dp, bottom = 4.dp),
            )
        }
        val images = remember(item.attachments) { item.attachments.filter { it.kind == AttachmentKind.Image } }
        val refs = remember(item.attachments) { item.attachments.filter { it.kind != AttachmentKind.Image } }
        if (images.isNotEmpty()) {
            var viewing by remember { mutableStateOf<String?>(null) }
            FlowRow(
                Modifier.padding(start = 40.dp, bottom = 6.dp).widthIn(max = 316.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                images.forEach { img ->
                    val (w, h) = when (images.size) {
                        1 -> 176.dp to 216.dp
                        2 -> 118.dp to 148.dp
                        else -> 98.dp to 124.dp
                    }
                    ChatImageThumb(img.path, w, h, corner = if (images.size == 1) 16.dp else 14.dp) { viewing = img.path }
                }
            }
            viewing?.let { path ->
                ChatImageViewer(
                    path,
                    onDismiss = { viewing = null },
                    onContinueEditing = actions.onContinueEditing?.let { reattach -> { reattach(path) } },
                )
            }
        }
        if (refs.isNotEmpty()) {
            FlowRow(
                Modifier.padding(start = 48.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                refs.forEach { AttachmentChip(it, onRemove = null) }
            }
        }
        Box {
            if (item.text.isNotBlank()) {
                Surface(
                    color = colors.surfaceContainerHigh,
                    contentColor = colors.onSurface,
                    shape = shape,
                    border = if (editingThis) androidx.compose.foundation.BorderStroke(1.dp, colors.primary) else null,
                    modifier = Modifier
                        .padding(start = 40.dp)
                        .widthIn(max = 316.dp)
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
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.5.dp)) {
                        Text(item.text, fontSize = 16.sp, lineHeight = 23.sp)
                        if (item.edited) {
                            Text(
                                stringResource(R.string.chat_edited),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
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
private fun TurnView(turn: ChatItem.Turn, actions: MessageActions, canEdit: Boolean, showStats: Boolean) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp)) {
        if (turn.work.isNotEmpty()) Timeline(turn, actions)
        TurnCards(turn, actions.onTakeOverBrowser)
        turn.answerReasoning?.let { ReasoningBlock(it) }
        val answer = turn.answer
        if (answer != null) {
            val shownText = if (turn.streaming) smoothReveal(answer) else answer
            SelectionContainer {
                MarkdownText(shownText, Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp, end = 4.dp))
            }
        }
        turn.answerSuggestions?.let { SearchSuggestionsRow(it) }
        // The action row fades in once the answer has finished revealing.
        AnimatedVisibility(
            visible = turn.finished && (answer != null || turn.work.isNotEmpty()),
            enter = fadeIn(tween(160)) + expandVertically(tween(160), expandFrom = Alignment.Top),
            exit = fadeOut(tween(100)),
        ) {
            TurnActions(turn, actions, canEdit, showStats)
        }
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
private fun Timeline(turn: ChatItem.Turn, actions: MessageActions) {
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
            val ink = homeInk()
            val runningCall = remember(turn.work) {
                turn.work.filterIsInstance<WorkEntry.Steps>().flatMap { it.calls }.lastOrNull { it.state == StepState.Running }
            }
            Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) {
                Icon(
                    when {
                        !turn.live -> HomeGlyphs.Checklist
                        runningCall != null -> stepIcon(runningCall)
                        else -> HomeGlyphs.Lightbulb
                    },
                    null,
                    Modifier.size(14.5.dp),
                    tint = if (turn.live) colors.primary else ink.ink(0.50f, 0.45f),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(workHeader(turn.live, summary.toolCalls, summary.failed, runningCall), fontSize = 13.5.sp, color = ink.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(4.dp))
            Icon(ChatGlyphs.Chevron, null, Modifier.size(13.dp).rotate(if (open) 90f else 0f), tint = colors.onSurfaceVariant.copy(alpha = 0.5f))
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
                        is WorkEntry.Steps -> StepGroup(
                            entry,
                            onOpenBrowser = if (turn.live) actions.onTakeOverBrowser else null,
                            conversationId = actions.conversationId.takeIf { turn.live },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The work header: "Analyzing task" before the first tool, "Working · step N · <tool>" while steps run, then
 * "Completed N steps" (with failures) or "Analysis complete" when nothing was called.
 */
@Composable
private fun workHeader(live: Boolean, calls: Int, failed: Int, running: StepCall?): String {
    if (live) {
        return when {
            running != null -> stringResource(R.string.work_step_tool, calls.coerceAtLeast(1), running.argsLine.takeIf { it.isNotBlank() && it.length <= 48 } ?: humanizeTool(running.name))
            calls > 0 -> stringResource(R.string.work_step, calls + 1)
            else -> stringResource(R.string.work_analyzing)
        }
    }
    if (calls == 0) return stringResource(R.string.work_done)
    val done = pluralStringResource(R.plurals.work_completed, calls, calls)
    return if (failed > 0) done + " · " + stringResource(R.string.timeline_failed, failed) else done
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

/**
 * The answer's reasoning as a card: "Reasoning…" with the last four lines while it streams, then folded to
 * "Reasoning completed · N seconds". A tap opens it fully; the user's choice sticks.
 */
@Composable
private fun ReasoningBlock(t: WorkEntry.Thought) {
    val colors = MaterialTheme.colorScheme
    val ink = homeInk()
    var chosen by rememberSaveable(t.key) { mutableStateOf<Boolean?>(null) }
    val open = chosen ?: t.live
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(shape)
            .background(colors.surface)
            .border(0.5.dp, colors.outline.copy(alpha = 0.5f), shape),
    ) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { chosen = !open }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val bulb = if (t.live) colors.primary else colors.onSurfaceVariant
            Icon(HomeGlyphs.Lightbulb, null, Modifier.size(15.dp).then(if (t.live) Modifier.pulsing() else Modifier), tint = bulb)
            Spacer(Modifier.width(8.dp))
            Text(
                reasoningLabel(t),
                fontSize = 13.5.sp,
                color = if (t.live) colors.onSurface else ink.muted,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(4.dp))
            Icon(ChatGlyphs.Chevron, null, Modifier.size(13.dp).rotate(if (open) 90f else 0f), tint = colors.onSurfaceVariant.copy(alpha = 0.5f))
        }
        AnimatedVisibility(visible = open, enter = fadeIn(tween(160)) + expandVertically(), exit = fadeOut(tween(120)) + shrinkVertically()) {
            Column {
                Box(Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(0.5.dp).background(colors.outlineVariant))
                val body = if (t.live && chosen == null) remember(t.text) { t.text.lines().filter { it.isNotBlank() }.takeLast(4).joinToString("\n") } else t.text
                SelectionContainer {
                    Text(
                        body,
                        fontSize = 13.5.sp,
                        lineHeight = 21.sp,
                        color = ink.muted,
                        maxLines = if (t.live && chosen == null) 4 else Int.MAX_VALUE,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
                    )
                }
            }
        }
    }
}

/** Alpha 0.58 ↔ 1 over 820 ms while something is live. */
@Composable
private fun Modifier.pulsing(): Modifier {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse")
    val alpha = transition.animateFloat(
        initialValue = 0.58f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            tween(820, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )
    return this.graphicsLayer { this.alpha = alpha.value }
}

@Composable
private fun StepGroup(group: WorkEntry.Steps, onOpenBrowser: (() -> Unit)? = null, conversationId: String? = null) {
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
                Text(pluralStringResource(R.plurals.work_calls, group.calls.size, group.calls.size), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
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
        val browser = group.tool.startsWith(BROWSER_TOOL_PREFIX)
        if (browser && running != null && conversationId != null) BrowserLoadingLine(conversationId)
        AnimatedVisibility(visible = open, enter = fadeIn(tween(QUICK)) + expandVertically(tween(200)), exit = fadeOut(tween(150)) + shrinkVertically(tween(180))) {
            Column(Modifier.padding(start = 25.dp, top = 10.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                group.calls.forEach { CallDetails(it, numbered = group.calls.size > 1) }
                if (browser && onOpenBrowser != null) {
                    atom.ui.components.CompactButton(stringResource(R.string.browser_open_current), onClick = onOpenBrowser, primary = false)
                }
            }
        }
    }
}

private const val BROWSER_TOOL_PREFIX = "browser_"

/** "Loading · N%" under a running browser step while its page loads. */
@Composable
private fun BrowserLoadingLine(conversationId: String) {
    // The step row appears before browser_open creates the session, and a session can be evicted and recreated:
    // look it up again whenever the set of live sessions changes.
    val active by atom.web.browser.BrowserSessions.active.collectAsState()
    val session = remember(conversationId, active) { atom.web.browser.BrowserSessions.live(conversationId) } ?: return
    val page by session.page.collectAsState()
    if (!page.loading) return
    Text(
        stringResource(R.string.browser_loading, page.progress.coerceIn(0, 100)),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 25.dp, top = 4.dp),
    )
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
                style = MaterialTheme.extras.mono.copy(fontFamily = atom.linux.ui.MonoFont.JetBrainsMono),
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

internal fun stepIcon(step: StepCall): ImageVector {
    val name = step.name.lowercase()
    return when {
        Transcript.isTerminal(name) -> AtomIcons.Terminal
        "screen" in name || "read" in name || "find" in name || "list" in name || "search" in name -> AtomIcons.Eye
        "url" in name || "web" in name || "browse" in name -> AtomIcons.Link
        step.risk == Risk.Sensitive -> Icons.Filled.Lock
        else -> AtomIcons.Touch
    }
}

/**
 * Under the final answer: Copy, Read aloud, Edit (character replies), Regenerate, Delete, Branch, then the stats
 * pill on the newest answer and, for character replies with alternatives, the candidate switcher on the right.
 */
@Composable
private fun TurnActions(turn: ChatItem.Turn, actions: MessageActions, canEdit: Boolean, showStats: Boolean) {
    val copyText = actions.textAt(turn.answerIndex) ?: turn.answer
    var confirmRegenerate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editInPlace by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val userIndex = turn.userIndex
    val answerIndex = turn.answerIndex
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_400)
            copied = false
        }
    }
    // 48dp touch targets around 16dp icons, shifted left so the first icon lines up with the answer text. The row
    // wraps, so the stats pill or the reply switcher moves to a second line on narrow screens instead of clipping.
    FlowRow(
        Modifier.fillMaxWidth().offset(x = (-16).dp),
        verticalArrangement = Arrangement.Center,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (copyText != null) {
            ActionIcon(
                if (copied) HomeGlyphs.Check else HomeGlyphs.Copy,
                stringResource(if (copied) R.string.chat_copied else R.string.chat_copy_answer),
                tint = if (copied) MaterialTheme.colorScheme.primary else null,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                actions.onCopy(copyText)
                copied = true
            }
        }
        val readAloud = actions.onReadAloud
        if (readAloud != null && copyText != null) {
            if (actions.readingId == turn.key) {
                ActionIcon(HomeGlyphs.StopLine, stringResource(R.string.chat_stop_reading)) { actions.onStopReading() }
            } else {
                ActionIcon(HomeGlyphs.Volume, stringResource(R.string.chat_read_aloud)) { readAloud(copyText, turn.key) }
            }
        }
        if (canEdit && answerIndex != null && actions.onEditInPlace != null && copyText != null) {
            ActionIcon(HomeGlyphs.EditLine, stringResource(R.string.chat_edit)) { editInPlace = true }
        }
        val regenerate = actions.onRegenerate
        if (canEdit && regenerate != null && userIndex != null) {
            ActionIcon(HomeGlyphs.Refresh, stringResource(R.string.chat_regenerate)) {
                if (actions.laterTurns(userIndex) > 0) confirmRegenerate = true else regenerate(turn)
            }
        }
        if (canEdit && actions.onDeleteFrom != null && userIndex != null) {
            ActionIcon(HomeGlyphs.Trash, stringResource(R.string.chat_delete_turn)) { confirmDelete = true }
        }
        val branch = actions.onBranch
        if (canEdit && branch != null && answerIndex != null) {
            ActionIcon(HomeGlyphs.Branch, stringResource(R.string.chat_branch)) { branch(turn) }
        }
        val set = answerIndex?.let { actions.candidates[it] }
        val pick = actions.onCandidate
        if (set != null && pick != null && answerIndex != null) {
            // A reply with alternatives shows its switcher here; the session stats stay in the overflow menu.
            CandidateSwitcher(set, enabled = canEdit, onPrevious = { pick(answerIndex, set.selected - 1) }, onNext = { pick(answerIndex, set.selected + 1) })
        } else {
            val onStats = actions.onStats
            val pill = if (showStats) statsPill(actions.stats) else null
            if (pill != null && onStats != null) StatsPill(pill, onStats)
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

/**
 * Google Search suggestions attached to a grounded Gemini answer. The search provider's terms ask for them to be
 * shown: one chip per query opening the search, or the provider's own rendered chip when no queries came with it.
 */
@Composable
private fun SearchSuggestionsRow(s: atom.core.llm.SearchSuggestions) {
    val colors = MaterialTheme.colorScheme
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val label = stringResource(R.string.chat_search_suggestions)
    if (s.queries.isNotEmpty()) {
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp).semantics { contentDescription = label },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            s.queries.forEach { q ->
                val shape = RoundedCornerShape(10.dp)
                Row(
                    Modifier
                        .clip(shape)
                        .background(colors.surface)
                        .border(0.5.dp, colors.outline.copy(alpha = 0.55f), shape)
                        .clickable { runCatching { uri.openUri(s.searchUrl(q)) } }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(ChatGlyphs.Search, null, Modifier.size(12.dp), tint = colors.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(q, fontSize = 13.sp, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        return
    }
    val html = s.renderedContent?.takeIf { it.isNotBlank() } ?: return
    // The provider's own markup, shown as given in a WebView with scripts off; links open in the browser.
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { context ->
            android.webkit.WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: android.webkit.WebView, request: android.webkit.WebResourceRequest): Boolean {
                        val url = request.url
                        if (url.scheme == "https" || url.scheme == "http") {
                            runCatching {
                                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                        return true
                    }
                }
                loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        modifier = Modifier.fillMaxWidth().height(64.dp).padding(vertical = 4.dp).semantics { contentDescription = label },
    )
}

/** A 16dp line icon in summary grey inside a 48dp touch target. */
@Composable
private fun ActionIcon(icon: ImageVector, description: String, tint: androidx.compose.ui.graphics.Color? = null, onClick: () -> Unit) {
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = androidx.compose.material3.ripple(bounded = false, radius = 16.dp),
                onClick = onClick,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f))
    }
}

/** "TTFT 1.2 s · 48 tok/s" for the session, or null before anything was measured. */
@Composable
private fun statsPill(stats: SessionStats): String? {
    val ttft = stats.averageTtftMs?.let { formatDuration(it) }
    val speed = stats.outputTokensPerSecond?.let { String.format(java.util.Locale.getDefault(), "%.0f", it) }
    return when {
        ttft != null && speed != null -> stringResource(R.string.chat_stats_pill, ttft, speed)
        ttft != null -> stringResource(R.string.chat_stats_ttft, ttft)
        speed != null -> stringResource(R.string.chat_stats_speed, speed)
        stats.modelTimeMs > 0 -> formatDuration(stats.modelTimeMs)
        else -> null
    }
}

@Composable
private fun StatsPill(text: String, onClick: () -> Unit) {
    val ink = homeInk()
    val description = stringResource(R.string.menu_stats)
    Row(
        Modifier
            .height(24.dp)
            .clip(RoundedCornerShape(50))
            .background(ink.ink(0.06f, 0.038f))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(HomeGlyphs.Gauge, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(text, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
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

/** Names of tools a provider runs on its side, as they appear in "Running <x>". */
internal fun hostedToolRes(kind: atom.core.llm.HostedToolKind): Int = when (kind) {
    atom.core.llm.HostedToolKind.WebSearch -> R.string.hosted_web_search
    atom.core.llm.HostedToolKind.FileSearch -> R.string.hosted_file_search
    atom.core.llm.HostedToolKind.CodeExecution -> R.string.hosted_code_execution
    atom.core.llm.HostedToolKind.ComputerUse -> R.string.hosted_computer_use
    atom.core.llm.HostedToolKind.ImageGeneration -> R.string.hosted_image_generation
    atom.core.llm.HostedToolKind.Mcp -> R.string.hosted_mcp
}

@Composable
internal fun hostedToolLabel(kind: atom.core.llm.HostedToolKind): String = stringResource(hostedToolRes(kind))

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
        state.hostedTool != null -> stringResource(R.string.hosted_running, hostedToolLabel(state.hostedTool))
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
