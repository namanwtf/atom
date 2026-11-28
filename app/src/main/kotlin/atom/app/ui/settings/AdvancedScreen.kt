package atom.app.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.JoinLeft
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import atom.app.R
import atom.app.data.AppSettings
import atom.core.agent.LimitOverrides
import atom.ui.components.DropdownRow
import atom.ui.components.Group
import atom.ui.components.LargeTitleScreen
import atom.ui.components.SectionLabel
import atom.ui.components.SwitchRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Settings > Advanced: limits for power users. Every row starts on "Auto (recommended)", where the value follows the
 * selected model (its context window and output limit; small on-device models keep their tight limits).
 */
@Composable
fun AdvancedScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val limits = (settings ?: AppSettings()).limits
    val auto = stringResource(R.string.adv_auto)
    val save: (LimitOverrides) -> Unit = vm::setLimits

    LargeTitleScreen(title = stringResource(R.string.adv_title), onBack = onBack) {
        item(key = "intro") {
            Text(
                stringResource(R.string.adv_intro),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item(key = "h-model") { SectionLabel(stringResource(R.string.adv_section_model)) }
        item(key = "g-model") {
            Group {
                Choice(
                    title = stringResource(R.string.adv_max_output),
                    summary = stringResource(R.string.adv_max_output_sub),
                    auto = auto,
                    values = listOf(8_192, 16_384, 32_768, 65_536, 131_072),
                    label = { tokens(it) },
                    selected = limits.maxOutputTokens,
                    icon = Icons.AutoMirrored.Rounded.Notes,
                ) { save(limits.copy(maxOutputTokens = it)) }
                Choice(
                    title = stringResource(R.string.adv_max_steps),
                    summary = stringResource(R.string.adv_max_steps_sub),
                    auto = auto,
                    values = listOf(50, 100, 200, 800, 1_500),
                    label = { "$it" },
                    selected = limits.maxSteps,
                    icon = Icons.Rounded.Repeat,
                ) { save(limits.copy(maxSteps = it)) }
                Choice(
                    title = stringResource(R.string.adv_tool_result),
                    summary = stringResource(R.string.adv_tool_result_sub),
                    auto = auto,
                    values = listOf(8_000, 16_000, 32_000, 64_000, 128_000),
                    label = { stringResource(R.string.adv_chars, "%,d".format(it)) },
                    selected = limits.toolResultChars,
                    icon = Icons.AutoMirrored.Rounded.Article,
                ) { save(limits.copy(toolResultChars = it)) }
                Choice(
                    title = stringResource(R.string.adv_images),
                    summary = stringResource(R.string.adv_images_sub),
                    auto = auto,
                    values = listOf(1, 2, 4, 6, 12),
                    label = { "$it" },
                    selected = limits.imagesKept,
                    icon = Icons.Rounded.Image,
                ) { save(limits.copy(imagesKept = it)) }
            }
        }
        item(key = "h-time") { SectionLabel(stringResource(R.string.adv_section_time)) }
        item(key = "g-time") {
            Group {
                Choice(
                    title = stringResource(R.string.adv_subagent),
                    summary = stringResource(R.string.adv_subagent_sub),
                    auto = auto,
                    values = listOf(5, 10, 30, 60),
                    label = { minutes(it) },
                    selected = limits.subagentMinutes,
                    icon = Icons.Rounded.JoinLeft,
                ) { save(limits.copy(subagentMinutes = it)) }
                Choice(
                    title = stringResource(R.string.adv_approval),
                    summary = stringResource(R.string.adv_approval_sub),
                    auto = auto,
                    values = listOf(1, 5, 30, 60),
                    label = { minutes(it) },
                    selected = limits.approvalWaitMinutes,
                    icon = Icons.Rounded.PanTool,
                ) { save(limits.copy(approvalWaitMinutes = it)) }
                SwitchRow(
                    title = stringResource(R.string.adv_approval_pause),
                    summary = stringResource(R.string.adv_approval_pause_sub),
                    checked = limits.approvalPausesRun,
                    onCheckedChange = { save(limits.copy(approvalPausesRun = it)) },
                    icon = Icons.Rounded.PauseCircle,
                )
                Choice(
                    title = stringResource(R.string.adv_task),
                    summary = stringResource(R.string.adv_task_sub),
                    auto = auto,
                    values = listOf(5, 10, 30, 60),
                    label = { minutes(it) },
                    selected = limits.taskMinutes,
                    icon = Icons.Rounded.Schedule,
                ) { save(limits.copy(taskMinutes = it)) }
            }
        }
    }
}

/** A dropdown whose first entry is "Auto (recommended)" (null); a saved value that is not listed still shows. */
@Composable
private fun Choice(
    title: String,
    summary: String,
    auto: String,
    values: List<Int>,
    label: @Composable (Int) -> String,
    selected: Int?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onSelect: (Int?) -> Unit,
) {
    val choices: List<Int?> = listOf<Int?>(null) + (values + listOfNotNull(selected)).distinct().sorted()
    val items = choices.map { if (it == null) auto else label(it) }
    DropdownRow(
        title = title,
        items = items,
        selectedIndex = choices.indexOf(selected).coerceAtLeast(0),
        onSelect = { i -> onSelect(choices[i]) },
        icon = icon,
        summary = summary,
    )
}

@Composable
private fun minutes(n: Int): String = stringResource(R.string.adv_minutes, n)

private fun tokens(n: Int): String = if (n % 1_024 == 0) "${n / 1_024}K tokens" else "%,d tokens".format(n)
