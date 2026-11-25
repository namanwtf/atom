@file:OptIn(ExperimentalFoundationApi::class)

package atom.app.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import atom.app.R
import atom.app.ui.common.humanizeTool
import atom.ui.components.AtomMenu
import atom.ui.components.ScreenIcons
import atom.ui.theme.AtomIcons
import atom.ui.theme.AtomMark
import atom.ui.theme.extras
import java.util.Calendar

internal class DrawerCallbacks(
    val onNewChat: () -> Unit,
    val onOpen: (String) -> Unit,
    val onPin: (String) -> Unit,
    val onRename: (String, String) -> Unit,
    val onDelete: (String) -> Unit,
    val onExport: (String) -> Unit,
    val onModels: () -> Unit,
    val onCharacters: (() -> Unit)?,
    val onPermissions: () -> Unit,
    val onSettings: () -> Unit,
)

private enum class Bucket { Pinned, Today, Yesterday, Recent }

@Composable
internal fun ConversationDrawer(
    rows: List<DrawerRow>,
    selectedId: String?,
    query: String,
    onQuery: (String) -> Unit,
    permissionsNeedAttention: Boolean,
    callbacks: DrawerCallbacks,
) {
    val colors = MaterialTheme.colorScheme
    var renaming by remember { mutableStateOf<DrawerRow?>(null) }
    var deleting by remember { mutableStateOf<DrawerRow?>(null) }
    val grouped = remember(rows, query) { if (query.isNotBlank()) null else group(rows) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            AtomMark(Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
        }
        NewChatButton(callbacks.onNewChat, Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(10.dp))
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (rows.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(if (query.isBlank()) R.string.drawer_empty else R.string.drawer_no_matches),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
            if (grouped == null) {
                items(rows, key = { "q" + it.summary.id }) { row ->
                    ConversationRow(row, row.summary.id == selectedId, callbacks, onRename = { renaming = row }, onDelete = { deleting = row })
                }
            } else {
                grouped.forEach { (bucket, list) ->
                    item(key = "h" + bucket.name) {
                        Text(
                            stringResource(
                                when (bucket) {
                                    Bucket.Pinned -> R.string.drawer_pinned
                                    Bucket.Today -> R.string.drawer_today
                                    Bucket.Yesterday -> R.string.drawer_yesterday
                                    Bucket.Recent -> R.string.chat_recent
                                },
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp),
                        )
                    }
                    items(list, key = { it.summary.id }) { row ->
                        ConversationRow(row, row.summary.id == selectedId, callbacks, onRename = { renaming = row }, onDelete = { deleting = row })
                    }
                }
            }
        }
        DockRow(AtomIcons.Chip, stringResource(R.string.screen_models), callbacks.onModels)
        callbacks.onCharacters?.let { DockRow(Icons.Filled.Face, stringResource(R.string.drawer_characters), it) }
        if (permissionsNeedAttention) {
            Row(
                Modifier.padding(horizontal = 26.dp, vertical = 6.dp).clip(RoundedCornerShape(50)).clickable(onClick = callbacks.onPermissions),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Dot(MaterialTheme.extras.warning)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.drawer_permissions_attention), style = MaterialTheme.typography.labelMedium, color = colors.onSurface)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchCapsule(query, onQuery, Modifier.weight(1f))
            RoundButton(ScreenIcons.Shield, stringResource(R.string.drawer_permissions), callbacks.onPermissions)
            RoundButton(Icons.Filled.Settings, stringResource(R.string.screen_settings), callbacks.onSettings)
        }
    }

    renaming?.let { row ->
        val field = rememberTextFieldState(row.summary.title)
        AtomDialog(
            onDismissRequest = { renaming = null },
            icon = ChatGlyphs.Edit,
            title = stringResource(R.string.drawer_rename),
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

private fun group(rows: List<DrawerRow>): List<Pair<Bucket, List<DrawerRow>>> {
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val today = cal.timeInMillis
    val yesterday = today - 24L * 60 * 60 * 1000
    val buckets = rows.groupBy { r ->
        when {
            r.summary.pinned -> Bucket.Pinned
            r.summary.updatedAt >= today || r.running -> Bucket.Today
            r.summary.updatedAt >= yesterday -> Bucket.Yesterday
            else -> Bucket.Recent
        }
    }
    return Bucket.entries.mapNotNull { b -> buckets[b]?.takeIf { it.isNotEmpty() }?.let { b to it } }
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
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val background by animateColorAsState(if (selected) colors.surfaceContainerHigh else Color.Transparent, tween(QUICK), label = "rowBg")
    val s = row.summary
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(background)
                .combinedClickable(
                    onClick = { callbacks.onOpen(s.id) },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    },
                    onLongClickLabel = stringResource(R.string.chat_message_actions),
                )
                .heightIn(min = 56.dp)
                .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (s.pinned) {
                        Icon(ChatGlyphs.Pin, null, Modifier.size(12.dp), tint = colors.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        s.title.ifBlank { stringResource(R.string.drawer_untitled) },
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val preview = when (val a = row.activity) {
                    DrawerActivity.Reasoning -> stringResource(R.string.drawer_reasoning)
                    DrawerActivity.Waiting -> stringResource(R.string.status_waiting_approval)
                    is DrawerActivity.Tool -> stringResource(R.string.drawer_using_tool, humanizeTool(a.name))
                    is DrawerActivity.Answering -> a.text
                    null -> row.snippet ?: s.preview
                }
                if (preview.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (row.running) {
                            RunningDot()
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(preview, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
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
            MenuAction(stringResource(R.string.action_delete), Icons.Filled.Delete) {
                menu = false
                onDelete()
            }
        }
    }
}

@Composable
private fun SearchCapsule(query: String, onQuery: (String) -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val field = rememberTextFieldState(query)
    LaunchedEffect(field) { snapshotFlow { field.text.toString() }.collect(onQuery) }
    LaunchedEffect(query) { if (query.isEmpty() && field.text.isNotEmpty()) field.setTextAndPlaceCursorAtEnd("") }
    Surface(color = colors.surfaceContainerHigh, shape = RoundedCornerShape(50), modifier = modifier.height(44.dp)) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(ChatGlyphs.Search, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (field.text.isEmpty()) {
                    Text(stringResource(R.string.drawer_search), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1)
                }
                BasicTextField(
                    state = field,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (field.text.isNotEmpty()) {
                IconButton(onClick = { field.setTextAndPlaceCursorAtEnd("") }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Close, stringResource(R.string.cd_dismiss), Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(44.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, description, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun NewChatButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = colors.primary, contentColor = colors.onPrimary, modifier = modifier.fillMaxWidth().height(48.dp)) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.drawer_new_chat), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun DockRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .padding(horizontal = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
    }
}
