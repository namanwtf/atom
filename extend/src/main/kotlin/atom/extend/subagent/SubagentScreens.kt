package atom.extend.subagent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import atom.core.llm.ReasoningEffort
import atom.extend.Extend
import atom.extend.R
import atom.extend.ui.ConfirmDialog
import atom.extend.ui.ExtendField
import atom.extend.ui.FieldLabel
import atom.extend.ui.Hint
import atom.extend.ui.Notice
import atom.ui.components.AtomCard
import atom.ui.components.AtomMenu
import atom.ui.components.AtomMenuItem
import atom.ui.components.AtomSwitch
import atom.ui.components.BottomActionBar
import atom.ui.components.CompactButton
import atom.ui.components.Group
import atom.ui.components.GroupDivider
import atom.ui.components.GroupRow
import atom.ui.components.LargeTitleScreen
import atom.ui.components.PrimaryButton
import atom.ui.components.RowChevron
import atom.ui.components.RowValue
import atom.ui.components.SectionLabel
import atom.ui.components.StatusDot
import atom.ui.feature.FeatureNav
import atom.ui.theme.extras

internal object SubagentRoutes {
    const val ROOT = "extend/subagents"
    const val EDIT = "extend/subagents/edit/"
    const val NEW = "extend/subagents/new"
    const val RUN = "extend/subagents/run/"
}

@Composable
internal fun SubagentsScreen(store: SubagentStore, nav: FeatureNav) {
    val settings by store.settings.collectAsState()
    val custom by store.custom.collectAsState()
    val runs by SubagentRuns.runs.collectAsState()
    val available = Extend.engines != null
    var modelMenu by remember { mutableStateOf(false) }
    var effortMenu by remember { mutableStateOf(false) }
    val followMain = stringResource(R.string.sa_follow_main)
    val modelDefault = stringResource(R.string.sa_model_default)

    LargeTitleScreen(title = stringResource(R.string.ext_entry_subagents), onBack = nav::back) {
        item {
            Group {
                GroupRow(
                    title = stringResource(R.string.sa_enable),
                    subtitle = stringResource(if (available) R.string.sa_enable_sub else R.string.sa_unavailable),
                    enabled = available,
                    trailing = { AtomSwitch(settings.enabled && available, { v -> store.updateSettings { it.copy(enabled = v) } }, enabled = available) },
                )
                GroupDivider(18.dp)
                Box {
                    GroupRow(
                        title = stringResource(R.string.sa_model),
                        enabled = available && settings.enabled,
                        onClick = { modelMenu = true },
                        trailing = { RowValue(settings.modelLabel ?: settings.modelId ?: followMain) },
                    )
                    AtomMenu(modelMenu, { modelMenu = false }) {
                        AtomMenuItem(followMain, settings.modelId == null) {
                            modelMenu = false
                            store.updateSettings { it.copy(modelId = null, modelLabel = null) }
                        }
                        Extend.engines?.models().orEmpty().forEach { m ->
                            AtomMenuItem(m.label, settings.modelId == m.id) {
                                modelMenu = false
                                store.updateSettings { it.copy(modelId = m.id, modelLabel = m.label) }
                            }
                        }
                    }
                }
                GroupDivider(18.dp)
                Box {
                    GroupRow(
                        title = stringResource(R.string.sa_reasoning),
                        enabled = available && settings.enabled,
                        onClick = { effortMenu = true },
                        trailing = { RowValue(settings.reasoning?.name ?: modelDefault) },
                    )
                    AtomMenu(effortMenu, { effortMenu = false }) {
                        AtomMenuItem(modelDefault, settings.reasoning == null) {
                            effortMenu = false
                            store.updateSettings { it.copy(reasoning = null) }
                        }
                        ReasoningEffort.entries.forEach { e ->
                            AtomMenuItem(e.name, settings.reasoning == e) {
                                effortMenu = false
                                store.updateSettings { it.copy(reasoning = e) }
                            }
                        }
                    }
                }
            }
        }
        item { SectionLabel(stringResource(R.string.sa_builtin)) }
        item {
            Group {
                BuiltInSubagents.all.forEachIndexed { i, d ->
                    if (i > 0) GroupDivider(18.dp)
                    GroupRow(title = d.name, subtitle = d.description + " " + d.whenToUse)
                }
            }
        }
        item { SectionLabel(stringResource(R.string.sa_custom)) }
        item {
            if (custom.isEmpty()) {
                Hint(stringResource(R.string.sa_none))
            } else {
                Group {
                    custom.forEachIndexed { i, d ->
                        if (i > 0) GroupDivider(18.dp)
                        GroupRow(
                            title = d.name,
                            subtitle = d.description.ifBlank { d.whenToUse },
                            onClick = { nav.open(SubagentRoutes.EDIT + d.name) },
                            trailing = { RowChevron() },
                        )
                    }
                }
            }
            Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                CompactButton(stringResource(R.string.sa_new), onClick = { nav.open(SubagentRoutes.NEW) }, primary = false)
            }
        }
        if (runs.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.sa_recent)) }
            item {
                Group {
                    runs.asReversed().take(10).forEachIndexed { i, r ->
                        if (i > 0) GroupDivider(18.dp)
                        GroupRow(
                            title = "${r.agent} · ${statusLabel(r.status)}",
                            subtitle = r.prompt.lineSequence().firstOrNull().orEmpty().take(90),
                            onClick = { nav.open(SubagentRoutes.RUN + r.id) },
                            trailing = { RowChevron() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun statusLabel(s: SubagentStatus): String = stringResource(
    when (s) {
        SubagentStatus.Running -> R.string.sa_status_running
        SubagentStatus.Done -> R.string.sa_status_done
        SubagentStatus.Partial -> R.string.sa_status_partial
        SubagentStatus.Failed -> R.string.sa_status_failed
        SubagentStatus.Cancelled -> R.string.sa_status_cancelled
    },
)

private val CATEGORIES = listOf(
    "read" to R.string.sa_cat_read,
    "web" to R.string.sa_cat_web,
    "file" to R.string.sa_cat_file,
    "terminal" to R.string.sa_cat_terminal,
    "mcp" to R.string.sa_cat_mcp,
    "memory" to R.string.sa_cat_memory,
    "device" to R.string.sa_cat_device,
    "phone" to R.string.sa_cat_phone,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SubagentEditScreen(store: SubagentStore, existing: String?, nav: FeatureNav) {
    val original = existing?.let { n -> store.custom.value.firstOrNull { it.name == n } }
    var name by rememberSaveable { mutableStateOf(original?.name.orEmpty()) }
    var description by rememberSaveable { mutableStateOf(original?.description.orEmpty()) }
    var whenToUse by rememberSaveable { mutableStateOf(original?.whenToUse.orEmpty()) }
    var instructions by rememberSaveable { mutableStateOf(original?.instructions.orEmpty()) }
    var tools by rememberSaveable { mutableStateOf((original?.tools ?: SubagentDef.DEFAULT_TOOLS).joinToString(",")) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val selected = tools.split(',').filter { it.isNotBlank() }.toSet()

    LargeTitleScreen(
        title = stringResource(if (original == null) R.string.sa_new_title else R.string.sa_edit_title),
        onBack = nav::back,
        bottomBar = {
            BottomActionBar {
                PrimaryButton(
                    stringResource(R.string.ext_save),
                    enabled = name.isNotBlank() && instructions.isNotBlank(),
                    onClick = {
                        val clean = name.trim()
                        val problem = SubagentDef.validateName(clean)
                            ?: if (original?.name != clean && store.exists(clean)) "\"$clean\" already exists." else null
                        if (problem != null) {
                            error = problem
                        } else {
                            store.save(SubagentDef(clean, description.trim(), whenToUse.trim(), selected.toList(), instructions), original?.name)
                            nav.back()
                        }
                    },
                )
            }
        },
    ) {
        item {
            FieldLabel(stringResource(R.string.sa_name))
            ExtendField(name, { name = it.lowercase().replace(' ', '-'); error = null }, stringResource(R.string.sa_name_hint), mono = true)
            error?.let { Notice(it, true) }
            FieldLabel(stringResource(R.string.sa_description))
            ExtendField(description, { description = it }, stringResource(R.string.sa_description_hint))
            FieldLabel(stringResource(R.string.sa_when))
            ExtendField(whenToUse, { whenToUse = it }, stringResource(R.string.sa_when_hint), singleLine = false, minLines = 2)
            FieldLabel(stringResource(R.string.sa_tools))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CATEGORIES.forEach { (key, label) ->
                    val on = key in selected
                    CompactButton(
                        stringResource(label),
                        primary = on,
                        onClick = { tools = (if (on) selected - key else selected + key).joinToString(",") },
                    )
                }
            }
            Hint(stringResource(R.string.sa_tools_hint))
            FieldLabel(stringResource(R.string.sa_instructions))
            ExtendField(instructions, { instructions = it }, stringResource(R.string.sa_instructions_hint), singleLine = false, minLines = 8)
        }
        if (original != null) {
            item {
                Spacer(Modifier.height(20.dp))
                Group {
                    GroupRow(stringResource(R.string.ext_delete), titleColor = MaterialTheme.colorScheme.error, onClick = { confirmDelete = true })
                }
            }
        }
    }

    if (confirmDelete && original != null) {
        ConfirmDialog(
            title = stringResource(R.string.sa_delete_title, original.name),
            text = stringResource(R.string.sa_delete_body),
            confirm = stringResource(R.string.ext_delete),
            destructive = true,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                store.delete(original.name)
                nav.back()
            },
        )
    }
}

@Composable
internal fun SubagentRunScreen(id: String, nav: FeatureNav) {
    val runs by SubagentRuns.runs.collectAsState()
    val run = runs.firstOrNull { it.id == id }
    LargeTitleScreen(title = run?.agent ?: stringResource(R.string.sa_run_title), onBack = nav::back) {
        item {
            if (run == null) Hint(stringResource(R.string.sa_run_missing)) else SubagentRunDetails(run)
        }
    }
}

/** The details sheet body: task, live steps, streamed text, summary, tool calls and token usage. */
@Composable
fun SubagentRunDetails(run: SubagentRun, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(
                when (run.status) {
                    SubagentStatus.Done -> MaterialTheme.extras.success
                    SubagentStatus.Failed -> c.error
                    SubagentStatus.Partial, SubagentStatus.Cancelled -> MaterialTheme.extras.warning
                    SubagentStatus.Running -> c.onSurface
                },
            )
            Spacer(Modifier.width(10.dp))
            Text(statusLabel(run.status), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.sa_stats, run.toolCalls, run.inputTokens, run.outputTokens) + (run.model?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelMedium,
                color = c.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AtomCard {
            Text(stringResource(R.string.sa_prompt), style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant)
            SelectionContainer { Text(run.prompt, style = MaterialTheme.typography.bodyMedium) }
        }
        if (run.steps.isNotEmpty()) {
            AtomCard {
                Text(stringResource(R.string.sa_steps), style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant)
                run.steps.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(
                            when (s.state) {
                                SubagentStepState.Done -> MaterialTheme.extras.success
                                SubagentStepState.Failed -> c.error
                                SubagentStepState.Running -> c.onSurfaceVariant
                            },
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.tool, style = MaterialTheme.extras.mono)
                            if (s.arguments.isNotBlank() && s.arguments != "{}") {
                                Text(s.arguments, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        s.durationMs?.let { Text("${it} ms", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant) }
                    }
                }
            }
        }
        if (run.status == SubagentStatus.Running && run.text.isNotBlank()) {
            AtomCard {
                Text(stringResource(R.string.sa_live), style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant)
                Text(run.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
        run.summary?.let { summary ->
            AtomCard {
                Text(stringResource(R.string.sa_summary), style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant)
                SelectionContainer { Text(summary, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}
