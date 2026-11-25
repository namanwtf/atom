@file:OptIn(ExperimentalMaterial3Api::class)

package atom.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import atom.app.R
import atom.app.ui.common.formatTokens
import atom.app.ui.common.humanizeTool
import atom.extend.subagent.SubagentRun
import atom.extend.subagent.SubagentStatus
import atom.extend.subagent.SubagentStepState
import atom.ui.theme.extras

/** "N subagents" chip under the transcript while a task (or the last one) spawned subagents. */
@Composable
internal fun SubagentChip(count: Int, running: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = colors.surfaceContainer.copy(alpha = 0.92f)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            if (running) RunningDot() else Dot(colors.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(pluralStringResource(R.plurals.chat_subagents_chip, count, count), style = MaterialTheme.typography.labelMedium, color = colors.onSurface, maxLines = 1)
        }
    }
}

/** Details of the subagents a run spawned: status, model, steps, live text and the final summary. */
@Composable
internal fun SubagentSheet(runs: List<SubagentRun>, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceContainer,
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(50)).background(colors.outline))
        },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.chat_subagents_title), style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
            Spacer(Modifier.size(12.dp))
            if (runs.isEmpty()) {
                Text(stringResource(R.string.chat_subagents_empty), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                Spacer(Modifier.size(24.dp))
            } else {
                LazyColumn(Modifier.heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(runs, key = { it.id }) { SubagentCard(it) }
                    item { Spacer(Modifier.size(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun SubagentCard(run: SubagentRun) {
    val colors = MaterialTheme.colorScheme
    var open by rememberSaveable(run.id) { mutableStateOf(false) }
    Surface(color = colors.surfaceContainerHigh, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.clickable { open = !open }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusMark(run.status)
                Spacer(Modifier.width(10.dp))
                Text(run.agent, style = MaterialTheme.typography.labelLarge, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(statusLabel(run.status), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            Text(
                run.prompt,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = if (open) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    run.model,
                    stringResource(R.string.subagent_steps, run.steps.size, formatTokens(run.inputTokens), formatTokens(run.outputTokens)),
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (open) {
                run.steps.forEach { step ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when (step.state) {
                            SubagentStepState.Running -> RunningDot()
                            SubagentStepState.Done -> Dot(MaterialTheme.extras.success)
                            SubagentStepState.Failed -> Dot(colors.error)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(humanizeTool(step.tool), style = MaterialTheme.typography.labelMedium, color = colors.onSurface, maxLines = 1)
                        Spacer(Modifier.width(8.dp))
                        Text(step.arguments, style = MaterialTheme.extras.mono, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                }
                val result = run.summary ?: run.text.takeIf { it.isNotBlank() } ?: run.error
                if (result != null) {
                    Text(stringResource(R.string.subagent_result), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    SelectionContainer {
                        Text(
                            result,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (run.status == SubagentStatus.Failed) colors.error else colors.onSurface,
                            modifier = Modifier.fillMaxWidth().background(colors.surfaceContainer, RoundedCornerShape(12.dp)).padding(10.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusMark(status: SubagentStatus) {
    val colors = MaterialTheme.colorScheme
    when (status) {
        SubagentStatus.Running -> RunningDot()
        SubagentStatus.Done -> Dot(MaterialTheme.extras.success)
        SubagentStatus.Partial -> Dot(MaterialTheme.extras.warning)
        SubagentStatus.Failed -> Dot(colors.error)
        SubagentStatus.Cancelled -> Dot(colors.onSurfaceVariant)
    }
}

@Composable
private fun statusLabel(status: SubagentStatus): String = stringResource(
    when (status) {
        SubagentStatus.Running -> R.string.subagent_status_running
        SubagentStatus.Done -> R.string.subagent_status_done
        SubagentStatus.Partial -> R.string.subagent_status_partial
        SubagentStatus.Failed -> R.string.subagent_status_failed
        SubagentStatus.Cancelled -> R.string.subagent_status_cancelled
    },
)
