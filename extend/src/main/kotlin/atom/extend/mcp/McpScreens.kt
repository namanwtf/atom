package atom.extend.mcp

import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import atom.extend.R
import atom.extend.SecretRead
import atom.extend.ui.ConfirmDialog
import atom.extend.ui.Hint
import atom.extend.ui.Message
import atom.extend.ui.Notice
import atom.ui.components.ArrowRow
import atom.ui.components.AtomDialog
import atom.ui.components.AtomRow
import atom.ui.components.AtomSwitch
import atom.ui.components.AtomTextButton
import atom.ui.components.AtomTextField
import atom.ui.components.DialogButtons
import atom.ui.components.Group
import atom.ui.components.GroupRow
import atom.ui.components.LargeTitleScreen
import atom.ui.components.SectionLabel
import atom.ui.components.SwitchRow
import atom.ui.feature.FeatureNav
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun McpServersScreen(manager: McpManager, nav: FeatureNav, serverRoute: (String) -> String) {
    val servers by manager.servers.collectAsState()
    var adding by rememberSaveable { mutableStateOf(false) }

    LargeTitleScreen(title = stringResource(R.string.ext_entry_mcp), onBack = nav::back) {
        item { Hint(stringResource(R.string.mcp_intro), Modifier.padding(top = 12.dp)) }
        item {
            Group(Modifier.padding(top = 8.dp)) {
                ArrowRow(stringResource(R.string.mcp_add), { adding = true }, summary = stringResource(R.string.mcp_add_sub))
            }
        }
        item { SectionLabel(stringResource(R.string.mcp_servers_count, servers.size)) }
        item {
            Group {
                if (servers.isEmpty()) {
                    ArrowRow(stringResource(R.string.mcp_none), { adding = true }, summary = stringResource(R.string.mcp_none_sub))
                }
                servers.forEach { s ->
                    val count = stringResource(R.string.mcp_count, s.enabledCount, s.tools.size) +
                        if (s.truncated) stringResource(R.string.mcp_count_truncated) else ""
                    ArrowRow(
                        title = s.name,
                        onClick = { nav.open(serverRoute(s.id)) },
                        summary = if (s.enabled) count else stringResource(R.string.mcp_count_off, count),
                    )
                }
            }
        }
    }

    if (adding) {
        AddServerDialog(manager, onDismiss = { adding = false }, onAdded = { id ->
            adding = false
            nav.open(serverRoute(id))
        })
    }
}

/** "Add server": name, server URL and an optional bearer token; the catalog is discovered before saving. */
@Composable
private fun AddServerDialog(manager: McpManager, onDismiss: () -> Unit, onAdded: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Message?>(null) }
    AtomDialog(show = true, onDismiss = { if (!busy) onDismiss() }, title = stringResource(R.string.mcp_add)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AtomTextField(name, { name = it }, label = stringResource(R.string.mcp_name), singleLine = true, enabled = !busy)
            AtomTextField(
                url,
                { url = it.trim() },
                label = stringResource(R.string.mcp_url),
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            AtomTextField(
                token,
                { token = it },
                label = stringResource(R.string.mcp_token),
                singleLine = true,
                enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            if (busy) Text(stringResource(R.string.mcp_discovering), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            message?.let { Text(it.text, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.error) }
            DialogButtons(
                confirmText = stringResource(R.string.mcp_connect),
                confirmEnabled = !busy && name.isNotBlank() && url.isNotBlank(),
                onConfirm = {
                    busy = true
                    message = null
                    scope.launch {
                        try {
                            onAdded(manager.add(name, url, token).id)
                        } catch (e: Exception) {
                            message = Message(e.message ?: e::class.simpleName.orEmpty(), true)
                        } finally {
                            busy = false
                        }
                    }
                },
                dismissText = stringResource(R.string.ext_cancel),
                onDismiss = { if (!busy) onDismiss() },
            )
        }
    }
}

@Composable
internal fun McpServerScreen(manager: McpManager, id: String, nav: FeatureNav) {
    val servers by manager.servers.collectAsState()
    val server = servers.firstOrNull { it.id == id }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Message?>(null) }
    var tokenState by remember { mutableStateOf<SecretRead?>(null) }
    var editingToken by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmTool by remember { mutableStateOf<McpToolEntry?>(null) }
    var confirmAll by remember { mutableStateOf(0) }
    val refreshed = stringResource(R.string.mcp_refreshed)

    LaunchedEffect(id, server?.hasToken) { tokenState = runCatching { manager.tokenState(id) }.getOrNull() }

    fun toggleTool(t: McpToolEntry, on: Boolean) {
        // A tool that may change things is confirmed when it is switched on.
        if (on && !t.readOnly) confirmTool = t else manager.setToolEnabled(id, t.name, on)
    }

    LargeTitleScreen(title = stringResource(R.string.mcp_server_title), onBack = nav::back) {
        if (server == null) {
            item { Hint(stringResource(R.string.mcp_missing), Modifier.padding(top = 12.dp)) }
            return@LargeTitleScreen
        }
        item { SectionLabel(stringResource(R.string.mcp_server_group)) }
        item {
            Group {
                SwitchRow(
                    title = stringResource(R.string.mcp_enabled),
                    checked = server.enabled,
                    onCheckedChange = { manager.setEnabled(id, it) },
                    summary = server.name + " · " + server.url,
                )
                val tokenText = when {
                    tokenState is SecretRead.Unreadable -> stringResource(R.string.mcp_token_bad)
                    server.hasToken -> stringResource(R.string.mcp_token_saved)
                    else -> stringResource(R.string.mcp_token_none)
                }
                ArrowRow(stringResource(R.string.mcp_token_row), { editingToken = true }, summary = tokenText)
                ArrowRow(
                    title = stringResource(if (busy) R.string.mcp_refreshing else R.string.mcp_refresh),
                    enabled = !busy,
                    summary = server.lastError?.takeIf { message == null }?.let { stringResource(R.string.mcp_last_error, it) },
                    onClick = {
                        busy = true
                        message = null
                        scope.launch {
                            message = try {
                                manager.refresh(id)
                                Message(refreshed, false)
                            } catch (e: Exception) {
                                Message(e.message ?: e::class.simpleName.orEmpty(), true)
                            } finally {
                                busy = false
                            }
                        }
                    },
                )
            }
            message?.let { Notice(it.text, it.error) }
        }
        item { SectionLabel(stringResource(R.string.mcp_tools_count, server.tools.size)) }
        item(key = "tools-header") {
            val cut = server.truncation
            if (cut != null) {
                Hint(stringResource(truncationText(cut)), Modifier.padding(bottom = 8.dp), color = MiuixTheme.colorScheme.error)
            } else if (server.truncated) {
                Hint(stringResource(R.string.mcp_truncated_old), Modifier.padding(bottom = 8.dp), color = MiuixTheme.colorScheme.error)
            }
            // The tools are one lazy item each (a catalog can hold 2,048), drawn as one card: the header takes the
            // top corners and the last tool the bottom ones.
            CardPiece(top = true, bottom = server.tools.isEmpty()) {
                if (server.tools.isEmpty()) {
                    AtomRow(title = stringResource(R.string.mcp_no_tools))
                } else {
                    AtomRow(
                        title = stringResource(R.string.mcp_tools_header, server.name, server.enabledCount),
                        end = {
                            Row(Modifier.align(Alignment.CenterVertically), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                val allOn = server.enabledCount == server.tools.size
                                AtomTextButton(
                                    stringResource(if (allOn) R.string.mcp_disable_all else R.string.mcp_enable_all),
                                    {
                                        val risky = McpToolRules.riskyToEnable(server.tools, server.enabledTools)
                                        when {
                                            allOn -> manager.setAllTools(id, false)
                                            risky > 0 -> confirmAll = risky
                                            else -> manager.setAllTools(id, true)
                                        }
                                    },
                                )
                            }
                        },
                    )
                }
            }
        }
        itemsIndexed(server.tools, key = { _, t -> t.name }) { i, t ->
            CardPiece(top = false, bottom = i == server.tools.lastIndex) {
                ToolRow(t, t.name in server.enabledTools) { toggleTool(t, it) }
            }
        }
        item {
            Group(Modifier.padding(top = 16.dp)) {
                GroupRow(
                    stringResource(R.string.mcp_delete),
                    subtitle = stringResource(R.string.mcp_delete_sub),
                    titleColor = MiuixTheme.colorScheme.error,
                    onClick = { confirmDelete = true },
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (editingToken) {
        TokenDialog(
            hasToken = server?.hasToken == true,
            onDismiss = { editingToken = false },
            onSave = { value ->
                editingToken = false
                scope.launch {
                    // A blank token removes it.
                    manager.setToken(id, value.ifBlank { null })
                    tokenState = manager.tokenState(id)
                }
            },
        )
    }

    confirmTool?.let { t ->
        ConfirmDialog(
            title = stringResource(R.string.mcp_enable_tool_title),
            text = stringResource(R.string.mcp_enable_tool_body, t.title?.takeIf { it.isNotBlank() } ?: t.name),
            confirm = stringResource(R.string.mcp_enable_tool_ok),
            onDismiss = { confirmTool = null },
            onConfirm = {
                manager.setToolEnabled(id, t.name, true)
                confirmTool = null
            },
        )
    }

    if (confirmAll > 0) {
        ConfirmDialog(
            title = stringResource(R.string.mcp_enable_all_title),
            text = pluralStringResource(R.plurals.mcp_enable_all_body, confirmAll, confirmAll),
            confirm = stringResource(R.string.mcp_enable_all),
            onDismiss = { confirmAll = 0 },
            onConfirm = {
                manager.setAllTools(id, true)
                confirmAll = 0
            },
        )
    }

    if (confirmDelete && server != null) {
        ConfirmDialog(
            title = stringResource(R.string.mcp_delete_title, server.name),
            text = stringResource(R.string.mcp_delete_body),
            confirm = stringResource(R.string.ext_delete),
            destructive = true,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    manager.delete(id)
                    nav.back()
                }
            },
        )
    }
}

private fun truncationText(t: McpTruncation) = when (t) {
    McpTruncation.ToolLimit -> R.string.mcp_truncated_tools
    McpTruncation.PageLimit -> R.string.mcp_truncated_pages
    McpTruncation.SizeBudget -> R.string.mcp_truncated_size
    McpTruncation.DuplicateCursor -> R.string.mcp_truncated_cursor
}

/** Change the bearer token; saving it blank removes it. */
@Composable
private fun TokenDialog(hasToken: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    AtomDialog(
        show = true,
        onDismiss = onDismiss,
        title = stringResource(R.string.mcp_token_row),
        summary = stringResource(if (hasToken) R.string.mcp_token_dialog_change else R.string.mcp_token_dialog_new),
    ) {
        Column {
            AtomTextField(
                value,
                { value = it },
                label = stringResource(R.string.mcp_token_hint),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            DialogButtons(
                confirmText = stringResource(R.string.ext_save),
                onConfirm = { onSave(value) },
                dismissText = stringResource(R.string.ext_cancel),
                onDismiss = onDismiss,
                confirmEnabled = hasToken || value.isNotBlank(),
            )
        }
    }
}

@Composable
private fun ToolRow(tool: McpToolEntry, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    var expanded by rememberSaveable(tool.name) { mutableStateOf(false) }
    val c = MiuixTheme.colorScheme
    AtomRow(
        title = tool.title?.takeIf { it.isNotBlank() } ?: tool.name,
        summary = stringResource(if (tool.readOnly) R.string.mcp_readonly else R.string.mcp_asks),
        onClick = { if (tool.description.isNotBlank()) expanded = !expanded else onToggle(!enabled) },
        end = { Box(Modifier.align(Alignment.CenterVertically)) { AtomSwitch(enabled, onToggle) } },
    )
    if (tool.description.isNotBlank()) {
        Text(
            tool.description,
            style = MiuixTheme.textStyles.footnote1,
            color = c.onSurfaceVariantSummary,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )
    }
}

/** One piece of a card split across lazy items: [top] / [bottom] round those corners. */
@Composable
private fun CardPiece(top: Boolean, bottom: Boolean, content: @Composable () -> Unit) {
    val r = 16.dp
    val shape = RoundedCornerShape(
        topStart = if (top) r else 0.dp,
        topEnd = if (top) r else 0.dp,
        bottomStart = if (bottom) r else 0.dp,
        bottomEnd = if (bottom) r else 0.dp,
    )
    Column(Modifier.fillMaxWidth().clip(shape).background(MiuixTheme.colorScheme.surfaceContainer)) { content() }
}
