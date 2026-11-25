package atom.linux.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import atom.linux.GuestFs
import atom.linux.HealthReport
import atom.linux.LinuxEnv
import atom.linux.LinuxState
import atom.linux.R
import atom.linux.SharedFolderRules
import atom.linux.jobs.JobKind
import atom.linux.jobs.JobManager
import atom.linux.jobs.JobRecord
import atom.linux.jobs.JobRules
import atom.linux.jobs.JobState
import atom.ui.components.AtomCard
import atom.ui.components.AtomSwitch
import atom.ui.components.CompactButton
import atom.ui.components.Group
import atom.ui.components.GroupDivider
import atom.ui.components.GroupRow
import atom.ui.components.LargeTitleScreen
import atom.ui.components.RowChevron
import atom.ui.components.SectionLabel
import atom.ui.components.StatusLine
import atom.ui.theme.extras
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import atom.linux.SharedFolder
import atom.ui.components.ArrowRow
import atom.ui.components.AtomDialog
import atom.ui.components.AtomRow
import atom.ui.components.AtomTextButton
import atom.ui.components.AtomTextField
import atom.ui.components.DialogButtons
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

// ================================================================ health

@Composable
internal fun HealthScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val env = remember { LinuxEnv.get(context) }
    val report by env.health.collectAsStateWithLifecycle()
    val state by env.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    fun check() {
        running = true
        scope.launch {
            try { env.healthCheck() } finally { running = false }
        }
    }
    LaunchedEffect(Unit) { if (report == null) check() }
    // Re-check once a repair finishes.
    var wasInstalling by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state is LinuxState.Installing) wasInstalling = true else if (wasInstalling) {
            wasInstalling = false
            check()
        }
    }

    LargeTitleScreen(title = stringResource(R.string.health_title), onBack = onBack) {
        item {
            val r = report
            if (r == null) {
                Text(stringResource(if (running) R.string.health_running else R.string.health_never), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Group {
                    r.items.forEachIndexed { i, item ->
                        if (i > 0) GroupDivider(inset = 18.dp)
                        GroupRow(
                            title = item.title,
                            subtitle = item.detail,
                            trailing = { StatusDot(item.level) },
                        )
                    }
                }
                Text(
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(r.checkedAtMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 18.dp, top = 6.dp),
                )
            }
        }
        item {
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CompactButton(stringResource(if (running) R.string.health_running else R.string.health_run), ::check, enabled = !running && state !is LinuxState.Installing)
                CompactButton(
                    stringResource(R.string.health_repair),
                    env::repair,
                    primary = report?.repairable == true,
                    enabled = state is LinuxState.Ready,
                )
            }
            Text(
                stringResource(R.string.health_repair_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
            )
            (state as? LinuxState.Installing)?.let {
                Text(stringResource(R.string.linux_progress, it.step, (it.progress * 100).toInt()), modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun StatusDot(level: HealthReport.Level) {
    val color = when (level) {
        HealthReport.Level.Ok -> MaterialTheme.extras.success
        HealthReport.Level.Warning -> MaterialTheme.extras.warning
        HealthReport.Level.Problem -> MaterialTheme.colorScheme.error
    }
    atom.ui.components.StatusDot(color)
}

// ================================================================ shared folders

private val STORAGE_ROOT: String = Environment.getExternalStorageDirectory()?.path ?: "/storage/emulated/0"

@Composable
internal fun FoldersScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val env = remember { LinuxEnv.get(context) }
    val config by env.config.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<SharedFolder?>(null) }

    LargeTitleScreen(title = stringResource(R.string.folders_title), onBack = onBack) {
        item {
            Text(
                stringResource(R.string.folders_body, SharedFolderRules.MAX_FOLDERS),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            )
        }
        item {
            SectionLabel(stringResource(R.string.folders_list, config.sharedFolders.size))
            Group {
                if (config.sharedFolders.isEmpty()) {
                    AtomRow(title = stringResource(R.string.folders_empty), summary = stringResource(R.string.folders_empty_body))
                }
                for (f in config.sharedFolders) {
                    val readable = File(f.hostPath).canRead()
                    AtomRow(
                        title = f.guestPath,
                        summary = f.hostPath + if (!readable) "\n" + stringResource(R.string.folders_unreadable) else "",
                        end = {
                            Box(Modifier.align(Alignment.CenterVertically)) {
                                AtomTextButton(stringResource(R.string.folders_remove), { removing = f })
                            }
                        },
                    )
                }
            }
        }
        item {
            Group(Modifier.padding(top = 12.dp)) {
                ArrowRow(
                    stringResource(R.string.folders_add),
                    { adding = true },
                    enabled = config.sharedFolders.size < SharedFolderRules.MAX_FOLDERS,
                )
            }
        }
    }

    if (adding) {
        AddFolderDialog(
            onDismiss = { adding = false },
            onAdd = { name, path ->
                val problem = env.addSharedFolder(name, path)
                if (problem == null) adding = false
                problem
            },
        )
    }

    AtomDialog(
        show = removing != null,
        onDismiss = { removing = null },
        title = removing?.let { stringResource(R.string.folders_remove_title, it.name) },
        summary = stringResource(R.string.folders_remove_body),
    ) {
        DialogButtons(
            confirmText = stringResource(R.string.folders_remove),
            onConfirm = {
                removing?.let { env.removeSharedFolder(it.name) }
                removing = null
            },
            dismissText = stringResource(R.string.linux_cancel),
            onDismiss = { removing = null },
        )
    }
}

/**
 * Pick a directory under shared storage (or with the system picker), name the mount and add it. [onAdd] returns
 * a problem to show, or null when the folder was added.
 */
@Composable
private fun AddFolderDialog(onDismiss: () -> Unit, onAdd: (String, String) -> String?) {
    var path by rememberSaveable { mutableStateOf(STORAGE_ROOT) }
    var name by rememberSaveable { mutableStateOf("") }
    var nameEdited by rememberSaveable { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val listing = remember(path) {
        File(path).listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.sortedBy { it.name.lowercase() }
    }
    LaunchedEffect(path) { if (!nameEdited) name = if (path == STORAGE_ROOT) "" else SharedFolderRules.suggestName(path) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val p = uri?.let { SharedFolderRules.pathFromTreeUri(it.toString()) } ?: return@rememberLauncherForActivityResult
        path = p
        if (!nameEdited) name = SharedFolderRules.suggestName(p)
    }
    val c = MiuixTheme.colorScheme

    AtomDialog(show = true, onDismiss = onDismiss, title = stringResource(R.string.folders_add_title)) {
        Column {
            Text(path, style = MiuixTheme.textStyles.body2, color = c.onSurfaceVariantSummary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.secondaryContainer)
                    .verticalScroll(rememberScrollState()),
            ) {
                SharedFolderRules.parentWithin(path, STORAGE_ROOT)?.let { up ->
                    PickerRow("..", onClick = { path = up })
                }
                when {
                    listing == null -> PickerNote(stringResource(R.string.folders_cannot_open))
                    listing.isEmpty() -> PickerNote(stringResource(R.string.folders_no_subfolders))
                    else -> listing.forEach { d -> PickerRow(d.name + "/", onClick = { path = d.path }) }
                }
            }
            AtomTextButton(stringResource(R.string.folders_system_picker), { picker.launch(null) }, Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(12.dp))
            AtomTextField(
                value = name,
                onValueChange = {
                    name = it
                    nameEdited = true
                    problem = null
                },
                label = stringResource(R.string.folders_mount_name),
                singleLine = true,
            )
            Text(
                stringResource(R.string.folders_mount_line, path, if (name.isBlank()) "…" else "/workspace/mounts/$name"),
                style = MiuixTheme.textStyles.footnote1,
                color = c.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 8.dp),
            )
            problem?.let { Text(it, style = MiuixTheme.textStyles.footnote1, color = c.error, modifier = Modifier.padding(top = 6.dp)) }
            DialogButtons(
                confirmText = stringResource(R.string.folders_add_confirm),
                onConfirm = { problem = onAdd(name.trim(), path) },
                dismissText = stringResource(R.string.linux_cancel),
                onDismiss = onDismiss,
                confirmEnabled = name.isNotBlank(),
            )
        }
    }
}

@Composable
private fun PickerRow(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MiuixTheme.textStyles.body1,
        color = MiuixTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
    )
}

@Composable
private fun PickerNote(text: String) {
    Text(
        text,
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
    )
}

// ================================================================ file browser

@Composable
internal fun FilesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val env = remember { LinuxEnv.get(context) }
    val fs = remember { env.guestFs() }
    var path by rememberSaveable { mutableStateOf("/") }
    var previewPath by rememberSaveable { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<GuestFs.Entry>?>(null) }
    var preview by remember { mutableStateOf<GuestFs.Preview?>(null) }

    LaunchedEffect(path) { entries = withContext(Dispatchers.IO) { fs?.list(path) } }
    LaunchedEffect(previewPath) { preview = previewPath?.let { p -> withContext(Dispatchers.IO) { fs?.preview(p) } } }

    val back = {
        when {
            previewPath != null -> previewPath = null
            path != "/" -> path = GuestFs.parentOf(path)
            else -> onBack()
        }
    }
    androidx.activity.compose.BackHandler(enabled = previewPath != null || path != "/") { back() }

    LargeTitleScreen(title = previewPath?.substringAfterLast('/') ?: path, onBack = back) {
        val p = previewPath
        if (p != null) {
            item {
                when (val pv = preview) {
                    null -> Text(stringResource(R.string.health_running))
                    is GuestFs.Preview.Binary -> Text(stringResource(R.string.files_binary, formatBytes(pv.totalBytes)))
                    is GuestFs.Preview.Unreadable -> Text(pv.reason, color = MaterialTheme.colorScheme.error)
                    is GuestFs.Preview.Text -> Column {
                        if (pv.truncated) {
                            Text(
                                stringResource(R.string.files_truncated, formatBytes(GuestFs.PREVIEW_BYTES.toLong()), formatBytes(pv.totalBytes)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            pv.text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF0E0E10))
                                .horizontalScroll(rememberScrollState())
                                .padding(12.dp),
                        )
                    }
                }
            }
            return@LargeTitleScreen
        }
        item {
            val list = entries
            when {
                fs == null -> Text(stringResource(R.string.terminal_linux_unavailable))
                list == null -> Text(stringResource(R.string.files_unreadable), color = MaterialTheme.colorScheme.onSurfaceVariant)
                list.isEmpty() -> Text(stringResource(R.string.files_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Group {
                    list.forEachIndexed { i, e ->
                        if (i > 0) GroupDivider(inset = 18.dp)
                        GroupRow(
                            title = e.name + if (e.isDirectory) "/" else "",
                            subtitle = when {
                                e.isSymlink -> "→ ${e.linkTarget}"
                                e.isDirectory -> null
                                else -> formatBytes(e.sizeBytes)
                            },
                            enabled = e.readable,
                            onClick = { if (e.isDirectory) path = e.guestPath else previewPath = e.guestPath },
                            trailing = if (e.isDirectory) ({ RowChevron() }) else null,
                        )
                    }
                }
            }
        }
    }
}

// ================================================================ workspace

private const val MAX_IMPORT = 32L * 1024 * 1024

@Composable
internal fun WorkspaceScreen(onBack: () -> Unit, open: (String) -> Unit = {}) {
    val context = LocalContext.current
    val env = remember { LinuxEnv.get(context) }
    val linuxState by env.state.collectAsStateWithLifecycle()
    val config by env.config.collectAsStateWithLifecycle()
    val dir = remember { env.paths.workspace.apply { mkdirs() } }
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf(listOf<File>()) }
    var message by remember { mutableStateOf<String?>(null) }
    var publicGranted by remember { mutableStateOf(publicAccess()) }
    var exportFile by remember { mutableStateOf<File?>(null) }
    var used by remember { mutableStateOf(0L) }

    fun reload() {
        scope.launch {
            val (list, size) = withContext(Dispatchers.IO) {
                val l = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name.lowercase() }.orEmpty()
                l to dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            }
            files = list
            used = size
        }
    }
    LaunchedEffect(Unit) { reload() }
    // The access screen is another app; re-check when we come back.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        publicGranted = publicAccess()
        onPauseOrDispose {}
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val skipped = ArrayList<String>()
            var count = 0
            withContext(Dispatchers.IO) {
                for (uri in uris) {
                    val (name, size) = nameAndSize(context, uri)
                    if (size > MAX_IMPORT) {
                        skipped += name
                        continue
                    }
                    val target = uniqueFile(dir, name)
                    val ok = runCatching {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            target.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                var total = 0L
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    total += n
                                    if (total > MAX_IMPORT) throw IllegalStateException("too big")
                                    out.write(buf, 0, n)
                                }
                            }
                        } != null
                    }.getOrDefault(false)
                    if (ok) count++ else {
                        target.delete()
                        skipped += name
                    }
                }
            }
            message = buildString {
                append(context.getString(R.string.workspace_imported, count))
                for (s in skipped) append('\n').append(context.getString(R.string.workspace_too_big, s))
            }
            reload()
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val src = exportFile ?: return@rememberLauncherForActivityResult
        exportFile = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } } != null }.getOrDefault(false)
            }
            message = if (ok) context.getString(R.string.workspace_exported) else null
        }
    }

    LargeTitleScreen(title = stringResource(R.string.workspace_title), onBack = onBack) {
        item {
            Group {
                GroupRow(
                    title = stringResource(R.string.workspace_private),
                    subtitle = stringResource(R.string.workspace_private_body, formatBytes(used)),
                    trailing = { atom.ui.components.StatusDot(MaterialTheme.extras.success) },
                )
                GroupDivider(inset = 18.dp)
                GroupRow(
                    title = stringResource(R.string.workspace_public),
                    subtitle = stringResource(if (publicGranted) R.string.workspace_public_granted else R.string.workspace_public_denied),
                    trailing = {
                        if (publicGranted) {
                            atom.ui.components.StatusDot(MaterialTheme.extras.success)
                        } else {
                            CompactButton(stringResource(R.string.workspace_allow), {
                                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                                runCatching { context.startActivity(intent) }.onFailure {
                                    runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                                }
                            })
                        }
                    },
                )
            }
        }
        item {
            Group(Modifier.padding(top = 12.dp)) {
                GroupRow(
                    title = stringResource(R.string.linux_folders),
                    subtitle = stringResource(R.string.workspace_folders_body, config.sharedFolders.size),
                    onClick = { open(LinuxFeature.ROUTE_FOLDERS) },
                    trailing = { RowChevron() },
                )
                GroupDivider(inset = 18.dp)
                GroupRow(
                    title = stringResource(R.string.workspace_browse),
                    subtitle = stringResource(if (linuxState is LinuxState.Ready) R.string.workspace_browse_body else R.string.terminal_linux_unavailable),
                    enabled = linuxState is LinuxState.Ready,
                    onClick = { open(LinuxFeature.ROUTE_FILES) },
                    trailing = { RowChevron() },
                )
            }
        }
        item {
            SectionLabel(stringResource(R.string.workspace_files))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                CompactButton(stringResource(R.string.workspace_import), { importer.launch(arrayOf("*/*")) })
            }
            Text(stringResource(R.string.workspace_import_limit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)) }
            if (files.isEmpty()) {
                Text(stringResource(R.string.workspace_none), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
            } else {
                Group {
                    files.forEachIndexed { i, f ->
                        if (i > 0) GroupDivider(inset = 18.dp)
                        GroupRow(
                            title = f.name,
                            subtitle = formatBytes(f.length()),
                            trailing = {
                                CompactButton(stringResource(R.string.workspace_export), {
                                    exportFile = f
                                    exporter.launch(f.name)
                                }, primary = false)
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun publicAccess(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

private fun nameAndSize(context: android.content.Context, uri: Uri): Pair<String, Long> {
    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    var size = -1L
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
    }
    val safe = name.replace('/', '_').replace('\u0000', '_').trim().ifEmpty { "file" }.take(120)
    return safe to size
}

private fun uniqueFile(dir: File, name: String): File {
    var f = File(dir, name)
    var i = 1
    val base = name.substringBeforeLast('.', name)
    val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
    while (f.exists()) f = File(dir, "$base (${i++})$ext")
    return f
}

// ================================================================ background tasks

@Composable
internal fun JobsScreen(onBack: () -> Unit, openLog: (String) -> Unit) {
    val context = LocalContext.current
    val env = remember { LinuxEnv.get(context) }
    val jobsManager = remember { JobManager.get(context) }
    val jobs by jobsManager.jobs.collectAsStateWithLifecycle()
    val config by env.config.collectAsStateWithLifecycle()

    LargeTitleScreen(title = stringResource(R.string.jobs_title), onBack = onBack) {
        item {
            Text(stringResource(R.string.jobs_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
            Group(Modifier.padding(top = 8.dp)) {
                GroupRow(
                    title = stringResource(R.string.jobs_cap),
                    subtitle = stringResource(R.string.jobs_cap_body),
                    trailing = { AtomSwitch(config.jobCapMinutes > 0, { env.setJobCapMinutes(if (it) JobRules.DEFAULT_CAP_MINUTES else 0) }) },
                )
            }
        }
        for ((title, kind) in listOf(R.string.jobs_daemons to JobKind.Daemon, R.string.jobs_commands to JobKind.Job)) {
            val list = jobs.filter { it.kind == kind }.sortedByDescending { it.startedAtMs }
            item {
                SectionLabel(stringResource(title))
                if (list.isEmpty()) {
                    Text(stringResource(R.string.jobs_none), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
                } else {
                    Group {
                        list.forEachIndexed { i, r ->
                            if (i > 0) GroupDivider(inset = 18.dp)
                            JobRow(r, onStop = { jobsManager.stop(r.id) }, onLogs = { openLog(r.id) })
                        }
                    }
                }
            }
        }
        if (jobs.any { it.state.finished }) {
            item {
                CompactButton(stringResource(R.string.jobs_clear), jobsManager::clearFinished, primary = false, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}

@Composable
private fun JobRow(r: JobRecord, onStop: () -> Unit, onLogs: () -> Unit) {
    val color = when (r.state) {
        JobState.Running -> MaterialTheme.colorScheme.primary
        JobState.Completed -> MaterialTheme.extras.success
        JobState.Stopped, JobState.Interrupted -> MaterialTheme.extras.warning
        else -> MaterialTheme.colorScheme.error
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StatusLine(color, JobRules.describe(r))
        Text("$ ${r.command}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 3, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "${r.env.label} · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(r.startedAtMs)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactButton(stringResource(R.string.jobs_logs), onLogs, primary = false)
            if (r.state == JobState.Running) CompactButton(stringResource(R.string.jobs_stop), onStop, primary = false, contentColor = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
internal fun JobLogScreen(id: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val jobs = remember { JobManager.get(context) }
    val all by jobs.jobs.collectAsStateWithLifecycle()
    val record = all.firstOrNull { it.id == id }
    var text by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    // Reload when the job changes state, or on request; no timers.
    LaunchedEffect(record?.state, tick) { text = withContext(Dispatchers.IO) { jobs.log(id) } }

    LargeTitleScreen(
        title = record?.name ?: stringResource(R.string.jobs_log_title),
        onBack = onBack,
        actions = { CompactButton(stringResource(R.string.jobs_refresh), { tick++ }, primary = false, modifier = Modifier.padding(end = 12.dp)) },
    ) {
        item {
            record?.let { StatusLine(MaterialTheme.colorScheme.primary, JobRules.describe(it), Modifier.padding(4.dp)) }
            Text(
                text,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF0E0E10))
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            )
        }
    }
}

