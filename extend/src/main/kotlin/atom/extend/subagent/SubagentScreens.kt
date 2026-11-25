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
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextAlign
import atom.ui.components.ArrowRow
import atom.ui.components.AtomRow
import atom.ui.components.AtomTextButton
import atom.ui.components.SpinnerOption
import atom.ui.components.SpinnerRow
import atom.ui.components.SwitchRow
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.theme.MiuixTheme
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
    const val VIEW = "extend/subagents/view/"
}

@Composable
internal fun SubagentsScreen(store: SubagentStore, nav: FeatureNav) {
    val settings by store.settings.collectAsState()
    val custom by store.custom.collectAsState()
    val runs by SubagentRuns.runs.collectAsState()
    val available = Extend.engines != null
    val followMain = stringResource(R.string.sa_follow_main)
    val modelDefault = stringResource(R.string.sa_model_default)
    val on = available && settings.enabled

    LargeTitleScreen(
        title = stringResource(R.string.sa_title),
        onBack = nav::back,
        actions = {
            IconButton(onClick = { nav.open(SubagentRoutes.NEW) }, modifier = Modifier.padding(end = 12.dp)) {
                Icon(MiuixIcons.Add, stringResource(R.string.sa_new), tint = MiuixTheme.colorScheme.onSurface)
            }
        },
    ) {
        item { SectionLabel(stringResource(R.string.sa_general)) }
        item {
            Group {
                SwitchRow(
                    title = stringResource(R.string.sa_enable),
                    checked = settings.enabled && available,
                    onCheckedChange = { v -> store.updateSettings { it.copy(enabled = v) } },
                    summary = stringResource(if (available) R.string.sa_enable_sub else R.string.sa_unavailable),
                    enabled = available,
                )
                val models = Extend.engines?.models().orEmpty()
                SpinnerRow(
                    title = stringResource(R.string.sa_model),
                    options = listOf(SpinnerOption(followMain)) + models.map { SpinnerOption(it.label) },
                    selectedIndex = settings.modelId?.let { id -> models.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(1) } ?: 0,
                    onSelect = { i ->
                        val m = models.getOrNull(i - 1)
                        store.updateSettings { it.copy(modelId = m?.id, modelLabel = m?.label) }
                    },
                    summary = if (settings.modelId != null && models.none { it.id == settings.modelId }) settings.modelLabel ?: settings.modelId else null,
                    enabled = on,
                )
                val efforts = ReasoningEffort.entries
                SpinnerRow(
                    title = stringResource(R.string.sa_reasoning),
                    options = listOf(SpinnerOption(modelDefault)) + efforts.map { SpinnerOption(it.name) },
                    selectedIndex = settings.reasoning?.let { efforts.indexOf(it) + 1 } ?: 0,
                    onSelect = { i -> store.updateSettings { it.copy(reasoning = efforts.getOrNull(i - 1)) } },
                    enabled = on,
                )
            }
        }
        item { SectionLabel(stringResource(R.string.sa_builtin)) }
        item {
            Group {
                BuiltInSubagents.all.forEach { d ->
                    ArrowRow(title = d.name, onClick = { nav.open(SubagentRoutes.VIEW + d.name) }, summary = d.description)
                }
            }
        }
        item { SectionLabel(stringResource(R.string.sa_custom)) }
        item {
            Group {
                if (custom.isEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.sa_none), style = MiuixTheme.textStyles.headline1, color = MiuixTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.sa_none_body),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(12.dp))
                        AtomTextButton(stringResource(R.string.sa_new), { nav.open(SubagentRoutes.NEW) }, primary = true)
                    }
                }
                custom.forEach { d ->
                    ArrowRow(
                        title = d.name,
                        onClick = { nav.open(SubagentRoutes.EDIT + d.name) },
                        summary = d.description.ifBlank { d.whenToUse },
                    )
                }
            }
        }
        if (runs.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.sa_recent)) }
            item {
                Group {
                    runs.asReversed().take(10).forEach { r ->
                        ArrowRow(
                            title = r.agent,
                            onClick = { nav.open(SubagentRoutes.RUN + r.id) },
                            summary = SubagentRunLabels.status(r) + " · " + r.prompt.lineSequence().firstOrNull().orEmpty().take(90),
                        )
                    }
                }
            }
        }
    }
}

/** Read-only view of a built-in subagent: what it is for, its tools and its instructions. */
@Composable
internal fun SubagentViewScreen(name: String, nav: FeatureNav) {
    val def = BuiltInSubagents.all.firstOrNull { it.name == name }
    LargeTitleScreen(title = def?.name ?: stringResource(R.string.sa_title), onBack = nav::back) {
        if (def == null) {
            item { Hint(stringResource(R.string.sa_run_missing), Modifier.padding(top = 12.dp)) }
            return@LargeTitleScreen
        }
        item { SectionLabel(stringResource(R.string.sa_builtin_one)) }
        item {
            Group {
                AtomRow(title = stringResource(R.string.sa_description), summary = def.description)
                AtomRow(title = stringResource(R.string.sa_when), summary = def.whenToUse)
                val toolNames = buildList {
                    for (key in def.tools) {
                        val res = CATEGORIES.firstOrNull { it.first == key }?.second
                        add(if (res != null) stringResource(res) else key)
                    }
                }
                AtomRow(title = stringResource(R.string.sa_tools), summary = toolNames.joinToString())
                if (def.readOnly) AtomRow(title = stringResource(R.string.sa_read_only), summary = stringResource(R.string.sa_read_only_sub))
            }
        }
        item { SectionLabel(stringResource(R.string.sa_instructions)) }
        item {
            Group {
                SelectionContainer {
                    Text(
                        def.instructions,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
            }
        }
    }
}

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
            if (run == null) Hint(stringResource(R.string.sa_run_missing)) else SubagentRunDetails(run, Modifier.padding(top = 12.dp))
        }
    }
}

/**
 * The details sheet body: status, token chips (In / Cached / Out), "Prompt", the latest steps (earlier ones
 * folded), "Streaming" while it runs, then "Summary".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubagentRunDetails(run: SubagentRun, modifier: Modifier = Modifier) {
    val c = MiuixTheme.colorScheme
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
            Text(SubagentRunLabels.status(run), style = MiuixTheme.textStyles.body1, color = c.onSurface)
            run.model?.let {
                Spacer(Modifier.width(10.dp))
                Text(it, style = MiuixTheme.textStyles.footnote1, color = c.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TokenChip(stringResource(R.string.sa_tokens_in), run.inputTokens)
            TokenChip(stringResource(R.string.sa_tokens_cached), run.cachedTokens)
            TokenChip(stringResource(R.string.sa_tokens_out), run.outputTokens)
        }
        DetailCard(stringResource(R.string.sa_prompt)) {
            SelectionContainer { Text(run.prompt, style = MiuixTheme.textStyles.body2, color = c.onSurface) }
        }
        if (run.steps.isNotEmpty()) {
            val (folded, shown) = SubagentRunLabels.visibleSteps(run.steps)
            DetailCard(stringResource(R.string.sa_steps)) {
                if (folded > 0) {
                    Text(
                        pluralStringResource(R.plurals.sa_steps_folded, folded, folded),
                        style = MiuixTheme.textStyles.footnote1,
                        color = c.onSurfaceVariantSummary,
                    )
                }
                shown.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(
                            when (s.state) {
                                SubagentStepState.Done -> MaterialTheme.extras.success
                                SubagentStepState.Failed -> c.error
                                SubagentStepState.Running -> c.onSurfaceVariantSummary
                            },
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.tool, style = MaterialTheme.extras.mono, color = c.onSurface)
                            if (s.arguments.isNotBlank() && s.arguments != "{}") {
                                Text(s.arguments, style = MiuixTheme.textStyles.footnote1, color = c.onSurfaceVariantSummary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        s.durationMs?.let { Text("$it ms", style = MiuixTheme.textStyles.footnote2, color = c.onSurfaceVariantSummary) }
                    }
                }
            }
        }
        if (run.status == SubagentStatus.Running && run.text.isNotBlank()) {
            DetailCard(stringResource(R.string.sa_live)) { Text(run.text, style = MiuixTheme.textStyles.body2, color = c.onSurface) }
        }
        run.summary?.let { summary ->
            DetailCard(stringResource(R.string.sa_summary)) {
                SelectionContainer { Text(summary, style = MiuixTheme.textStyles.body2, color = c.onSurface) }
            }
        }
    }
}

@Composable
private fun DetailCard(label: String, content: @Composable () -> Unit) {
    Group {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            content()
        }
    }
}

@Composable
private fun TokenChip(label: String, value: Int) {
    Text(
        "$label ${formatTokens(value)}",
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MiuixTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

private fun formatTokens(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 10_000 -> "${n / 1000}k"
    n >= 1_000 -> "%.1fk".format(n / 1000.0)
    else -> n.toString()
}
