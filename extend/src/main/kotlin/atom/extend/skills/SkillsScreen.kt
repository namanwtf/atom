package atom.extend.skills

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import atom.extend.R
import atom.extend.ui.ConfirmDialog
import atom.extend.ui.Hint
import atom.extend.ui.Message
import atom.extend.ui.Notice
import atom.extend.ui.WorkingLine
import atom.extend.ui.formatBytes
import atom.ui.components.AtomSwitch
import atom.ui.components.CompactButton
import atom.ui.components.Group
import atom.ui.components.GroupDivider
import atom.ui.components.LargeTitleScreen
import atom.ui.components.SectionLabel
import atom.ui.feature.FeatureNav
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SkillsScreen(store: SkillStore, installer: SkillInstaller, nav: FeatureNav) {
    val skills by store.skills.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Message?>(null) }
    var replace by remember { mutableStateOf<Pair<List<String>, CompletableDeferred<Boolean>>?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    val importedFmt = stringResource(R.string.sk_imported)
    val skippedFmt = stringResource(R.string.sk_skipped)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        message = null
        scope.launch {
            message = try {
                val report = withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri) ?: throw SkillException("Could not open the file.")
                    input.use { stream ->
                        installer.importZip(stream) { names ->
                            val answer = CompletableDeferred<Boolean>()
                            replace = names to answer
                            answer.await()
                        }
                    }
                }
                if (report.installed.isNotEmpty()) {
                    Message(importedFmt.format(report.installed.joinToString()), false)
                } else {
                    Message(skippedFmt.format(report.skipped.joinToString("; ")), true)
                }
            } catch (e: Exception) {
                Message(e.message ?: e::class.simpleName.orEmpty(), true)
            } finally {
                busy = false
            }
        }
    }

    LargeTitleScreen(title = stringResource(R.string.ext_entry_skills), onBack = nav::back) {
        item {
            Hint(stringResource(R.string.sk_how), Modifier.padding(bottom = 4.dp))
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                CompactButton(
                    stringResource(R.string.sk_import),
                    onClick = { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                    enabled = !busy,
                )
            }
            if (busy) WorkingLine(stringResource(R.string.sk_importing))
            message?.let { Notice(it.text, it.error) }
        }
        val builtIn = skills.filter { it.origin == SkillOrigin.BuiltIn }
        val user = skills.filter { it.origin == SkillOrigin.User }
        item { SectionLabel(stringResource(R.string.sk_builtin)) }
        item {
            Group {
                builtIn.forEachIndexed { i, s ->
                    if (i > 0) GroupDivider(18.dp)
                    SkillRow(s, onToggle = { store.setEnabled(s.name, it) }, onDelete = null)
                }
            }
        }
        item { SectionLabel(stringResource(R.string.sk_installed)) }
        item {
            if (user.isEmpty()) {
                Hint(stringResource(R.string.sk_none))
            } else {
                Group {
                    user.forEachIndexed { i, s ->
                        if (i > 0) GroupDivider(18.dp)
                        SkillRow(s, onToggle = { store.setEnabled(s.name, it) }, onDelete = { deleting = s.name })
                    }
                }
            }
        }
    }

    replace?.let { (names, answer) ->
        ConfirmDialog(
            title = stringResource(R.string.sk_replace_title, names.joinToString()),
            text = stringResource(R.string.sk_replace_body),
            confirm = stringResource(R.string.sk_replace),
            onConfirm = {
                replace = null
                answer.complete(true)
            },
            onDismiss = {
                replace = null
                answer.complete(false)
            },
        )
    }

    deleting?.let { name ->
        ConfirmDialog(
            title = stringResource(R.string.sk_delete_title, name),
            text = stringResource(R.string.sk_delete_body),
            confirm = stringResource(R.string.ext_delete),
            destructive = true,
            onDismiss = { deleting = null },
            onConfirm = {
                deleting = null
                scope.launch { withContext(Dispatchers.IO) { store.delete(name) } }
            },
        )
    }
}

@Composable
private fun SkillRow(skill: SkillInfo, onToggle: (Boolean) -> Unit, onDelete: (() -> Unit)?) {
    var expanded by rememberSaveable(skill.name) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(skill.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            AtomSwitch(skill.enabled, onToggle)
        }
        Text(
            skill.manifest.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp).clickable { expanded = !expanded },
        )
        if (expanded || onDelete != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (skill.origin == SkillOrigin.User) {
                    Text(
                        stringResource(R.string.sk_files, skill.fileCount, formatBytes(skill.sizeBytes)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                CompactButton(
                    stringResource(if (expanded) R.string.sk_less else R.string.sk_more),
                    onClick = { expanded = !expanded },
                    primary = false,
                )
                if (onDelete != null) {
                    CompactButton(stringResource(R.string.ext_delete), onClick = onDelete, primary = false, contentColor = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
