package atom.extend.skills

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import atom.extend.ui.InfoDialog
import atom.ui.components.ArrowRow
import atom.ui.components.AtomMenu
import atom.ui.components.AtomMenuItem
import atom.ui.components.AtomRow
import atom.ui.components.SwitchRow
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.theme.MiuixTheme
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
    var describing by remember { mutableStateOf<SkillInfo?>(null) }
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

    fun toggle(s: SkillInfo, on: Boolean) {
        store.setEnabled(s.name, on)
        if (on) Toast.makeText(context, context.getString(R.string.sk_enabled_toast, s.name), Toast.LENGTH_SHORT).show()
    }

    LargeTitleScreen(title = stringResource(R.string.ext_entry_skills), onBack = nav::back) {
        item {
            Hint(stringResource(R.string.sk_how), Modifier.padding(top = 12.dp, bottom = 4.dp))
            Group(Modifier.padding(top = 8.dp)) {
                ArrowRow(
                    title = stringResource(if (busy) R.string.sk_checking else R.string.sk_import),
                    summary = if (busy) stringResource(R.string.sk_importing) else stringResource(R.string.sk_import_sub),
                    onClick = { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                    enabled = !busy,
                )
            }
            message?.let { Notice(it.text, it.error) }
        }
        val builtIn = skills.filter { it.origin == SkillOrigin.BuiltIn }
        val user = skills.filter { it.origin == SkillOrigin.User }
        item { SectionLabel(stringResource(R.string.sk_builtin)) }
        item {
            Group {
                builtIn.forEach { s ->
                    // Tapping the row shows the full description; the switch turns the skill on or off.
                    AtomRow(
                        title = s.name,
                        summary = s.manifest.description.oneLine(),
                        onClick = { describing = s },
                        end = { Box(Modifier.align(Alignment.CenterVertically)) { AtomSwitch(s.enabled, { toggle(s, it) }) } },
                    )
                }
            }
        }
        item { SectionLabel(stringResource(R.string.sk_installed)) }
        item {
            Group {
                if (user.isEmpty()) AtomRow(title = stringResource(R.string.sk_none))
                user.forEach { s ->
                    UserSkillRow(
                        s,
                        onToggle = { toggle(s, it) },
                        onDescribe = { describing = s },
                        onDelete = { deleting = s.name },
                    )
                }
            }
        }
    }

    describing?.let { s ->
        InfoDialog(
            title = s.name,
            text = if (s.origin == SkillOrigin.User) {
                s.manifest.description + "\n\n" + stringResource(R.string.sk_files, s.fileCount, formatBytes(s.sizeBytes))
            } else {
                s.manifest.description
            },
            onDismiss = { describing = null },
        )
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
                scope.launch {
                    if (withContext(Dispatchers.IO) { store.delete(name) }) {
                        Toast.makeText(context, context.getString(R.string.sk_removed_toast, name), Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }
}

private fun String.oneLine(): String = lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ").let {
    if (it.length <= 140) it else it.take(139).trimEnd() + "…"
}

/** An installed skill: switch, a one-line description and a "More actions" menu (View description, Delete). */
@Composable
private fun UserSkillRow(skill: SkillInfo, onToggle: (Boolean) -> Unit, onDescribe: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val more = stringResource(R.string.sk_more_actions, skill.name)
    AtomRow(
        title = skill.name,
        summary = skill.manifest.description.oneLine(),
        onClick = { onToggle(!skill.enabled) },
        end = {
            Box(Modifier.align(Alignment.CenterVertically)) {
                IconButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = more }) {
                    Icon(MiuixIcons.More, null, tint = MiuixTheme.colorScheme.onSurfaceVariantActions)
                }
                AtomMenu(menu, { menu = false }) {
                    AtomMenuItem(stringResource(R.string.sk_view_description), false) {
                        menu = false
                        onDescribe()
                    }
                    AtomMenuItem(stringResource(R.string.ext_delete), false) {
                        menu = false
                        onDelete()
                    }
                }
            }
            Box(Modifier.align(Alignment.CenterVertically)) { AtomSwitch(skill.enabled, onToggle) }
        },
    )
}
