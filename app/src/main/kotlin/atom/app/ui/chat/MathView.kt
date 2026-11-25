package atom.app.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import atom.core.markdown.AccentKind
import atom.core.markdown.MathNode
import atom.core.markdown.MathParser
import atom.core.markdown.MathStyle
import atom.core.markdown.MatrixAlign
import atom.core.markdown.SymKind
import kotlin.math.max

/** A laid-out formula: [draw] paints it with its baseline at (x, baseline). */
internal class MathBox(
    val width: Float,
    val ascent: Float,
    val descent: Float,
    val draw: DrawScope.(x: Float, baseline: Float) -> Unit,
) {
    val height: Float get() = ascent + descent
}

/**
 * A small TeX layout engine on Compose text and paths: fractions, roots, scripts, big operators with limits,
 * accents, stretchy delimiters, matrices and environments. It is native (no WebView) and degrades unknown input
 * to readable text instead of failing.
 */
internal class MathLayout(
    private val measurer: TextMeasurer,
    private val color: Color,
    private val density: Float,
    private val fontScale: Float,
) {
    private data class Key(val text: String, val size: Float, val italic: Boolean, val bold: Boolean, val family: Int)
    private val cache = HashMap<Key, TextLayoutResult>()

    private data class Style(
        val size: Float,
        val display: Boolean,
        val level: Int = 0,
        val bold: Boolean = false,
        val upright: Boolean = false,
        val family: MathStyle? = null,
    ) {
        fun script() = copy(size = size * if (level == 0) 0.72f else 0.82f, display = false, level = level + 1)
        fun inner() = copy(display = false)
    }

    fun layout(tex: String, sizePx: Float, display: Boolean): MathBox = layout(MathParser.parse(tex), Style(sizePx, display))

    private fun layout(node: MathNode, s: Style): MathBox = when (node) {
        is MathNode.Row -> row(node.children, s)
        is MathNode.Sym -> sym(node, s, null)
        is MathNode.Frac -> frac(node, s)
        is MathNode.Sqrt -> sqrt(node, s)
        is MathNode.Scripts -> scripts(layout(node.base, s), node.sup, node.sub, s)
        is MathNode.BigOp -> bigOp(node, s)
        is MathNode.Accent -> accent(node, s)
        is MathNode.Fenced -> fenced(node.left, layout(node.body, s), node.right, s)
        is MathNode.Matrix -> matrix(node, s)
        is MathNode.Styled -> layout(
            node.body,
            when (node.style) {
                MathStyle.Bold -> s.copy(bold = true, upright = true)
                MathStyle.Roman, MathStyle.Text, MathStyle.SansSerif, MathStyle.Mono -> s.copy(upright = true, family = node.style)
                else -> s.copy(family = node.style)
            },
        )
        is MathNode.Space -> MathBox(node.em * s.size, 0f, 0f) { _, _ -> }
        is MathNode.Stack -> stack(layout(node.base, s), node.over?.let { layout(it, s.script()) }, node.under?.let { layout(it, s.script()) }, s)
    }

    // ---------------------------------------------------------------- Pieces

    private fun text(text: String, s: Style, italic: Boolean, kind: SymKind? = null): MathBox {
        val family = when (s.family) {
            MathStyle.Text, MathStyle.SansSerif -> 1
            MathStyle.Mono -> 2
            else -> if (kind == SymKind.Text) 1 else 0
        }
        val key = Key(text, s.size, italic, s.bold, family)
        val result = cache.getOrPut(key) {
            measurer.measure(
                AnnotatedString(text),
                TextStyle(
                    // Sizes are already pixels; undo the scaling sp would apply a second time.
                    fontSize = (s.size / (density * fontScale)).sp,
                    fontFamily = when (family) {
                        1 -> FontFamily.SansSerif
                        2 -> FontFamily.Monospace
                        else -> FontFamily.Serif
                    },
                    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
                    fontWeight = if (s.bold) FontWeight.Bold else FontWeight.Normal,
                    lineHeight = TextUnit.Unspecified,
                ),
                softWrap = false,
            )
        }
        val asc = result.firstBaseline
        val desc = result.size.height - asc
        // Font line boxes are generous; trim them so stacked pieces sit close like TeX.
        val trimA = asc * 0.12f
        val trimD = desc * 0.35f
        return MathBox(result.size.width.toFloat(), asc - trimA, desc - trimD) { x, baseline ->
            drawText(result, color = color, topLeft = Offset(x, baseline - asc))
        }
    }

    private fun sym(node: MathNode.Sym, s: Style, prev: SymKind?): MathBox {
        val mapped = mapFamily(node.text, s.family)
        val italic = node.kind == SymKind.Var && !s.upright && mapped == node.text && s.family == null
        val core = text(mapped, s, italic, node.kind)
        val pad = if (s.level > 0) 0f else when (node.kind) {
            SymKind.Bin -> if (prev == null || prev == SymKind.Bin || prev == SymKind.Rel || prev == SymKind.Open || prev == SymKind.Punct) 0f else 0.22f
            SymKind.Rel -> 0.28f
            else -> 0f
        } * s.size
        val after = if (node.kind == SymKind.Punct && s.level == 0) 0.17f * s.size else pad
        if (pad == 0f && after == 0f) return core
        return MathBox(core.width + pad + after, core.ascent, core.descent) { x, b -> core.draw(this, x + pad, b) }
    }

    private fun row(children: List<MathNode>, s: Style): MathBox {
        if (children.isEmpty()) return MathBox(0f, s.size * 0.5f, 0f) { _, _ -> }
        val boxes = ArrayList<MathBox>(children.size)
        var prev: SymKind? = null
        for (c in children) {
            boxes += if (c is MathNode.Sym) sym(c, s, prev) else layout(c, s)
            prev = (c as? MathNode.Sym)?.kind ?: SymKind.Ord
        }
        return hbox(boxes)
    }

    private fun hbox(boxes: List<MathBox>): MathBox {
        val width = boxes.sumOf { it.width.toDouble() }.toFloat()
        val asc = boxes.maxOf { it.ascent }
        val desc = boxes.maxOf { it.descent }
        return MathBox(width, asc, desc) { x, b ->
            var cx = x
            for (box in boxes) {
                box.draw(this, cx, b)
                cx += box.width
            }
        }
    }

    private fun axis(s: Style) = s.size * 0.25f
    private fun rule(s: Style) = max(1f, s.size * 0.055f)

    private fun frac(node: MathNode.Frac, s: Style): MathBox {
        val inner = if (s.display) s.inner() else s.script()
        val num = layout(node.num, inner)
        val den = layout(node.den, inner)
        val pad = s.size * 0.12f
        val gap = s.size * 0.14f
        val t = if (node.line) rule(s) else 0f
        val width = max(num.width, den.width) + pad * 2
        val a = axis(s)
        val asc = a + t / 2 + gap + num.height
        val desc = (den.height + gap + t / 2 - a).coerceAtLeast(den.descent)
        return MathBox(width, asc, desc) { x, b ->
            val lineY = b - a
            num.draw(this, x + (width - num.width) / 2, lineY - t / 2 - gap - num.descent)
            den.draw(this, x + (width - den.width) / 2, lineY + t / 2 + gap + den.ascent)
            if (node.line) drawLine(color, Offset(x + pad * 0.4f, lineY), Offset(x + width - pad * 0.4f, lineY), strokeWidth = t)
        }
    }

    private fun sqrt(node: MathNode.Sqrt, s: Style): MathBox {
        val body = layout(node.body, s)
        val index = node.index?.let { layout(it, s.script().script()) }
        val t = rule(s)
        val gap = s.size * 0.12f
        val signW = s.size * 0.55f
        val lead = index?.let { max(0f, it.width - signW * 0.45f) } ?: 0f
        val asc = body.ascent + gap + t
        val width = lead + signW + body.width + s.size * 0.08f
        return MathBox(width, asc, body.descent) { x, b ->
            val top = b - asc + t / 2
            val bottom = b + body.descent
            val sx = x + lead
            val path = Path().apply {
                moveTo(sx, b - body.ascent * 0.35f)
                lineTo(sx + signW * 0.25f, b - body.ascent * 0.45f)
                lineTo(sx + signW * 0.55f, bottom)
                lineTo(sx + signW, top)
                lineTo(sx + signW + body.width + s.size * 0.08f, top)
            }
            drawPath(path, color, style = Stroke(width = t, cap = StrokeCap.Round))
            body.draw(this, sx + signW, b)
            index?.draw(this, x, b - body.ascent * 0.45f - index.descent)
        }
    }

    private fun scripts(base: MathBox, sup: MathNode?, sub: MathNode?, s: Style): MathBox {
        val sp = sup?.let { layout(it, s.script()) }
        val sb = sub?.let { layout(it, s.script()) }
        val up = sp?.let { max(base.ascent - it.ascent * 0.5f, s.size * 0.36f) + 0f } ?: 0f
        val down = sb?.let { max(base.descent + it.ascent * 0.25f, s.size * 0.18f) } ?: 0f
        val kern = s.size * 0.04f
        val scriptW = max(sp?.width ?: 0f, sb?.width ?: 0f)
        val asc = max(base.ascent, sp?.let { up + it.ascent } ?: 0f)
        val desc = max(base.descent, sb?.let { down + it.descent } ?: 0f)
        return MathBox(base.width + kern + scriptW, asc, desc) { x, b ->
            base.draw(this, x, b)
            sp?.draw(this, x + base.width + kern, b - up)
            sb?.draw(this, x + base.width + kern, b + down)
        }
    }

    private fun stack(base: MathBox, over: MathBox?, under: MathBox?, s: Style): MathBox {
        val gap = s.size * 0.1f
        val width = maxOf(base.width, over?.width ?: 0f, under?.width ?: 0f)
        val asc = base.ascent + (over?.let { gap + it.height } ?: 0f)
        val desc = base.descent + (under?.let { gap + it.height } ?: 0f)
        return MathBox(width, asc, desc) { x, b ->
            base.draw(this, x + (width - base.width) / 2, b)
            over?.draw(this, x + (width - over.width) / 2, b - base.ascent - gap - over.descent)
            under?.draw(this, x + (width - under.width) / 2, b + base.descent + gap + under.ascent)
        }
    }

    private fun bigOp(node: MathNode.BigOp, s: Style): MathBox {
        val op = if (node.text) {
            text(node.symbol, s.copy(upright = true), italic = false)
        } else {
            text(node.symbol, s.copy(size = s.size * if (s.display) 1.45f else 1.1f), italic = false).let { box ->
                // Centre the enlarged glyph on the math axis.
                val shift = (box.ascent - box.descent) / 2 - axis(s)
                MathBox(box.width + s.size * 0.08f, box.ascent - shift, box.descent + shift) { x, b -> box.draw(this, x, b + shift) }
            }
        }
        if (node.sup == null && node.sub == null) return padRight(op, s.size * 0.1f)
        return if (node.limits && s.display) {
            padRight(stack(op, node.sup?.let { layout(it, s.script()) }, node.sub?.let { layout(it, s.script()) }, s), s.size * 0.1f)
        } else {
            padRight(scripts(op, node.sup, node.sub, s), s.size * 0.1f)
        }
    }

    private fun padRight(box: MathBox, pad: Float) = MathBox(box.width + pad, box.ascent, box.descent, box.draw)

    private fun accent(node: MathNode.Accent, s: Style): MathBox {
        val body = layout(node.body, s)
        val t = rule(s)
        val gap = s.size * 0.08f
        val mark = s.size * 0.2f
        if (node.accent == AccentKind.Underline) {
            return MathBox(body.width, body.ascent, body.descent + gap + t) { x, b ->
                body.draw(this, x, b)
                val y = b + body.descent + gap
                drawLine(color, Offset(x, y), Offset(x + body.width, y), strokeWidth = t)
            }
        }
        val asc = body.ascent + gap + mark
        return MathBox(body.width, asc, body.descent) { x, b ->
            body.draw(this, x, b)
            val y = b - body.ascent - gap - mark / 2
            val cx = x + body.width / 2
            val w = when (node.accent) {
                AccentKind.Overline, AccentKind.OverArrow -> body.width
                else -> max(s.size * 0.4f, body.width * 0.6f)
            }
            val l = cx - w / 2
            val r = cx + w / 2
            when (node.accent) {
                AccentKind.Hat -> drawPath(Path().apply { moveTo(l, y + mark / 2); lineTo(cx, y - mark / 2); lineTo(r, y + mark / 2) }, color, style = Stroke(t, cap = StrokeCap.Round))
                AccentKind.Bar, AccentKind.Overline -> drawLine(color, Offset(l, y), Offset(r, y), strokeWidth = t)
                AccentKind.Vec, AccentKind.OverArrow -> {
                    drawLine(color, Offset(l, y), Offset(r, y), strokeWidth = t)
                    drawPath(Path().apply { moveTo(r - mark * 0.7f, y - mark * 0.45f); lineTo(r, y); lineTo(r - mark * 0.7f, y + mark * 0.45f) }, color, style = Stroke(t, cap = StrokeCap.Round))
                }
                AccentKind.Dot -> drawCircle(color, t * 1.1f, Offset(cx, y))
                AccentKind.DDot -> {
                    drawCircle(color, t * 1.1f, Offset(cx - mark * 0.5f, y))
                    drawCircle(color, t * 1.1f, Offset(cx + mark * 0.5f, y))
                }
                AccentKind.Tilde -> drawPath(
                    Path().apply {
                        moveTo(l, y + mark * 0.2f)
                        cubicTo(l + w * 0.3f, y - mark * 0.6f, r - w * 0.3f, y + mark * 0.6f, r, y - mark * 0.2f)
                    },
                    color,
                    style = Stroke(t, cap = StrokeCap.Round),
                )
                AccentKind.Underline -> Unit
            }
        }
    }

    private fun fenced(left: String, body: MathBox, right: String, s: Style): MathBox {
        val a = axis(s)
        val half = max(body.ascent - a, body.descent + a) + s.size * 0.08f
        val l = delimiter(left, half, s)
        val r = delimiter(right, half, s)
        return hbox(listOf(l, body, r))
    }

    /** A delimiter spanning [half] above and below the axis; drawn as paths once it outgrows the font glyph. */
    private fun delimiter(d: String, half: Float, s: Style): MathBox {
        if (d.isEmpty()) return MathBox(s.size * 0.05f, 0f, 0f) { _, _ -> }
        val plain = text(d, s, italic = false)
        if (half * 2 <= plain.height * 1.15f || d !in STRETCHY) return plain
        val a = axis(s)
        val t = rule(s) * 1.1f
        val w = s.size * 0.42f
        return MathBox(w, a + half, half - a) { x, b ->
            val top = b - a - half
            val bottom = b - a + half
            val mid = b - a
            val stroke = Stroke(t, cap = StrokeCap.Round)
            val m = x + w * 0.5f
            when (d) {
                "(" -> drawPath(Path().apply { moveTo(x + w * 0.75f, top); quadraticTo(x + w * 0.1f, mid, x + w * 0.75f, bottom) }, color, style = stroke)
                ")" -> drawPath(Path().apply { moveTo(x + w * 0.25f, top); quadraticTo(x + w * 0.9f, mid, x + w * 0.25f, bottom) }, color, style = stroke)
                "[", "⌊", "⌈" -> drawPath(
                    Path().apply {
                        if (d != "⌊") { moveTo(x + w * 0.75f, top); lineTo(x + w * 0.35f, top) } else moveTo(x + w * 0.35f, top)
                        lineTo(x + w * 0.35f, bottom)
                        if (d != "⌈") lineTo(x + w * 0.75f, bottom)
                    },
                    color,
                    style = stroke,
                )
                "]", "⌋", "⌉" -> drawPath(
                    Path().apply {
                        if (d != "⌋") { moveTo(x + w * 0.25f, top); lineTo(x + w * 0.65f, top) } else moveTo(x + w * 0.65f, top)
                        lineTo(x + w * 0.65f, bottom)
                        if (d != "⌉") lineTo(x + w * 0.25f, bottom)
                    },
                    color,
                    style = stroke,
                )
                "{" -> drawPath(
                    Path().apply {
                        moveTo(x + w * 0.8f, top)
                        quadraticTo(x + w * 0.45f, top, x + w * 0.45f, top + half * 0.3f)
                        lineTo(x + w * 0.45f, mid - half * 0.2f)
                        quadraticTo(x + w * 0.45f, mid, x + w * 0.15f, mid)
                        quadraticTo(x + w * 0.45f, mid, x + w * 0.45f, mid + half * 0.2f)
                        lineTo(x + w * 0.45f, bottom - half * 0.3f)
                        quadraticTo(x + w * 0.45f, bottom, x + w * 0.8f, bottom)
                    },
                    color,
                    style = stroke,
                )
                "}" -> drawPath(
                    Path().apply {
                        moveTo(x + w * 0.2f, top)
                        quadraticTo(x + w * 0.55f, top, x + w * 0.55f, top + half * 0.3f)
                        lineTo(x + w * 0.55f, mid - half * 0.2f)
                        quadraticTo(x + w * 0.55f, mid, x + w * 0.85f, mid)
                        quadraticTo(x + w * 0.55f, mid, x + w * 0.55f, mid + half * 0.2f)
                        lineTo(x + w * 0.55f, bottom - half * 0.3f)
                        quadraticTo(x + w * 0.55f, bottom, x + w * 0.2f, bottom)
                    },
                    color,
                    style = stroke,
                )
                "⟨" -> drawPath(Path().apply { moveTo(x + w * 0.75f, top); lineTo(x + w * 0.25f, mid); lineTo(x + w * 0.75f, bottom) }, color, style = stroke)
                "⟩" -> drawPath(Path().apply { moveTo(x + w * 0.25f, top); lineTo(x + w * 0.75f, mid); lineTo(x + w * 0.25f, bottom) }, color, style = stroke)
                "|" -> drawLine(color, Offset(m, top), Offset(m, bottom), strokeWidth = t)
                "‖" -> {
                    drawLine(color, Offset(m - w * 0.15f, top), Offset(m - w * 0.15f, bottom), strokeWidth = t)
                    drawLine(color, Offset(m + w * 0.15f, top), Offset(m + w * 0.15f, bottom), strokeWidth = t)
                }
            }
        }
    }

    private fun matrix(node: MathNode.Matrix, s: Style): MathBox {
        val cellStyle = s.inner()
        val cells = node.rows.map { row -> row.map { layout(it, cellStyle) } }
        if (cells.isEmpty()) return fenced(node.left, MathBox(s.size * 0.5f, s.size * 0.5f, 0f) { _, _ -> }, node.right, s)
        val cols = cells.maxOf { it.size }
        val colW = FloatArray(cols) { c -> cells.maxOf { it.getOrNull(c)?.width ?: 0f } }
        val rowGap = s.size * 0.35f
        val colGap = when (node.align) {
            MatrixAlign.AlignPairs -> 0f
            else -> s.size * 0.9f
        }
        val heights = cells.map { r -> (r.maxOfOrNull { it.ascent } ?: 0f) to (r.maxOfOrNull { it.descent } ?: 0f) }
        val total = heights.sumOf { (it.first + it.second).toDouble() }.toFloat() + rowGap * (cells.size - 1)
        val width = colW.sum() + colGap * (cols - 1) + if (node.align == MatrixAlign.AlignPairs) s.size * 0.3f * (cols / 2) else 0f
        val a = axis(s)
        val grid = MathBox(width, total / 2 + a, total / 2 - a) { x, b ->
            var y = b - a - total / 2
            cells.forEachIndexed { ri, row ->
                val (asc, desc) = heights[ri]
                val baseline = y + asc
                var cx = x
                for (c in 0 until cols) {
                    val box = row.getOrNull(c)
                    val w = colW[c]
                    if (box != null) {
                        val dx = when (node.align) {
                            MatrixAlign.Left -> 0f
                            MatrixAlign.AlignPairs -> if (c % 2 == 0) w - box.width else 0f
                            MatrixAlign.Center -> (w - box.width) / 2
                        }
                        box.draw(this, cx + dx, baseline)
                    }
                    cx += w + colGap + if (node.align == MatrixAlign.AlignPairs && c % 2 == 1) s.size * 0.6f else 0f
                }
                y += asc + desc + rowGap
            }
        }
        return if (node.left.isEmpty() && node.right.isEmpty()) grid else fenced(node.left, grid, node.right, s)
    }

    private fun mapFamily(text: String, family: MathStyle?): String = when (family) {
        MathStyle.Blackboard -> text.map { BLACKBOARD[it] ?: it.toString() }.joinToString("")
        MathStyle.Calligraphic -> text.map { SCRIPT[it] ?: it.toString() }.joinToString("")
        MathStyle.Fraktur -> text.map { FRAKTUR[it] ?: it.toString() }.joinToString("")
        else -> text
    }

    private companion object {
        val STRETCHY = setOf("(", ")", "[", "]", "{", "}", "|", "‖", "⟨", "⟩", "⌊", "⌋", "⌈", "⌉")

        // Letterlike forms that ship in the system fonts; other letters keep their plain shape.
        val BLACKBOARD = mapOf('R' to "ℝ", 'N' to "ℕ", 'Z' to "ℤ", 'Q' to "ℚ", 'C' to "ℂ", 'H' to "ℍ", 'P' to "ℙ", '1' to "𝟙")
        val SCRIPT = mapOf(
            'B' to "ℬ", 'E' to "ℰ", 'F' to "ℱ", 'H' to "ℋ", 'I' to "ℐ", 'L' to "ℒ", 'M' to "ℳ", 'R' to "ℛ",
            'e' to "ℯ", 'g' to "ℊ", 'o' to "ℴ",
        )
        val FRAKTUR = mapOf('C' to "ℭ", 'H' to "ℌ", 'I' to "ℑ", 'R' to "ℜ", 'Z' to "ℨ")
    }
}

/** Remembers a layout engine for the current text colour and density. */
@Composable
internal fun rememberMathLayout(color: Color = MaterialTheme.colorScheme.onSurface): MathLayout {
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val d = LocalDensity.current
    return remember(measurer, color, d.density, d.fontScale) { MathLayout(measurer, color, d.density, d.fontScale) }
}

/** Display math: centred, scrolls sideways when wider than the screen. */
@Composable
internal fun MathBlock(tex: String, modifier: Modifier = Modifier) {
    val math = rememberMathLayout()
    val sizePx = with(LocalDensity.current) { 18.sp.toPx() }
    val box = remember(tex, math, sizePx) { math.layout(tex, sizePx, display = true) }
    val density = LocalDensity.current
    Box(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(with(density) { (box.width + 4f).toDp() }, with(density) { (box.height + 4f).toDp() })) {
            box.draw(this, 2f, 2f + box.ascent)
        }
    }
}
