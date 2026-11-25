@file:OptIn(ExperimentalLayoutApi::class)

package atom.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import atom.app.R
import atom.app.data.Attachment
import atom.core.llm.ReasoningEffort
import atom.core.llm.ReasoningMode
import atom.core.runtime.QueuedMessage
import atom.ui.components.AtomMenu
import atom.ui.components.AtomMenuItem
import atom.ui.components.ScreenIcons

internal class ComposerCallbacks(
    val onSend: () -> Unit,
    val onStop: () -> Unit,
    val onCancelEdit: () -> Unit,
    val onRemoveAttachment: (Attachment) -> Unit,
    val onPickImages: () -> Unit,
    val onPickFiles: () -> Unit,
    val onPickFolder: () -> Unit,
    val onEnterPath: () -> Unit,
    val onReasoningToggle: (Boolean) -> Unit,
    val onEffort: (ReasoningEffort?) -> Unit,
    val onRemoveQueued: (Long) -> Unit,
    val onSendNow: (Long) -> Unit,
)

@Composable
internal fun Composer(
    input: TextFieldState,
    state: ChatUiState,
    attachments: List<Attachment>,
    editing: EditState?,
    callbacks: ComposerCallbacks,
    micSlot: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AnimatedVisibility(
            visible = state.queued.isNotEmpty(),
            enter = fadeIn(tween(QUICK)) + expandVertically(tween(200)),
            exit = fadeOut(tween(150)) + shrinkVertically(tween(180)),
        ) {
            QueuePanel(state.queued, state.running, callbacks)
        }
        if (editing != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(ChatGlyphs.Edit, null, Modifier.size(14.dp), tint = colors.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.composer_editing), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(
                    stringResource(R.string.action_cancel),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurface,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = callbacks.onCancelEdit).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        Surface(color = colors.surfaceContainerHigh, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
            Column {
                if (attachments.isNotEmpty()) {
                    FlowRow(
                        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        attachments.forEach { a -> AttachmentChip(a, onRemove = { callbacks.onRemoveAttachment(a) }) }
                    }
                }
                Box(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 2.dp)) {
                    if (input.text.isEmpty()) {
                        Text(
                            stringResource(if (state.running) R.string.composer_hint_running else R.string.composer_hint),
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    BasicTextField(
                        state = input,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                        cursorBrush = SolidColor(colors.primary),
                        lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 6),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(start = 6.dp, end = 10.dp, bottom = 8.dp, top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AttachButton(callbacks)
                    ReasoningControl(state.reasoning, callbacks)
                    Spacer(Modifier.weight(1f))
                    micSlot()
                    Spacer(Modifier.width(6.dp))
                    val hasContent = input.text.isNotBlank() || attachments.isNotEmpty()
                    SendButton(running = state.running, canSend = hasContent, onSend = callbacks.onSend, onStop = callbacks.onStop)
                }
            }
        }
    }
}

@Composable
private fun QueuePanel(queued: List<QueuedMessage>, running: Boolean, callbacks: ComposerCallbacks) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainer.copy(alpha = 0.94f), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 6.dp)) {
            Text(stringResource(R.string.queue_label, queued.size), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            queued.forEach { q ->
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        q.text.ifBlank { stringResource(R.string.queue_attachments_only) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    when {
                        q.steer -> Text(stringResource(R.string.queue_next_step), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
                        !q.canSteer && running -> Text(stringResource(R.string.queue_after_task), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
                        else -> Surface(onClick = { callbacks.onSendNow(q.id) }, shape = RoundedCornerShape(50), color = colors.surfaceContainerHighest) {
                            Text(stringResource(R.string.queue_send_now), style = MaterialTheme.typography.labelMedium, color = colors.onSurface, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                        }
                    }
                    IconButton(onClick = { callbacks.onRemoveQueued(q.id) }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Filled.Close, stringResource(R.string.action_remove), Modifier.size(15.dp), tint = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachButton(callbacks: ComposerCallbacks) {
    val colors = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Box {
        IconButton(onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            open = true
        }, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Add, stringResource(R.string.composer_attach), Modifier.size(22.dp), tint = colors.onSurface)
        }
        AtomMenu(expanded = open, onDismiss = { open = false }) {
            MenuAction(stringResource(R.string.attach_image), ChatGlyphs.Image) {
                open = false
                callbacks.onPickImages()
            }
            MenuAction(stringResource(R.string.attach_file), ChatGlyphs.File) {
                open = false
                callbacks.onPickFiles()
            }
            MenuAction(stringResource(R.string.attach_folder), ChatGlyphs.Folder) {
                open = false
                callbacks.onPickFolder()
            }
            MenuAction(stringResource(R.string.attach_path), ChatGlyphs.Path) {
                open = false
                callbacks.onEnterPath()
            }
        }
    }
}

/** "Think" / "Deep Think" chip plus the per-conversation effort picker, limited to what the model accepts. */
@Composable
private fun ReasoningControl(ui: ReasoningUi, callbacks: ComposerCallbacks) {
    val support = ui.support
    if (support.mode == ReasoningMode.Unsupported) return
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val on = support.mode == ReasoningMode.Always || ui.enabled
    val fixed = support.mode == ReasoningMode.Always && support.levels.isEmpty()
    val container by animateColorAsState(if (on && !fixed) colors.primary else colors.surfaceContainerHighest, tween(QUICK), label = "thinkBg")
    val content by animateColorAsState(if (on && !fixed) colors.onPrimary else colors.onSurface, tween(QUICK), label = "thinkFg")
    val label = when {
        fixed -> stringResource(R.string.reasoning_fixed_default)
        !on -> stringResource(R.string.reasoning_think)
        ui.effort != null -> stringResource(R.string.reasoning_deep_with, effortLabel(ui.effort))
        else -> stringResource(R.string.reasoning_deep)
    }
    Box {
        Surface(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                when {
                    fixed -> Unit
                    support.mode == ReasoningMode.Always -> menu = true
                    else -> callbacks.onReasoningToggle(!ui.enabled)
                }
            },
            shape = RoundedCornerShape(50),
            color = container,
            contentColor = content,
            modifier = Modifier.height(34.dp),
        ) {
            Row(Modifier.padding(start = 12.dp, end = if (fixed) 12.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(ChatGlyphs.Brain, null, Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, maxLines = 1)
                if (!fixed) {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(28.dp)) {
                        Icon(ScreenIcons.ChevronDown, stringResource(R.string.reasoning_effort), Modifier.size(16.dp))
                    }
                }
            }
        }
        AtomMenu(expanded = menu, onDismiss = { menu = false }) {
            if (support.canDisable) {
                AtomMenuItem(stringResource(R.string.effort_off), checked = !ui.enabled) {
                    menu = false
                    callbacks.onEffort(ReasoningEffort.Off)
                }
            }
            AtomMenuItem(stringResource(R.string.effort_default), checked = on && ui.effort == null) {
                menu = false
                callbacks.onEffort(null)
            }
            support.levels.forEach { level ->
                AtomMenuItem(effortLabel(level), checked = on && ui.effort == level) {
                    menu = false
                    callbacks.onEffort(level)
                }
            }
        }
    }
}

@Composable
internal fun effortLabel(e: ReasoningEffort): String = stringResource(
    when (e) {
        ReasoningEffort.Off -> R.string.effort_off
        ReasoningEffort.Low -> R.string.effort_low
        ReasoningEffort.Medium -> R.string.effort_medium
        ReasoningEffort.High -> R.string.effort_high
        ReasoningEffort.XHigh -> R.string.effort_xhigh
        ReasoningEffort.Max -> R.string.effort_max
    },
)

/** White disc with a black arrow when there is something to send; a stop square while running with nothing typed. */
@Composable
private fun SendButton(running: Boolean, canSend: Boolean, onSend: () -> Unit, onStop: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val stopMode = running && !canSend
    val active = canSend || stopMode
    val container by animateColorAsState(if (active) colors.primary else colors.surfaceContainerHighest, tween(QUICK), label = "sendBg")
    val glyph by animateColorAsState(if (active) colors.onPrimary else colors.onSurfaceVariant.copy(alpha = 0.6f), tween(QUICK), label = "sendFg")
    val description = stringResource(if (stopMode) R.string.action_stop else if (running) R.string.composer_queue else R.string.action_send)
    Surface(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            if (stopMode) onStop() else onSend()
        },
        enabled = active,
        shape = CircleShape,
        color = container,
        modifier = Modifier.size(40.dp).semantics { contentDescription = description },
    ) {
        Crossfade(targetState = stopMode, animationSpec = tween(160), label = "sendGlyph") { stop ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (stop) {
                    Box(Modifier.size(12.dp).background(glyph, RoundedCornerShape(3.dp)))
                } else {
                    Icon(ChatGlyphs.ArrowUp, null, Modifier.size(20.dp), tint = glyph)
                }
            }
        }
    }
}
