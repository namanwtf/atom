package atom.overlay.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import atom.overlay.R
import atom.ui.feature.HostApproval
import atom.ui.feature.HostStep
import atom.ui.feature.StepState
import atom.ui.theme.AtomIcons
import atom.ui.theme.extras

/** Shared look of every overlay surface: graphite glass over whatever is behind, with a white hairline. */
internal object Glass {
    val hairline = Color.White.copy(alpha = 0.09f)
    fun fill(blurred: Boolean) = Color(0xFF0A0A0C).copy(alpha = if (blurred) 0.74f else 0.95f)
    val raised = Color.White.copy(alpha = 0.07f)
}

internal fun Modifier.glass(shape: RoundedCornerShape, blurred: Boolean): Modifier =
    clip(shape).background(Glass.fill(blurred)).border(1.dp, Glass.hairline, shape)

@Composable
internal fun RoundIcon(
    icon: ImageVector,
    description: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    filled: Boolean = false,
    enabled: Boolean = true,
) {
    val c = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = when {
            !enabled -> Glass.raised
            filled -> c.primary
            else -> Glass.raised
        },
        contentColor = when {
            !enabled -> c.onSurfaceVariant
            filled -> c.onPrimary
            else -> c.onSurface
        },
        modifier = modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, description, Modifier.size(size * 0.5f)) }
    }
}

// ------------------------------------------------------------------ Markdown

@Composable
internal fun MarkdownText(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    val blocks = remember(text) { Markdown.blocks(text) }
    val body = MaterialTheme.typography.bodyLarge.copy(color = color, fontSize = 15.sp, lineHeight = 22.sp)
    val codeBg = Color.White.copy(alpha = 0.08f)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    annotated(block.text, codeBg),
                    style = (if (block.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall)
                        .copy(color = color, fontWeight = FontWeight.SemiBold),
                )
                is MdBlock.Paragraph -> Text(annotated(block.text, codeBg), style = body)
                is MdBlock.ListItem -> Row(Modifier.padding(start = (block.indent * 14).dp)) {
                    Text(block.marker, style = body.copy(color = color.copy(alpha = 0.6f)), modifier = Modifier.widthIn(min = 18.dp))
                    Text(annotated(block.text, codeBg), style = body)
                }
                is MdBlock.Quote -> Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                    Box(Modifier.width(2.dp).fillMaxHeight().background(color.copy(alpha = 0.3f)))
                    Spacer(Modifier.width(10.dp))
                    Text(annotated(block.text, codeBg), style = body.copy(color = color.copy(alpha = 0.72f)))
                }
                is MdBlock.Code -> Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(codeBg)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(block.code, style = MaterialTheme.extras.mono.copy(color = color), softWrap = false)
                }
                MdBlock.Rule -> HorizontalDivider(color = Glass.hairline)
            }
        }
    }
}

private fun annotated(text: String, codeBg: Color): AnnotatedString = buildAnnotatedString {
    Markdown.inline(text).forEach { span ->
        val style = SpanStyle(
            fontWeight = if (span.bold) FontWeight.SemiBold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null,
            fontFamily = if (span.code) FontFamily.Monospace else null,
            background = if (span.code) codeBg else Color.Unspecified,
            textDecoration = if (span.strike) TextDecoration.LineThrough else null,
        )
        val link = span.link
        if (link != null) {
            withLink(LinkAnnotation.Url(link, TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline)))) {
                withStyle(style) { append(span.text) }
            }
        } else {
            withStyle(style) { append(span.text) }
        }
    }
}

// ------------------------------------------------------------------ Steps

/** Thinking and tool steps folded into one row; tap to see each step. */
@Composable
internal fun StepsFold(steps: List<HostStep>, working: Boolean, modifier: Modifier = Modifier) {
    if (steps.isEmpty() && !working) return
    var open by rememberSaveable { mutableStateOf(false) }
    val c = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .clickable(enabled = steps.isNotEmpty()) { open = !open }
                .padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val current = steps.lastOrNull { it.state == StepState.Running }
            val label = when {
                current != null -> current.summary.ifBlank { current.tool }
                working -> stringResource(R.string.overlay_thinking)
                else -> pluralSteps(steps.size)
            }
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = c.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (steps.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    null,
                    Modifier.size(18.dp),
                    tint = c.onSurfaceVariant,
                )
            }
        }
        AnimatedVisibility(open, enter = fadeIn(tween(180)) + expandVertically(tween(200)), exit = fadeOut(tween(150)) + shrinkVertically(tween(180))) {
            Column(Modifier.padding(top = 4.dp, start = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                steps.forEach { StepRow(it) }
            }
        }
    }
}

@Composable
private fun pluralSteps(n: Int): String =
    androidx.compose.ui.platform.LocalContext.current.resources.getQuantityString(R.plurals.overlay_steps, n, n)

@Composable
private fun StepRow(step: HostStep) {
    val c = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        val (glyph, tint) = when (step.state) {
            StepState.Completed -> Icons.Filled.Check to c.onSurfaceVariant
            StepState.Failed -> Icons.Filled.Close to c.error
            StepState.Running -> null to c.onSurface
            StepState.Unknown -> null to c.onSurfaceVariant
        }
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            if (glyph != null) Icon(glyph, null, Modifier.size(14.dp), tint = tint) else Box(Modifier.size(5.dp).background(tint, CircleShape))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            step.summary.ifBlank { step.tool },
            style = MaterialTheme.typography.bodyMedium,
            color = if (step.state == StepState.Running) c.onSurface else c.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ------------------------------------------------------------------ Messages

@Composable
internal fun UserBubble(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp))
                .background(Color.White.copy(alpha = 0.12f))
                .padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

@Composable
internal fun ApprovalCard(approval: HostApproval, onAnswer: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Glass.raised)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.overlay_approval_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(approval.action, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(stringResource(R.string.overlay_deny), onClick = { onAnswer(false) }, primary = false)
            PillButton(stringResource(R.string.overlay_allow), onClick = { onAnswer(true) }, primary = true)
        }
    }
}

@Composable
internal fun PillButton(text: String, onClick: () -> Unit, primary: Boolean, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val c = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (primary) c.primary else Glass.raised,
        contentColor = if (primary) c.onPrimary else c.onSurface,
        modifier = modifier.heightIn(min = 38.dp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

// ------------------------------------------------------------------ Composer

/**
 * Pill text field with an inline mic, plus a round white send button. With an empty field during a run the button
 * stops the run; with text it steers.
 */
@Composable
internal fun PillComposer(
    text: String,
    onText: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    running: Boolean,
    listening: Boolean,
    level: Float,
    micAvailable: Boolean,
    onMic: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = stringResource(R.string.overlay_composer_hint),
    focusRequester: FocusRequester? = null,
    enabled: Boolean = true,
) {
    val c = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Glass.raised)
                .border(1.dp, Glass.hairline, RoundedCornerShape(24.dp))
                .padding(start = 18.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).padding(vertical = 13.dp)) {
                if (text.isEmpty()) {
                    Text(
                        if (listening) stringResource(R.string.overlay_listening) else placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = c.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = onText,
                    enabled = enabled,
                    textStyle = TextStyle(color = c.onSurface, fontSize = 16.sp, lineHeight = 22.sp),
                    cursorBrush = SolidColor(c.onSurface),
                    maxLines = 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
                )
            }
            if (micAvailable) {
                MicButton(listening, level, onMic)
            } else {
                Spacer(Modifier.width(12.dp))
            }
        }
        val canSend = text.isNotBlank() && enabled
        val stop = running && !canSend
        RoundIcon(
            icon = if (stop) AtomIcons.Stop else OverlayIcons.ArrowUp,
            description = stringResource(if (stop) R.string.overlay_stop else R.string.overlay_send),
            onClick = { if (stop) onStop() else onSend() },
            size = 48.dp,
            filled = canSend || stop,
            enabled = canSend || stop,
        )
    }
}

@Composable
internal fun MicButton(listening: Boolean, level: Float, onClick: () -> Unit, size: Dp = 40.dp) {
    val c = MaterialTheme.colorScheme
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {
        if (listening) {
            val ring = 1f + level.coerceIn(0f, 1f) * 0.35f
            Box(
                Modifier
                    .size(size * ring)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.14f)),
            )
        }
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = if (listening) c.primary else Color.Transparent,
            contentColor = if (listening) c.onPrimary else c.onSurfaceVariant,
            modifier = Modifier.size(size - 6.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(AtomIcons.Mic, stringResource(R.string.overlay_mic), Modifier.size(20.dp))
            }
        }
    }
}
