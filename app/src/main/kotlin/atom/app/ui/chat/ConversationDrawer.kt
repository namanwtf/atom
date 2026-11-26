@file:OptIn(ExperimentalFoundationApi::class)

package atom.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import atom.app.R
import atom.app.ui.common.humanizeTool
import atom.characters.ui.CharacterAvatar
import atom.core.home.ConversationGroup
import atom.core.home.ConversationGroups
import atom.ui.components.ArrowRow
import atom.ui.components.AtomMenu
import atom.ui.components.Group
import androidx.compose.material3.minimumInteractiveComponentSize
import atom.ui.components.ScreenIcons
import atom.ui.theme.AtomBadge
import atom.ui.theme.AtomIcons

internal class DrawerCallbacks(
    val onNewChat: () -> Unit,
    val onOpen: (String) -> Unit,
    val onPin: (String) -> Unit,
    val onRename: (String, String) -> Unit,
    val onDelete: (String) -> Unit,
    val onExport: (String) -> Unit,
    val onModels: () -> Unit,
    val onCharacters: (() -> Unit)?,
    val onTools: (() -> Unit)?,
    val onSkills: (() -> Unit)?,
    val onScheduled: (() -> Unit)?,
    val onPermissions: () -> Unit,
    val onSettings: () -> Unit,
)

/** Amber badge for "something needs attention". */
private val AttentionAmber = Color(0xFFF9AB00)

/**
 * The conversation pane: a fixed "Atom" header with a new-conversation button, a scrolling body (the workbench
 * card, then Pinned / Today / Yesterday / Recent) and a fixed dock with search, Permissions and Settings.
 * [open] is false while the pane is pushed out of view; closing it leaves search.
 */
@Composable
internal fun ConversationDrawer(
    rows: List<DrawerRow>,
    selectedId: String?,
    query: String,
    onQuery: (String) -> Unit,
    permissionsNeedAttention: Boolean,
    activeModel: String?,
    open: Boolean,
    callbacks: DrawerCallbacks,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val pane = paneColor()
    var renaming by remember { mutableStateOf<DrawerRow?>(null) }
    var deleting by remember { mutableStateOf<DrawerRow?>(null) }
    var searching by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    LaunchedEffect(open) {
        if (!open && searching) {
            searching = false
            onQuery("")
            focus.clearFocus()
        }
    }
    val searchMode = searching || query.isNotBlank()
    val grouped = remember(rows, query) {
        if (query.isNotBlank()) null else ConversationGroups.group(
            rows,
            ConversationGroups.bounds(System.currentTimeMillis()),
            pinned = { it.summary.pinned },
            running = { it.running },
            updatedAt = { it.summary.updatedAt },
        )
    }
    var headerPx by remember { mutableIntStateOf(0) }
    var dockPx by remember { mutableIntStateOf(0) }
    val headerDp = with(density) { headerPx.toDp() }
    val dockDp = with(density) { dockPx.toDp() }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = headerDp + 10.dp, bottom = dockDp + 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (permissionsNeedAttention && !searchMode) {
                item(key = "attention") { AttentionRow(callbacks.onPermissions) }
            }
            if (!searchMode) {
                item(key = "workbench") { Workbench(activeModel, callbacks) }
            }
            if (rows.isEmpty()) {
                item(key = "empty") { EmptyList(searching = query.isNotBlank()) }
            }
            if (grouped == null) {
                items(rows, key = { "q" + it.summary.id }) { row ->
                    ConversationRow(row, row.summary.id == selectedId, callbacks, onRename = { renaming = row }, onDelete = { deleting = row })
                }
            } else {
                grouped.forEachIndexed { i, (group, list) ->
                    item(key = "h" + group.name) {
                        Text(
                            stringResource(
                                when (group) {
                                    ConversationGroup.Pinned -> R.string.drawer_pinned
                                    ConversationGroup.Today -> R.string.drawer_today
                                    ConversationGroup.Yesterday -> R.string.drawer_yesterday
                                    ConversationGroup.Recent -> R.string.chat_recent
                                },
                            ),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(start = 30.dp, end = 30.dp, top = if (i == 0) 19.dp else 17.dp, bottom = 3.dp),
                        )
                    }
                    items(list, key = { it.summary.id }) { row ->
                        ConversationRow(row, row.summary.id == selectedId, callbacks, onRename = { renaming = row }, onDelete = { deleting = row })
                    }
                }
            }
        }

        // Header: brand mark, "Atom" and a new-conversation button; the list scrolls under it.
        Row(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { headerPx = it.height }
                .background(Brush.verticalGradient(0f to pane, 0.8f to pane.copy(alpha = 0.94f), 1f to pane.copy(alpha = 0f)))
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrandBadge()
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.app_name), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface, modifier = Modifier.weight(1f))
            GlassIconButton(HomeGlyphs.NewMessage, stringResource(R.string.drawer_new_chat), callbacks.onNewChat, size = 40.dp)
        }

        // Dock: search pill plus Permissions and Settings; the list scrolls under it.
        Dock(
            modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { dockPx = it.height },
            pane = pane,
            query = query,
            onQuery = onQuery,
            searching = searching,
            onSearching = { searching = it },
            attention = permissionsNeedAttention,
            callbacks = callbacks,
        )
    }

    renaming?.let { row ->
        val field = rememberTextFieldState(row.summary.title)
        AtomDialog(
            onDismissRequest = { renaming = null },
            icon = ChatGlyphs.Edit,
            title = stringResource(R.string.drawer_rename_title),
            dismissLabel = stringResource(R.string.action_cancel),
            onDismiss = { renaming = null },
            confirmLabel = stringResource(R.string.action_save),
            confirmEnabled = field.text.isNotBlank(),
            onConfirm = {
                callbacks.onRename(row.summary.id, field.text.toString())
                renaming = null
            },
        ) {
            DialogField(field, stringResource(R.string.drawer_rename_hint))
        }
    }
    deleting?.let { row ->
        AtomDialog(
            onDismissRequest = { deleting = null },
            icon = Icons.Filled.Delete,
            title = stringResource(R.string.delete_conversation_title),
            dismissLabel = stringResource(R.string.action_cancel),
            onDismiss = { deleting = null },
            confirmLabel = stringResource(if (row.running) R.string.drawer_stop_and_delete else R.string.action_delete),
            destructive = true,
            onConfirm = {
                callbacks.onDelete(row.summary.id)
                deleting = null
            },
        ) {
            Text(
                stringResource(R.string.delete_conversation_text, row.summary.title.ifBlank { stringResource(R.string.drawer_untitled) }) +
                    if (row.running) "\n\n" + stringResource(R.string.drawer_stop_and_delete_text) else "",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

/** The emblem on a black disc, as on the launcher icon. */
@Composable
private fun BrandBadge() {
    AtomBadge(30.dp)
}

@Composable
private fun AttentionRow(onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(AttentionAmber, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.drawer_permissions_attention), fontSize = 14.sp, color = colors.onSurface, modifier = Modifier.weight(1f))
        Icon(ChatGlyphs.Chevron, null, Modifier.size(14.dp), tint = colors.onSurfaceVariant)
    }
}

/** The workbench card: Models (with the active model), Characters, Tools, Skills, Scheduled Tasks. */
@Composable
private fun Workbench(activeModel: String?, callbacks: DrawerCallbacks) {
    val colors = MaterialTheme.colorScheme
    val entries = buildList<Triple<ImageVector, Int, () -> Unit>> {
        add(Triple(AtomIcons.Chip, R.string.screen_models, callbacks.onModels))
        callbacks.onCharacters?.let { add(Triple(Icons.Outlined.Person, R.string.drawer_characters, it)) }
        callbacks.onTools?.let { add(Triple(Icons.Outlined.Build, R.string.drawer_tools, it)) }
        callbacks.onSkills?.let { add(Triple(Icons.Outlined.AutoAwesome, R.string.drawer_skills, it)) }
        callbacks.onScheduled?.let { add(Triple(Icons.Outlined.Schedule, R.string.drawer_scheduled, it)) }
    }
    // The foundation's Miuix card and arrow rows (icon, title, end value, arrow).
    Group(Modifier.padding(horizontal = 16.dp)) {
        entries.forEachIndexed { i, (icon, title, onClick) ->
            ArrowRow(
                title = stringResource(title),
                onClick = onClick,
                icon = icon,
                value = if (i == 0) activeModel ?: stringResource(R.string.home_not_set) else null,
            )
        }
    }
}

@Composable
private fun EmptyList(searching: Boolean) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = 36.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (searching) {
            Box(Modifier.size(44.dp).background(colors.surfaceContainer, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Icon(ChatGlyphs.Search, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
            }
        } else {
            AtomBadge(56.dp)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(if (searching) R.string.drawer_no_matches else R.string.drawer_empty),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = colors.onSurfaceVariant,
        )
        if (!searching) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.drawer_empty_hint), fontSize = 12.sp, color = colors.onSurfaceVariant.copy(alpha = 0.75f))
        }
    }
}

@Composable
private fun ConversationRow(
    row: DrawerRow,
    selected: Boolean,
    callbacks: DrawerCallbacks,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val ink = homeInk()
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val s = row.summary
    val shape = RoundedCornerShape(14.dp)
    val selectedFill = ink.accent(0.16f, 0.10f)
    Box(Modifier.padding(horizontal = 16.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(if (selected) selectedFill else Color.Transparent)
                .combinedClickable(
                    onClick = { callbacks.onOpen(s.id) },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    },
                    onLongClickLabel = stringResource(R.string.chat_message_actions),
                )
                .heightIn(min = 44.dp)
                .padding(start = 14.dp, end = if (selected) 8.dp else 14.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            row.character?.let { c ->
                val ring = if (ink.dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f)
                CharacterAvatar(c, Modifier.size(24.dp).border(0.5.dp, ring, CircleShape).clip(CircleShape))
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                val title = s.title.ifBlank { s.preview.ifBlank { stringResource(R.string.drawer_untitled) } }
                Text(
                    title,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val characterName = row.character?.displayName?.takeIf { it != title }
                if (characterName != null) {
                    Text(characterName, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
                val status = when (val a = row.activity) {
                    DrawerActivity.Reasoning -> stringResource(R.string.drawer_reasoning)
                    DrawerActivity.Waiting -> stringResource(R.string.status_waiting_approval)
                    is DrawerActivity.Tool -> stringResource(R.string.drawer_using_tool, humanizeTool(a.name))
                    is DrawerActivity.Answering -> a.text
                    null -> null
                }
                if (status != null) {
                    Text(status, fontSize = 12.sp, color = colors.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                } else {
                    // Search hit, or the last message; skipped when the title already shows it.
                    val preview = (row.snippet ?: s.preview).replace('\n', ' ').trim()
                    if (preview.isNotEmpty() && preview != title) {
                        Text(preview, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
            when {
                row.running -> {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(6.dp).background(colors.primary, CircleShape))
                }
                s.pinned -> {
                    Spacer(Modifier.width(8.dp))
                    Icon(ChatGlyphs.Pin, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant.copy(alpha = 0.72f))
                }
            }
            if (selected) {
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier.minimumInteractiveComponentSize().size(28.dp).clip(CircleShape).clickable { menu = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.MoreHoriz, stringResource(R.string.drawer_more), Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                }
            }
        }
        if (selected) {
            // Selection indicator in the left padding.
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = 5.5.dp)
                    .size(width = 2.5.dp, height = 16.dp)
                    .background(colors.primary, RoundedCornerShape(2.dp)),
            )
        }
        AtomMenu(expanded = menu, onDismiss = { menu = false }) {
            MenuAction(stringResource(if (s.pinned) R.string.drawer_unpin else R.string.drawer_pin), ChatGlyphs.Pin) {
                menu = false
                callbacks.onPin(s.id)
            }
            MenuAction(stringResource(R.string.drawer_rename), ChatGlyphs.Edit) {
                menu = false
                onRename()
            }
            MenuAction(stringResource(R.string.drawer_export), ChatGlyphs.Export) {
                menu = false
                callbacks.onExport(s.id)
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), thickness = 0.5.dp, color = colors.outlineVariant)
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_delete), style = MaterialTheme.typography.bodyLarge, color = colors.error) },
                leadingIcon = { Icon(Icons.Filled.Delete, null, Modifier.size(18.dp), tint = colors.error) },
                onClick = {
                    menu = false
                    onDelete()
                },
                modifier = Modifier.widthIn(min = 180.dp),
            )
        }
    }
}

/** Search pill, then Permissions (with an amber badge when something needs attention) and Settings. */
@Composable
private fun Dock(
    modifier: Modifier,
    pane: Color,
    query: String,
    onQuery: (String) -> Unit,
    searching: Boolean,
    onSearching: (Boolean) -> Unit,
    attention: Boolean,
    callbacks: DrawerCallbacks,
) {
    val focus = LocalFocusManager.current
    val motion = spring<androidx.compose.ui.unit.IntOffset>(dampingRatio = 0.88f, stiffness = 360f)
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to pane.copy(alpha = 0f), 0.25f to pane.copy(alpha = 0.72f), 0.55f to pane.copy(alpha = 0.94f), 1f to pane))
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchCapsule(query, onQuery, onFocus = { if (it) onSearching(true) }, modifier = Modifier.weight(1f))
        AnimatedVisibility(
            visible = !searching,
            enter = fadeIn(tween(160)) + scaleIn(initialScale = 0.8f) + slideInHorizontally(motion) { it / 2 },
            exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.8f) + slideOutHorizontally(motion) { it / 2 },
        ) {
            Row {
                Spacer(Modifier.width(8.dp))
                Box {
                    GlassIconButton(ScreenIcons.Shield, stringResource(R.string.drawer_permissions), callbacks.onPermissions)
                    if (attention) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = (-7).dp, y = 7.dp)
                                .size(9.dp)
                                .border(1.5.dp, pane, CircleShape)
                                .background(AttentionAmber, CircleShape),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                GlassIconButton(Icons.Outlined.Settings, stringResource(R.string.screen_settings), callbacks.onSettings)
            }
        }
        AnimatedVisibility(
            visible = searching,
            enter = fadeIn(tween(160)) + scaleIn(initialScale = 0.8f),
            exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.8f),
        ) {
            Row {
                Spacer(Modifier.width(8.dp))
                GlassIconButton(Icons.Filled.Close, stringResource(R.string.drawer_close_search), {
                    onQuery("")
                    onSearching(false)
                    focus.clearFocus()
                })
            }
        }
    }
}

@Composable
private fun SearchCapsule(query: String, onQuery: (String) -> Unit, onFocus: (Boolean) -> Unit, modifier: Modifier) {
    // A 44dp single-line pill: the foundation's search field wraps "Search conversations" next to two dock buttons.
    val colors = MaterialTheme.colorScheme
    val ink = homeInk()
    val field = rememberTextFieldState(query)
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(field) { snapshotFlow { field.text.toString() }.collect(onQuery) }
    LaunchedEffect(query) { if (query.isEmpty() && field.text.isNotEmpty()) field.setTextAndPlaceCursorAtEnd("") }
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .height(44.dp)
            .clip(shape)
            .background(ink.glassButton)
            .border(0.5.dp, ink.glassEdge, shape)
            .clickable { focusRequester.requestFocus() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(14.dp))
        Icon(ChatGlyphs.Search, null, Modifier.size(20.dp), tint = if (focused) colors.primary else colors.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            val style = TextStyle(fontSize = 15.sp, lineHeight = 20.sp)
            if (field.text.isEmpty()) {
                Text(stringResource(R.string.drawer_search), style = style, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                state = field,
                textStyle = style.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                lineLimits = TextFieldLineLimits.SingleLine,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged {
                        focused = it.isFocused
                        onFocus(it.isFocused)
                    },
            )
        }
        if (field.text.isNotEmpty()) {
            val clear = stringResource(R.string.drawer_clear_search)
            Box(
                Modifier
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .clickable { field.setTextAndPlaceCursorAtEnd("") }
                    .semantics { contentDescription = clear },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
            }
        } else {
            Spacer(Modifier.width(14.dp))
        }
    }
}
