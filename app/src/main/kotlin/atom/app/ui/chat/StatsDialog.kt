package atom.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import atom.app.R
import atom.app.ui.common.formatDuration
import atom.app.ui.common.formatTokens
import java.util.Locale

/** Session stats popover: turns, steps, timings, speed, tokens, cache, compaction and context use. */
@Composable
internal fun StatsDialog(state: ChatUiState, onCompact: () -> Unit, onDismiss: () -> Unit) {
    val s = state.stats
    val colors = MaterialTheme.colorScheme
    AtomDialog(
        onDismissRequest = onDismiss,
        icon = ChatGlyphs.Stats,
        title = stringResource(R.string.stats_title),
        dismissLabel = stringResource(R.string.action_done),
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.menu_compact),
        confirmEnabled = !state.running && state.conversationId != null,
        onConfirm = {
            onDismiss()
            onCompact()
        },
    ) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val ctx = state.context
            if (ctx != null) {
                val window = ctx.windowTokens
                StatRow(
                    stringResource(R.string.stats_context),
                    if (window != null) {
                        stringResource(R.string.stats_context_value, formatTokens(ctx.usedTokens), formatTokens(window), ((ctx.fraction ?: 0f) * 100).toInt())
                    } else {
                        formatTokens(ctx.usedTokens)
                    },
                )
                Text(
                    stringResource(if (window != null) R.string.stats_autocompact_on else R.string.stats_autocompact_unknown),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant)
                Spacer(Modifier.height(4.dp))
            }
            StatRow(stringResource(R.string.stats_turns), s.turns.toString())
            StatRow(stringResource(R.string.stats_steps), s.steps.toString())
            StatRow(stringResource(R.string.stats_model_time), formatDuration(s.modelTimeMs))
            StatRow(stringResource(R.string.stats_tool_time), stringResource(R.string.stats_tool_value, formatDuration(s.toolTimeMs), s.toolCalls, s.toolFailures))
            s.averageTtftMs?.let { StatRow(stringResource(R.string.stats_ttft), formatDuration(it)) }
            s.outputTokensPerSecond?.let { StatRow(stringResource(R.string.stats_speed), String.format(Locale.getDefault(), "%.1f tok/s", it)) }
            StatRow(stringResource(R.string.stats_tokens), stringResource(R.string.usage_tokens, formatTokens(s.inputTokens.toInt()), formatTokens(s.outputTokens.toInt())))
            if (s.cacheReadTokens > 0 || s.cacheWriteTokens > 0) {
                val hit = s.cacheHitRate?.let { (it * 100).toInt() } ?: 0
                StatRow(stringResource(R.string.stats_cache), stringResource(R.string.stats_cache_value, formatTokens(s.cacheReadTokens.toInt()), formatTokens(s.cacheWriteTokens.toInt()), hit))
                StatRow(stringResource(R.string.stats_cache_misses), stringResource(R.string.stats_cache_misses_value, s.cacheMisses, s.modelCalls))
            } else {
                StatRow(stringResource(R.string.stats_cache), stringResource(R.string.stats_cache_none))
            }
            if (s.subagentRuns > 0) {
                StatRow(
                    stringResource(R.string.stats_subagents),
                    stringResource(
                        R.string.stats_subagents_value,
                        s.subagentRuns,
                        s.subagentToolCalls,
                        stringResource(R.string.usage_tokens, formatTokens(s.subagentInputTokens.toInt()), formatTokens(s.subagentOutputTokens.toInt())),
                    ),
                )
            }
            if (s.compactions > 0) {
                StatRow(stringResource(R.string.stats_compactions), stringResource(R.string.stats_compactions_value, s.compactions, formatTokens(s.compactionTokens.toInt())))
                s.compactionPaybackRequests?.let {
                    StatRow(stringResource(R.string.stats_payback), stringResource(R.string.stats_payback_value, String.format(Locale.getDefault(), "%.1f", it)))
                }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}
