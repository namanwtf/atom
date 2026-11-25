package atom.extend.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import atom.extend.R
import atom.extend.SecretRead
import atom.extend.ui.ConfirmDialog
import atom.extend.ui.ExtendField
import atom.extend.ui.FieldLabel
import atom.extend.ui.Hint
import atom.extend.ui.InputDialog
import atom.extend.ui.Message
import atom.extend.ui.Notice
import atom.extend.ui.WorkingLine
import atom.ui.components.AtomCard
import atom.ui.components.AtomSwitch
import atom.ui.components.CompactButton
import atom.ui.components.Group
import atom.ui.components.GroupDivider
import atom.ui.components.GroupRow
import atom.ui.components.LargeTitleScreen
import atom.ui.components.RowChevron
import atom.ui.components.SectionLabel
import atom.ui.feature.FeatureNav
import kotlinx.coroutines.launch

@Composable
internal fun McpServersScreen(manager: McpManager, nav: FeatureNav, serverRoute: (String) -> String) {
    val servers by manager.servers.collectAsState()
    val scope = rememberCoroutineScope()
    var adding by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Message?>(null) }

    LargeTitleScreen(title = stringResource(R.string.ext_entry_mcp), onBack = nav::back) {
        item { Hint(stringResource(R.string.mcp_intro)) }
        item {
            if (!adding) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    CompactButton(stringResource(R.string.mcp_add), onClick = { adding = true; message = null })
                }
            } else {
                AtomCard {
                    FieldLabel(stringResource(R.string.mcp_name))
                    ExtendField(name, { name = it }, stringResource(R.string.mcp_name_hint))
                    FieldLabel(stringResource(R.string.mcp_url))
                    ExtendField(url, { url = it.trim() }, stringResource(R.string.mcp_url_hint), mono = true, keyboardType = KeyboardType.Uri)
                    FieldLabel(stringResource(R.string.mcp_token))
                    ExtendField(token, { token = it }, stringResource(R.string.mcp_token_hint), secret = true)
                    Spacer(Modifier.height(6.dp))
                    if (busy) WorkingLine(stringResource(R.string.mcp_discovering))
                    message?.let { Notice(it.text, it.error) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompactButton(stringResource(R.string.ext_cancel), onClick = { adding = false; message = null }, primary = false, enabled = !busy)
                        CompactButton(
                            stringResource(R.string.mcp_connect),
                            enabled = !busy && name.isNotBlank() && url.isNotBlank(),
                            onClick = {
                                busy = true
                                message = null
                                scope.launch {
                                    try {
                                        val server = manager.add(name, url, token)
                                        adding = false
                                        name = ""
                                        url = ""
                                        token = ""
                                        nav.open(serverRoute(server.id))
                                    } catch (e: Exception) {
                                        message = Message(e.message ?: e::class.simpleName.orEmpty(), true)
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
        item { SectionLabel(stringResource(R.string.mcp_servers)) }
        item {
            if (servers.isEmpty()) {
                Hint(stringResource(R.string.mcp_none))
            } else {
                Group {
                    servers.forEachIndexed { i, s ->
                        if (i > 0) GroupDivider(18.dp)
                        GroupRow(
                            title = s.name,
                            subtitle = stringResource(if (s.enabled) R.string.mcp_count else R.string.mcp_count_off, s.enabledCount, s.tools.size),
                            onClick = { nav.open(serverRoute(s.id)) },
                            trailing = { RowChevron() },
                        )
                    }
                }
            }
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
    val refreshed = stringResource(R.string.mcp_refreshed)

    LaunchedEffect(id, server?.hasToken) { tokenState = runCatching { manager.tokenState(id) }.getOrNull() }

    LargeTitleScreen(title = server?.name ?: stringResource(R.string.ext_entry_mcp), onBack = nav::back) {
        if (server == null) {
            item { Hint(stringResource(R.string.mcp_missing)) }
            return@LargeTitleScreen
        }
        item {
            Group {
                GroupRow(
                    title = stringResource(R.string.mcp_enabled),
                    subtitle = stringResource(R.string.mcp_enabled_sub),
                    trailing = { AtomSwitch(server.enabled, { manager.setEnabled(id, it) }) },
                )
                GroupDivider(18.dp)
                GroupRow(title = stringResource(R.string.mcp_url), subtitle = server.url)
                GroupDivider(18.dp)
                val tokenText = when {
                    tokenState is SecretRead.Unreadable -> stringResource(R.string.mcp_token_bad)
                    server.hasToken -> stringResource(R.string.mcp_token_saved)
                    else -> stringResource(R.string.mcp_token_none)
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
                    Text(stringResource(R.string.mcp_token_row), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        tokenText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (tokenState is SecretRead.Unreadable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompactButton(
                            stringResource(if (server.hasToken) R.string.ext_change else R.string.mcp_token_set),
                            onClick = { editingToken = true },
                            primary = false,
                        )
                        if (server.hasToken) {
                            CompactButton(
                                stringResource(R.string.ext_remove),
                                onClick = { scope.launch { manager.setToken(id, null) } },
                                primary = false,
                                contentColor = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                CompactButton(
                    stringResource(R.string.mcp_refresh),
                    enabled = !busy,
                    primary = false,
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
            if (busy) WorkingLine(stringResource(R.string.mcp_refreshing))
            message?.let { Notice(it.text, it.error) }
            if (message == null) server.lastError?.let { Notice(stringResource(R.string.mcp_last_error, it), true) }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(stringResource(R.string.mcp_tools) + " · " + stringResource(R.string.mcp_count, server.enabledCount, server.tools.size), Modifier.weight(1f))
            }
            if (server.tools.isNotEmpty()) {
                Row(Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactButton(stringResource(R.string.mcp_enable_all), onClick = { manager.setAllTools(id, true) }, primary = false)
                    CompactButton(stringResource(R.string.mcp_disable_all), onClick = { manager.setAllTools(id, false) }, primary = false)
                }
            }
            if (server.truncated) Hint(stringResource(R.string.mcp_truncated, McpClient.MAX_TOOLS), color = MaterialTheme.colorScheme.error)
        }
        item {
            if (server.tools.isEmpty()) {
                Hint(stringResource(R.string.mcp_no_tools))
            } else {
                Group {
                    server.tools.forEachIndexed { i, t ->
                        if (i > 0) GroupDivider(18.dp)
                        ToolRow(t, t.name in server.enabledTools) { manager.setToolEnabled(id, t.name, it) }
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(20.dp))
            Group {
                GroupRow(stringResource(R.string.mcp_delete), titleColor = MaterialTheme.colorScheme.error, onClick = { confirmDelete = true })
            }
        }
    }

    if (editingToken) {
        InputDialog(
            title = stringResource(R.string.mcp_token_new),
            placeholder = stringResource(R.string.mcp_token_hint),
            confirm = stringResource(R.string.ext_save),
            secret = true,
            onDismiss = { editingToken = false },
            onConfirm = { value ->
                editingToken = false
                scope.launch {
                    manager.setToken(id, value)
                    tokenState = manager.tokenState(id)
                }
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

@Composable
private fun ToolRow(tool: McpToolEntry, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(tool.title?.takeIf { it.isNotBlank() } ?: tool.name, style = MaterialTheme.typography.bodyLarge)
            if (tool.description.isNotBlank()) {
                Text(
                    tool.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                stringResource(if (tool.readOnly) R.string.mcp_readonly else R.string.mcp_asks),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        AtomSwitch(enabled, onToggle)
    }
}
