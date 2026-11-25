@file:OptIn(ExperimentalLayoutApi::class)

package atom.app.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import atom.app.R
import atom.app.data.Attachment
import atom.core.llm.ReasoningEffort
import atom.core.llm.ReasoningMode
import atom.core.runtime.QueuedMessage
import atom.ui.components.AtomMenu
import atom.ui.components.AtomMenuItem
import atom.ui.theme.extras

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
    /** The context ring opens the session stats (with "Compact context"). */
    val onStats: () -> Unit = {},
)

/**
 * The floating composer: a 24dp container with the text area on top and one action row under it
 * (+, Deep Think, context ring, mic, send / stop).
 */
@Composable
internal fun Composer(
    input: TextFieldState,
    state: ChatUiState,
    attachments: List<Attachment>,
    editing: EditState?,
    editReplacesLater: Boolean,
    callbacks: ComposerCallbacks,
    micSlot: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val ink = homeInk()
    val shape = RoundedCornerShape(24.dp)
    val fill = when {
        ink.dark -> ink.solidInk(0.095f, 0.05f)
        colors.surfaceContainer == colors.surface -> androidx.compose.ui.graphics.Color(0xFFF5F6F8)
        else -> colors.surfaceContainer
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AnimatedVisibility(
            visible = state.queued.isNotEmpty(),
            enter = fadeIn(tween(170)) + expandVertically(expandFrom = Alignment.Bottom),
            exit = fadeOut(tween(150)) + shrinkVertically(shrinkTowards = Alignment.Bottom),
        ) {
            QueuePanel(state.queued, state.running, callbacks)
        }
        if (editing != null) {
            Text(
                stringResource(if (editReplacesLater) R.string.composer_edit_replace_later else R.string.composer_edit_replace),
                fontSize = 13.sp,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 18.dp),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(fill)
                .border(0.5.dp, if (editing != null) colors.primary.copy(alpha = 0.38f) else ink.ink(0.08f, 0.06f), shape)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            if (attachments.isNotEmpty()) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    attachments.forEach { a -> AttachmentChip(a, onRemove = { callbacks.onRemoveAttachment(a) }) }
                }
            }
            val textStyle = TextStyle(fontSize = 16.sp, lineHeight = 22.sp)
            Box(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(horizontal = 6.dp, vertical = 5.dp), contentAlignment = Alignment.CenterStart) {
                if (input.text.isEmpty()) {
                    Text(
                        placeholder(state),
                        style = textStyle,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    state = input,
                    textStyle = textStyle.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 6),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (editing != null) {
                    IconButton(onClick = callbacks.onCancelEdit, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.Close, stringResource(R.string.composer_cancel_edit), Modifier.size(20.dp), tint = colors.onSurface.copy(alpha = 0.72f))
                    }
                } else {
                    AttachButton(callbacks)
                }
                Spacer(Modifier.width(2.dp))
                ReasoningChip(state.reasoning, enabled = !state.running, callbacks)
                Spacer(Modifier.weight(1f))
                val ctx = state.context
                if (ctx?.fraction != null && state.items.isNotEmpty()) ContextRing(ctx, callbacks.onStats)
                micSlot()
                Spacer(Modifier.width(2.dp))
                val hasContent = input.text.isNotBlank() || attachments.isNotEmpty()
                AnimatedVisibility(
                    visible = state.running && hasContent,
                    enter = fadeIn(tween(140)) + scaleIn(tween(160), initialScale = 0.78f) + expandHorizontally(),
                    exit = fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.78f) + shrinkHorizontally(),
                ) {
                    Box(Modifier.padding(end = 4.dp)) { ExtraStop(callbacks.onStop) }
                }
                SendButton(running = state.running, canSend = hasContent, onSend = callbacks.onSend, onStop = callbacks.onStop)
            }
        }
    }
}

@Composable
private fun placeholder(state: ChatUiState): String {
    val name = state.character?.displayName?.takeIf { state.characterChat }
    return when {
        name != null && state.running -> state.replyingLabel ?: stringResource(R.string.composer_hint_running)
        name != null -> stringResource(R.string.composer_hint_character, name)
        state.running -> stringResource(R.string.composer_hint_running)
        else -> stringResource(R.string.composer_hint)
    }
}

@Composable
private fun QueuePanel(queued: List<QueuedMessage>, running: Boolean, callbacks: ComposerCallbacks) {
    val colors = MaterialTheme.colorScheme
    val ink = homeInk()
    val shape = RoundedCornerShape(16.dp)
    Surface(color = ink.solidInk(0.08f, 0.04f), shape = shape, modifier = Modifier.fillMaxWidth().border(0.5.dp, ink.cardBorder, shape)) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(colors.primary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.queue_label, queued.size), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
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
                        else -> Surface(onClick = { callbacks.onSendNow(q.id) }, shape = RoundedCornerShape(50), color = ink.accent(0.16f, 0.10f)) {
                            Text(stringResource(R.string.queue_send_now), style = MaterialTheme.typography.labelMedium, color = colors.primary, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
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
        }, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Filled.Add, stringResource(R.string.composer_attach), Modifier.size(23.dp), tint = colors.onSurface.copy(alpha = 0.72f))
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
            MenuAction(stringResource(R.string.attach_path), ChatGlyphs.Edit) {
                open = false
                callbacks.onEnterPath()
            }
        }
    }
}

/**
 * The "Deep Think" chip: accent-tinted while reasoning is on, plain when off. A model with only on/off toggles on
 * tap; a model with effort levels opens the Off / Default / Low … Max picker.
 */
@Composable
private fun ReasoningChip(ui: ReasoningUi, enabled: Boolean, callbacks: ComposerCallbacks) {
    val support = ui.support
    if (support.mode == ReasoningMode.Unsupported) return
    val colors = MaterialTheme.colorScheme
    val ink = homeInk()
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val on = support.mode == ReasoningMode.Always || ui.enabled
    val fixed = support.mode == ReasoningMode.Always && support.levels.isEmpty()
    val toggleOnly = support.mode == ReasoningMode.Optional && support.levels.isEmpty()
    val fill by animateColorAsState(if (on) ink.accent(0.18f, 0.10f) else ink.ink(0.06f, 0.038f), tween(160), label = "thinkFill")
    val border by animateColorAsState(if (on) ink.accent(0.30f, 0.22f) else ink.ink(0.10f, 0.065f), tween(160), label = "thinkBorder")
    val content by animateColorAsState(if (on) colors.primary else ink.ink(0.58f, 0.54f), tween(160), label = "thinkText")
    val label = when {
        !on -> stringResource(R.string.reasoning_think)
        ui.effort != null -> effortLabel(ui.effort)
        else -> stringResource(R.string.reasoning_deep)
    }
    val shape = RoundedCornerShape(50)
    val description = stringResource(R.string.reasoning_effort)
    Box {
        Row(
            Modifier
                .heightIn(min = 30.dp)
                .clip(shape)
                .background(fill)
                .border(0.5.dp, border, shape)
                .clickable(enabled = enabled && !fixed) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (toggleOnly) callbacks.onReasoningToggle(!ui.enabled) else menu = true
                }
                .semantics { contentDescription = description }
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(HomeGlyphs.Orbit, null, Modifier.size(14.5.dp), tint = content)
            Spacer(Modifier.width(5.dp))
            Text(label, fontSize = 12.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, color = content, maxLines = 1)
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

/** Context usage as a 16dp ring: neutral, amber from 75%, red from 92%. Tapping opens the session stats. */
@Composable
private fun ContextRing(ctx: ContextInfo, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val ink = homeInk()
    val fraction = ctx.fraction ?: 0f
    val arc = when {
        fraction >= 0.92f -> colors.error
        fraction >= 0.75f -> MaterialTheme.extras.warning
        else -> ink.ink(0.48f, 0.42f)
    }
    val track = ink.ink(0.12f, 0.08f)
    val description = stringResource(R.string.context_ring_cd, (fraction * 100).toInt())
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(16.dp)) {
            val stroke = 1.8.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(arc, -90f, 360f * fraction, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}

/** While a task runs and something is typed: stop sits left of send (which then queues). */
@Composable
private fun ExtraStop(onStop: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val description = stringResource(R.string.composer_stop_run)
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(colors.surfaceContainerHigh)
            .clickable(onClick = onStop)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(10.dp).background(colors.onSurface, RoundedCornerShape(2.dp)))
    }
}

/**
 * A 32dp round send button in a 36dp target: faint when empty, accent with something to send, and an inverted
 * stop disc while a task runs with nothing typed.
 */
@Composable
private fun SendButton(running: Boolean, canSend: Boolean, onSend: () -> Unit, onStop: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val ink = homeInk()
    val haptics = LocalHapticFeedback.current
    val stopMode = running && !canSend
    val circle by animateColorAsState(
        when {
            stopMode -> colors.onSurface
            canSend -> colors.primary
            else -> ink.ink(0.08f, 0.06f)
        },
        tween(160),
        label = "sendBg",
    )
    val glyph by animateColorAsState(
        when {
            stopMode -> colors.surface
            canSend -> colors.onPrimary
            else -> ink.ink(0.28f, 0.24f)
        },
        tween(160),
        label = "sendFg",
    )
    val description = stringResource(if (stopMode) R.string.action_stop else if (running) R.string.composer_queue else R.string.action_send)
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(enabled = canSend || stopMode) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                if (stopMode) onStop() else onSend()
            }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(32.dp).background(circle, CircleShape), contentAlignment = Alignment.Center) {
            AnimatedContent(
                targetState = stopMode,
                transitionSpec = {
                    (fadeIn(tween(130)) + scaleIn(tween(160), initialScale = 0.72f)) togetherWith
                        (fadeOut(tween(90)) + scaleOut(tween(110), targetScale = 0.72f))
                },
                label = "sendGlyph",
            ) { stop ->
                if (stop) {
                    Box(Modifier.size(12.dp).background(glyph, RoundedCornerShape(2.5.dp)))
                } else {
                    Icon(ChatGlyphs.ArrowUp, null, Modifier.size(17.dp), tint = glyph)
                }
            }
        }
    }
}
