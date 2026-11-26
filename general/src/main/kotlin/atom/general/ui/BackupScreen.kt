package atom.general.ui

import android.content.Context
import android.net.Uri
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import atom.general.GeneralHooks
import atom.general.R
import atom.general.backup.BackupEngine
import atom.general.backup.BackupFile
import atom.general.backup.BackupProblem
import atom.general.backup.ImportPlan
import atom.general.backup.ImportResult
import atom.general.backup.ParseResult
import atom.general.backup.ValidationResult
import atom.ui.components.Group
import atom.ui.components.GroupDivider
import atom.ui.components.GroupRow
import atom.ui.components.LargeTitleScreen
import atom.ui.components.RowChevron
import atom.ui.components.ScreenIcons
import atom.ui.components.SectionLabel
import atom.ui.feature.Backups
import atom.ui.feature.FeatureNav
import atom.ui.theme.AtomIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Dialog the backup screen is showing. */
private sealed interface BackupDialog {
    data object SecretsWarning : BackupDialog
    data class Message(val title: String, val body: String) : BackupDialog
    data class Confirm(val plan: ImportPlan) : BackupDialog
    data class Busy(val file: BackupFile) : BackupDialog
}

@Composable
internal fun BackupScreen(nav: FeatureNav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { BackupEngine(appVersion = appVersion(context), saving = { GeneralHooks.conversationsSaving() }) }
    val contributors = remember { Backups.all }
    var working by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<BackupDialog?>(null) }
    val legacy = GeneralHooks.legacyImport
    val legacyAvailable by produceState(false, legacy) { value = withContext(Dispatchers.IO) { legacy?.available() == true } }

    fun labels(keys: List<String>): String {
        val byKey = contributors.associateBy { it.key }
        return keys.joinToString(", ") { k -> byKey[k]?.let { context.getString(it.label) } ?: k }
    }

    fun runImport(file: BackupFile) {
        scope.launch {
            working = true
            val result = engine.import(file, GeneralHooks.tasksRunning)
            working = false
            dialog = when (result) {
                ImportResult.Busy -> BackupDialog.Busy(file)
                ImportResult.Saving -> BackupDialog.Message(
                    context.getString(R.string.backup_saving_title),
                    context.getString(R.string.backup_saving_body),
                )
                is ImportResult.Invalid -> BackupDialog.Message(
                    context.getString(R.string.backup_invalid_title),
                    result.problems.joinToString("\n") { problemText(context, it, ::labels) },
                )
                is ImportResult.Failed -> BackupDialog.Message(
                    context.getString(R.string.backup_failed_title),
                    if (result.rollbackFailures.isEmpty()) {
                        context.getString(R.string.backup_failed_rolled_back, labels(listOf(result.failedKey)), result.reason)
                    } else {
                        context.getString(
                            R.string.backup_failed_partial,
                            labels(listOf(result.failedKey)),
                            result.reason,
                            labels(result.rollbackFailures),
                        )
                    },
                )
                is ImportResult.Success -> {
                    GeneralHooks.onImported?.invoke()
                    BackupDialog.Message(
                        context.getString(R.string.backup_done_title),
                        context.getString(R.string.backup_done_body, labels(result.imported)),
                    )
                }
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupEngine.MIME)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            working = true
            val error = try {
                val text = engine.export()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: throw IOException("no output stream")
                }
                null
            } catch (e: Exception) {
                e.message ?: e::class.simpleName ?: "error"
            }
            working = false
            dialog = BackupDialog.Message(
                context.getString(R.string.backup_export),
                if (error == null) context.getString(R.string.backup_exported) else context.getString(R.string.backup_export_failed, error),
            )
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            working = true
            val next: BackupDialog = when (val read = readText(context, uri)) {
                ReadResult.Failed ->
                    BackupDialog.Message(context.getString(R.string.backup_invalid_title), context.getString(R.string.backup_read_failed))
                ReadResult.TooLarge -> BackupDialog.Message(
                    context.getString(R.string.backup_invalid_title),
                    problemText(context, BackupProblem.TooLarge, ::labels),
                )
                is ReadResult.Text -> when (val parsed = engine.parse(read.value)) {
                    is ParseResult.Invalid -> BackupDialog.Message(
                        context.getString(R.string.backup_invalid_title),
                        problemText(context, parsed.problem, ::labels),
                    )
                    is ParseResult.Ok -> when (val v = engine.validate(parsed.file)) {
                        is ValidationResult.Invalid -> BackupDialog.Message(
                            context.getString(R.string.backup_invalid_title),
                            v.problems.joinToString("\n") { problemText(context, it, ::labels) },
                        )
                        is ValidationResult.Ok -> BackupDialog.Confirm(v.plan)
                    }
                }
            }
            working = false
            dialog = next
        }
    }

    LargeTitleScreen(title = stringResource(R.string.backup_title), onBack = nav::back) {
        item(key = "warning") {
            Group(Modifier.padding(top = 12.dp)) {
                GroupRow(
                    title = stringResource(R.string.backup_keep_safe),
                    subtitle = if (contributors.isEmpty()) stringResource(R.string.backup_nothing_registered) else stringResource(R.string.backup_intro),
                    titleColor = MaterialTheme.colorScheme.error,
                )
            }
        }
        item(key = "label") { SectionLabel(stringResource(R.string.backup_group)) }
        item(key = "actions") {
            Group {
                GroupRow(
                    title = stringResource(R.string.backup_export),
                    subtitle = stringResource(R.string.backup_export_sub, labels(contributors.map { it.key })),
                    icon = AtomIcons.Download,
                    enabled = contributors.isNotEmpty() && !working,
                    onClick = {
                        if (engine.exportContainsSecrets()) {
                            dialog = BackupDialog.SecretsWarning
                        } else {
                            exportLauncher.launch(BackupEngine.fileName(System.currentTimeMillis()))
                        }
                    },
                    trailing = { RowChevron() },
                )
                GroupRow(
                    title = stringResource(R.string.backup_import),
                    subtitle = stringResource(R.string.backup_import_sub),
                    icon = ScreenIcons.Document,
                    enabled = contributors.isNotEmpty() && !working,
                    onClick = { importLauncher.launch(arrayOf(BackupEngine.MIME, "text/plain", "application/octet-stream")) },
                    trailing = { RowChevron() },
                )
                if (legacy != null && legacyAvailable) {
                    GroupRow(
                        title = stringResource(R.string.backup_legacy_import),
                        subtitle = stringResource(R.string.backup_legacy_import_sub),
                        icon = AtomIcons.Download,
                        enabled = !working,
                        onClick = {
                            scope.launch {
                                working = true
                                val text = try {
                                    legacy.run()
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    e.message ?: e::class.simpleName ?: "error"
                                }
                                working = false
                                dialog = BackupDialog.Message(context.getString(R.string.backup_legacy_import), text)
                            }
                        },
                        trailing = { RowChevron() },
                    )
                }
            }
        }
        if (working) {
            item(key = "working") {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }
        }
        item(key = "never") { FootNote(stringResource(R.string.backup_never_included), Modifier.padding(top = 12.dp)) }
    }

    when (val d = dialog) {
        null -> Unit
        BackupDialog.SecretsWarning -> AtomDialog(
            title = stringResource(R.string.backup_secrets_title),
            body = stringResource(R.string.backup_secrets_body),
            confirm = stringResource(R.string.backup_continue),
            onConfirm = {
                dialog = null
                exportLauncher.launch(BackupEngine.fileName(System.currentTimeMillis()))
            },
            onDismiss = { dialog = null },
        )
        is BackupDialog.Message -> AtomDialog(
            title = d.title,
            body = d.body,
            confirm = stringResource(R.string.backup_ok),
            onConfirm = { dialog = null },
            onDismiss = null,
        )
        is BackupDialog.Confirm -> {
            val h = d.plan.file.header
            val created = if (h.createdAtMillis > 0) {
                DateFormat.getMediumDateFormat(context).format(h.createdAtMillis) + " " +
                    DateFormat.getTimeFormat(context).format(h.createdAtMillis)
            } else {
                "?"
            }
            var body = stringResource(R.string.backup_confirm_body, created, h.appVersion, labels(d.plan.toImport.map { it.key }))
            if (d.plan.untouchedKeys.isNotEmpty()) body += stringResource(R.string.backup_confirm_untouched, labels(d.plan.untouchedKeys))
            if (d.plan.unknownKeys.isNotEmpty()) body += stringResource(R.string.backup_confirm_unknown, d.plan.unknownKeys.joinToString(", "))
            if (h.containsSecrets) body += stringResource(R.string.backup_confirm_secrets)
            AtomDialog(
                title = stringResource(R.string.backup_confirm_title),
                body = body,
                confirm = stringResource(R.string.backup_replace),
                onConfirm = {
                    dialog = null
                    runImport(d.plan.file)
                },
                onDismiss = { dialog = null },
            )
        }
        is BackupDialog.Busy -> {
            val stop = GeneralHooks.stopTasks
            AtomDialog(
                title = stringResource(R.string.backup_busy_title),
                body = stringResource(R.string.backup_busy_body),
                confirm = stringResource(if (stop != null) R.string.backup_stop_tasks else R.string.backup_ok),
                onConfirm = {
                    dialog = null
                    stop?.invoke()
                },
                onDismiss = if (stop != null) ({ dialog = null }) else null,
            )
        }
    }
}

/** The Miuix dialog with one confirm button, plus Cancel when [onDismiss] is set. */
@Composable
private fun AtomDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: (() -> Unit)?) {
    atom.ui.components.AtomDialog(show = true, onDismiss = { (onDismiss ?: onConfirm)() }, title = title, summary = body) {
        if (onDismiss != null) {
            atom.ui.components.DialogButtons(
                confirmText = confirm,
                onConfirm = onConfirm,
                dismissText = stringResource(R.string.backup_cancel),
                onDismiss = onDismiss,
            )
        } else {
            atom.ui.components.AtomTextButton(confirm, onConfirm, Modifier.fillMaxWidth().padding(top = 12.dp), primary = true)
        }
    }
}

private sealed interface ReadResult {
    data class Text(val value: String) : ReadResult
    data object TooLarge : ReadResult
    data object Failed : ReadResult
}

/** Reads a picked file, stopping at [BackupEngine.MAX_BYTES] so a huge file never fills memory. */
private suspend fun readText(context: Context, uri: Uri): ReadResult = withContext(Dispatchers.IO) {
    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > BackupEngine.MAX_BYTES) return@withContext ReadResult.TooLarge
                out.write(buf, 0, n)
            }
            ReadResult.Text(out.toString(Charsets.UTF_8.name()))
        } ?: ReadResult.Failed
    } catch (e: Exception) {
        ReadResult.Failed
    }
}

private fun problemText(context: Context, p: BackupProblem, labels: (List<String>) -> String): String = when (p) {
    BackupProblem.Empty -> context.getString(R.string.backup_problem_empty)
    BackupProblem.TooLarge -> context.getString(R.string.backup_problem_too_large)
    BackupProblem.NotJson -> context.getString(R.string.backup_problem_not_json)
    BackupProblem.Foreign -> context.getString(R.string.backup_problem_foreign)
    is BackupProblem.UnsupportedVersion -> context.getString(R.string.backup_problem_version, p.found)
    is BackupProblem.Corrupt -> context.getString(R.string.backup_problem_corrupt, p.detail)
    is BackupProblem.SectionInvalid -> context.getString(R.string.backup_problem_section, labels(listOf(p.key)), p.reason)
    BackupProblem.NothingToImport -> context.getString(R.string.backup_problem_nothing)
}
