package atom.app.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import atom.app.R
import atom.core.markdown.Align
import atom.core.markdown.CalloutKind
import atom.core.markdown.Markdown
import atom.core.markdown.MdBlock
import atom.core.markdown.MdInline
import atom.ui.theme.extras

/** Renders chat Markdown natively: one Compose tree, no WebView, inline and display TeX included. */
@Composable
internal fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = MaterialTheme.colorScheme.onBackground,
) {
    val blocks = remember(text) { Markdown.parse(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { Block(it, style, color) }
    }
}

@Composable
private fun Block(block: MdBlock, style: TextStyle, color: Color) {
    val colors = MaterialTheme.colorScheme
    when (block) {
        is MdBlock.Paragraph -> InlineText(block.inlines, style, color)
        is MdBlock.Heading -> {
            // Typography roles, so the global font-size setting and the user's font scale both apply.
            val t = MaterialTheme.typography
            val role = when (block.level) {
                1 -> t.titleLarge
                2 -> t.titleMedium
                else -> style
            }
            InlineText(
                block.inlines,
                style.copy(fontSize = role.fontSize, lineHeight = role.lineHeight, fontWeight = FontWeight.SemiBold),
                color,
                Modifier.padding(top = if (block.level <= 2) 6.dp else 2.dp),
            )
        }
        is MdBlock.Code -> CodeBlock(block.language, block.code)
        is MdBlock.Math -> MathBlock(block.tex)
        is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(colors.outline, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                block.blocks.forEach { Block(it, style, colors.onSurfaceVariant) }
            }
        }
        is MdBlock.Callout -> Callout(block, style, color)
        is MdBlock.ListBlock -> ListBlock(block, style, color)
        is MdBlock.Table -> Table(block, style, color)
        MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp), thickness = 1.dp, color = colors.outline)
    }
}

@Composable
private fun InlineText(inlines: List<MdInline>, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val math = rememberMathLayout(color)
    val density = LocalDensity.current
    val sizePx = with(density) { (style.fontSize.takeIf { it.isSp } ?: 16.sp).toPx() } * 1.05f
    val codeBg = colors.surfaceContainerHigh
    val linkStyle = TextLinkStyles(SpanStyle(color = colors.onSurface, textDecoration = TextDecoration.Underline))
    val (annotated, inline) = remember(inlines, color, sizePx, math, style, codeBg) {
        val content = HashMap<String, InlineTextContent>()
        var n = 0
        val text = buildAnnotatedString {
            fun add(nodes: List<MdInline>) {
                for (node in nodes) when (node) {
                    is MdInline.Text -> append(node.text)
                    is MdInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { add(node.children) }
                    is MdInline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { add(node.children) }
                    is MdInline.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { add(node.children) }
                    is MdInline.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = style.fontSize * 0.88f, background = codeBg)) {
                        append(" " + node.code + " ")
                    }
                    is MdInline.Link -> withLink(LinkAnnotation.Url(node.url, linkStyle)) { add(node.children) }
                    is MdInline.Math -> {
                        val id = "m${n++}"
                        val box = math.layout(node.tex, sizePx, display = false)
                        val w = with(density) { (box.width + 2f).toSp() }
                        val h = with(density) { (box.height + 2f).toSp() }
                        content[id] = InlineTextContent(Placeholder(w, h, PlaceholderVerticalAlign.TextCenter)) {
                            Canvas(Modifier.fillMaxWidth().fillMaxHeight()) { box.draw(this, 1f, 1f + box.ascent) }
                        }
                        // The alternate text is what selection copies: the TeX source.
                        appendInlineContent(id, "$" + node.tex + "$")
                    }
                    MdInline.LineBreak -> append('\n')
                }
            }
            add(inlines)
        }
        text to content
    }
    Text(annotated, modifier = modifier, style = style.copy(color = color), inlineContent = inline)
}

@Composable
private fun CodeBlock(language: String?, code: String) {
    val colors = MaterialTheme.colorScheme
    val copy = rememberCopier()
    var copied by remember(code) { mutableStateOf(false) }
    Surface(color = colors.surfaceContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    language?.lowercase() ?: stringResource(R.string.md_code),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    onClick = {
                        copy(code)
                        copied = true
                    },
                    color = Color.Transparent,
                    shape = RoundedCornerShape(50),
                ) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (copied) ChatGlyphs.Check else ChatGlyphs.Copy, null, Modifier.size(14.dp), tint = colors.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(if (copied) R.string.md_copied else R.string.md_copy),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                code,
                style = MaterialTheme.extras.mono,
                color = colors.onSurface,
                softWrap = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun Callout(block: MdBlock.Callout, style: TextStyle, color: Color) {
    val colors = MaterialTheme.colorScheme
    val extras = MaterialTheme.extras
    val (tint, label) = when (block.kind) {
        CalloutKind.Note -> colors.onSurfaceVariant to R.string.md_note
        CalloutKind.Tip -> extras.success to R.string.md_tip
        CalloutKind.Important -> colors.onSurface to R.string.md_important
        CalloutKind.Warning -> extras.warning to R.string.md_warning
        CalloutKind.Caution -> colors.error to R.string.md_caution
    }
    Surface(color = colors.surfaceContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(tint))
            Column(Modifier.padding(start = 12.dp, end = 14.dp, top = 10.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(ChatGlyphs.Callout, null, Modifier.size(15.dp), tint = tint)
                    Spacer(Modifier.width(8.dp))
                    Text(block.title ?: stringResource(label), style = MaterialTheme.typography.labelLarge, color = tint, fontWeight = FontWeight.SemiBold)
                }
                block.blocks.forEach { Block(it, style, color) }
            }
        }
    }
}

@Composable
private fun ListBlock(block: MdBlock.ListBlock, style: TextStyle, color: Color) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        block.items.forEachIndexed { i, item ->
            Row {
                Box(Modifier.widthIn(min = 22.dp).padding(end = 6.dp), contentAlignment = Alignment.TopEnd) {
                    when {
                        item.task -> Icon(
                            if (item.checked) ChatGlyphs.Check else ChatGlyphs.Unchecked,
                            null,
                            Modifier.padding(top = 3.dp).size(16.dp),
                            tint = if (item.checked) colors.onSurface else colors.onSurfaceVariant,
                        )
                        block.ordered -> Text("${block.start + i}.", style = style, color = colors.onSurfaceVariant)
                        else -> Text("•", style = style, color = colors.onSurfaceVariant)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item.blocks.forEach { b ->
                        val done = item.task && item.checked
                        Block(b, style.copy(textDecoration = if (done) TextDecoration.LineThrough else null), if (done) colors.onSurfaceVariant else color)
                    }
                }
            }
        }
    }
}

@Composable
private fun Table(block: MdBlock.Table, style: TextStyle, color: Color) {
    val colors = MaterialTheme.colorScheme
    val cellStyle = style.copy(fontSize = style.fontSize * 0.92f, lineHeight = style.lineHeight * 0.92f)
    val cols = block.header.size
    Surface(color = colors.surfaceContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            TableLayout(columns = cols, rows = block.rows.size + 1) {
                (listOf(block.header) + block.rows).forEachIndexed { r, row ->
                    for (c in 0 until cols) {
                        val cell = row.getOrElse(c) { emptyList() }
                        val align = when (block.align.getOrElse(c) { Align.Start }) {
                            Align.Start -> Alignment.CenterStart
                            Align.Center -> Alignment.Center
                            Align.End -> Alignment.CenterEnd
                        }
                        Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = align) {
                            InlineText(
                                cell,
                                if (r == 0) cellStyle.copy(fontWeight = FontWeight.SemiBold) else cellStyle,
                                color,
                                Modifier.widthIn(max = 260.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Grid layout: column widths fit the widest cell, row heights the tallest, children given row by row. */
@Composable
private fun TableLayout(columns: Int, rows: Int, content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints()) }
        val widths = IntArray(columns) { c -> (0 until rows).maxOf { r -> placeables.getOrNull(r * columns + c)?.width ?: 0 } }
        val heights = IntArray(rows) { r -> (0 until columns).maxOf { c -> placeables.getOrNull(r * columns + c)?.height ?: 0 } }
        val width = widths.sum().coerceAtLeast(constraints.minWidth)
        val height = heights.sum()
        layout(width, height) {
            var y = 0
            for (r in 0 until rows) {
                var x = 0
                for (c in 0 until columns) {
                    val p = placeables.getOrNull(r * columns + c) ?: continue
                    p.place(x, y + (heights[r] - p.height) / 2)
                    x += widths[c]
                }
                y += heights[r]
            }
        }
    }
}
