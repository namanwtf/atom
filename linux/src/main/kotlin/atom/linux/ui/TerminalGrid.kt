package atom.linux.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import atom.linux.term.CellStyle
import atom.linux.term.TerminalRow
import atom.linux.term.TerminalScreen
import kotlin.math.floor

/** True black, light grey text: the OLED terminal look. */
internal object TerminalColors {
    val background = Color(0xFF000000)
    val foreground = Color(0xFFE3E3E3)
    val cursor = Color(0xFFE3E3E3)
    val cursorText = Color(0xFF000000)

    private val palette = Array(256) { Color(0xFF000000 or CellStyle.PALETTE[it].toLong()) }

    fun resolve(color: Int, default: Color): Color = when {
        color == CellStyle.DEFAULT_COLOR -> default
        CellStyle.isRgb(color) -> Color(0xFF000000 or CellStyle.rgbValue(color).toLong())
        CellStyle.isIndexed(color) -> palette[CellStyle.paletteIndex(color)]
        else -> default
    }
}

/**
 * JetBrains Mono when its files are bundled in assets/fonts (OFL-1.1), otherwise the system monospace font.
 * Loaded once; failure is silent.
 */
internal object TerminalFont {
    @Volatile private var family: FontFamily? = null

    fun get(context: android.content.Context): FontFamily = family ?: synchronized(this) {
        family ?: run {
            val regular = runCatching { android.graphics.Typeface.createFromAsset(context.assets, "fonts/JetBrainsMono-Regular.ttf") }.getOrNull()
            if (regular != null) androidx.compose.ui.text.font.FontFamily(regular) else FontFamily.Monospace
        }.also { family = it }
    }
}

internal fun terminalTextStyle(fontSizeSp: Float, family: FontFamily = FontFamily.Monospace) = TextStyle(
    fontFamily = family,
    fontSize = fontSizeSp.sp,
    lineHeight = (fontSizeSp * LINE_FACTOR).sp,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    color = TerminalColors.foreground,
)

private const val LINE_FACTOR = 1.22f

/**
 * Draws the terminal grid. Each row is one Text with an AnnotatedString built only when that row instance
 * changes (the emulator reuses unchanged rows), so a byte of output recomposes one row, not the grid.
 * Rows sit in a LazyColumn (bottom-anchored), which also gives the main screen its scrollback.
 */
@Composable
internal fun TerminalGrid(
    screen: TerminalScreen,
    fontSizeSp: Float,
    onGridSize: (cols: Int, rows: Int) -> Unit,
    onTap: () -> Unit,
    onZoom: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val family = remember { TerminalFont.get(context) }
    val style = remember(fontSizeSp, family) { terminalTextStyle(fontSizeSp, family) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val sample = remember(style) { measurer.measure(AnnotatedString("M".repeat(20)), style) }
    val charWidthPx = sample.size.width / 20f
    val lineHeightPx = with(density) { (fontSizeSp * LINE_FACTOR).sp.toPx() }
    val rowHeight: Dp = with(density) { lineHeightPx.toDp() }
    val currentOnGridSize by rememberUpdatedState(onGridSize)
    val currentOnZoom by rememberUpdatedState(onZoom)
    val horizontalPadding = 6.dp
    val padPx = with(density) { horizontalPadding.toPx() * 2 }

    val listState = rememberLazyListState()
    val scrollback = screen.scrollback
    val lines = screen.lines
    val total = scrollback.size + lines.size

    // New output snaps back to the prompt unless the user is reading the scrollback.
    LaunchedEffect(screen.cursorRow, screen.cursorColumn, total) {
        if (listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
    }

    Box(
        modifier
            .background(TerminalColors.background)
            .onSizeChanged { size ->
                if (charWidthPx > 0f && lineHeightPx > 0f) {
                    val cols = floor((size.width - padPx) / charWidthPx).toInt().coerceAtLeast(10)
                    val rows = floor(size.height / lineHeightPx).toInt().coerceAtLeast(3)
                    currentOnGridSize(cols, rows)
                }
            }
            .pointerInput(Unit) {
                // Pinch to zoom: only two-finger gestures are taken, so one-finger scrolling still works.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.none { it.pressed }) break
                        if (event.changes.count { it.pressed } >= 2) {
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) currentOnZoom(zoom)
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) },
    ) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize().padding(horizontal = horizontalPadding),
        ) {
            items(count = total, contentType = { 0 }) { index ->
                // index 0 is the bottom row of the screen.
                val position = total - 1 - index
                val onScreen = position - scrollback.size
                val row = if (onScreen >= 0) lines[onScreen] else scrollback[position]
                val cursorColumn = if (onScreen == screen.cursorRow && screen.cursorVisible) screen.cursorColumn else -1
                TerminalLine(row, cursorColumn, screen.reverseVideo, style, rowHeight)
            }
        }
    }
}

@Composable
internal fun TerminalLine(row: TerminalRow, cursorColumn: Int, reverseVideo: Boolean, style: TextStyle, height: Dp) {
    val text = remember(row, cursorColumn, reverseVideo) { annotate(row, cursorColumn, reverseVideo) }
    Text(
        text = text,
        style = style,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.fillMaxWidth().height(height),
    )
}

internal fun annotate(row: TerminalRow, cursorColumn: Int, reverseVideo: Boolean): AnnotatedString {
    val text = row.text
    val b = AnnotatedString.Builder(text)
    row.forEachStyleRun { start, end, style ->
        if (start < end && (style != CellStyle.DEFAULT || reverseVideo)) b.addStyle(span(style, reverseVideo), start, end)
    }
    if (cursorColumn in 0 until row.columns) {
        val offsets = row.textOffsets
        var c = cursorColumn
        if (offsets[c + 1] <= offsets[c] && c > 0) c-- // on the tail of a wide character
        val s = offsets[c]
        val e = maxOf(offsets[c + 1], s + 1).coerceAtMost(text.length)
        if (s < e) b.addStyle(SpanStyle(color = TerminalColors.cursorText, background = TerminalColors.cursor), s, e)
    }
    return b.toAnnotatedString()
}

private fun span(style: Long, reverseVideo: Boolean): SpanStyle {
    val attrs = CellStyle.attrs(style)
    var fg = TerminalColors.resolve(CellStyle.fg(style), TerminalColors.foreground)
    var bg = TerminalColors.resolve(CellStyle.bg(style), Color.Transparent)
    val inverse = (attrs and CellStyle.INVERSE != 0) != reverseVideo
    if (inverse) {
        val newFg = if (bg == Color.Transparent) TerminalColors.background else bg
        bg = fg
        fg = newFg
    }
    if (attrs and CellStyle.DIM != 0) fg = fg.copy(alpha = 0.6f)
    if (attrs and CellStyle.HIDDEN != 0) fg = Color.Transparent
    val decoration = when {
        attrs and CellStyle.UNDERLINE != 0 && attrs and CellStyle.STRIKE != 0 ->
            TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
        attrs and CellStyle.UNDERLINE != 0 -> TextDecoration.Underline
        attrs and CellStyle.STRIKE != 0 -> TextDecoration.LineThrough
        else -> null
    }
    return SpanStyle(
        color = fg,
        background = bg,
        fontWeight = if (attrs and CellStyle.BOLD != 0) FontWeight.Bold else null,
        fontStyle = if (attrs and CellStyle.ITALIC != 0) FontStyle.Italic else null,
        textDecoration = decoration,
    )
}
