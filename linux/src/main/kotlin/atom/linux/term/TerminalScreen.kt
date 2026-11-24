package atom.linux.term

/**
 * Cell styles are packed into one Long so a row is two flat arrays.
 *
 * Layout: bits 0-24 foreground colour, 25-49 background colour, 50-57 attribute flags.
 * A colour is 0 (terminal default), 1..256 (palette index + 1), or [RGB_FLAG] | 0xRRGGBB.
 */
object CellStyle {
    const val BOLD = 1
    const val DIM = 2
    const val ITALIC = 4
    const val UNDERLINE = 8
    const val INVERSE = 16
    const val STRIKE = 32
    const val HIDDEN = 64
    const val BLINK = 128

    const val DEFAULT_COLOR = 0
    const val RGB_FLAG = 0x1000000

    const val DEFAULT: Long = 0L

    private const val COLOR_MASK = 0x1FFFFFFL

    fun pack(fg: Int, bg: Int, attrs: Int): Long =
        (fg.toLong() and COLOR_MASK) or ((bg.toLong() and COLOR_MASK) shl 25) or ((attrs.toLong() and 0xFF) shl 50)

    fun fg(style: Long): Int = (style and COLOR_MASK).toInt()
    fun bg(style: Long): Int = ((style ushr 25) and COLOR_MASK).toInt()
    fun attrs(style: Long): Int = ((style ushr 50) and 0xFF).toInt()

    fun indexed(index: Int): Int = index + 1
    fun rgb(r: Int, g: Int, b: Int): Int = RGB_FLAG or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    fun isIndexed(color: Int) = color in 1..256
    fun paletteIndex(color: Int) = color - 1
    fun isRgb(color: Int) = color and RGB_FLAG != 0
    fun rgbValue(color: Int) = color and 0xFFFFFF

    /** xterm's 256-colour palette as 0xRRGGBB; 0-15 use a calm, high-contrast scheme for true black. */
    val PALETTE: IntArray = IntArray(256).also { p ->
        val base = intArrayOf(
            0x000000, 0xE5534B, 0x57AB5A, 0xC69026, 0x539BF5, 0xB083F0, 0x39C5CF, 0xD0D7DE,
            0x6E7681, 0xFF7B72, 0x7EE787, 0xF2CC60, 0x79C0FF, 0xD2A8FF, 0x56D4DD, 0xFFFFFF,
        )
        base.copyInto(p)
        val steps = intArrayOf(0, 95, 135, 175, 215, 255)
        for (i in 0 until 216) {
            p[16 + i] = (steps[i / 36] shl 16) or (steps[(i / 6) % 6] shl 8) or steps[i % 6]
        }
        for (i in 0 until 24) {
            val v = 8 + i * 10
            p[232 + i] = (v shl 16) or (v shl 8) or v
        }
    }
}

/**
 * One immutable row of the terminal. Rows that did not change between snapshots are the same instance,
 * so a UI can skip them by identity.
 */
class TerminalRow internal constructor(
    /** Code point per column; 0 is blank, [WIDE_TAIL] marks the second half of a double-width character. */
    private val codePoints: IntArray,
    private val styles: LongArray,
    /** True when the text continues on the next row because it wrapped (rather than ended with a newline). */
    val wrapped: Boolean,
) {
    val columns: Int get() = codePoints.size

    fun codePointAt(column: Int): Int = codePoints[column]
    fun styleAt(column: Int): Long = styles[column]

    /** The row as text: blanks become spaces, double-width characters appear once. */
    val text: String by lazy(LazyThreadSafetyMode.NONE) {
        val sb = StringBuilder(codePoints.size)
        for (cp in codePoints) {
            when {
                cp == WIDE_TAIL -> Unit
                cp <= 0 -> sb.append(' ')
                else -> sb.appendCodePoint(cp)
            }
        }
        sb.toString()
    }

    /** [text] index where each column starts; size is columns + 1. A wide tail maps to its head's end. */
    val textOffsets: IntArray by lazy(LazyThreadSafetyMode.NONE) {
        val out = IntArray(codePoints.size + 1)
        var i = 0
        for (c in codePoints.indices) {
            out[c] = i
            val cp = codePoints[c]
            i += when {
                cp == WIDE_TAIL -> 0
                cp <= 0 -> 1
                else -> Character.charCount(cp)
            }
        }
        out[codePoints.size] = i
        out
    }

    /** Calls [block] for each maximal run of equal style, in [text] offsets. */
    inline fun forEachStyleRun(block: (start: Int, end: Int, style: Long) -> Unit) {
        val offsets = textOffsets
        var runStart = 0
        var c = 0
        while (c < columns) {
            val style = styleAt(c)
            var e = c + 1
            while (e < columns && styleAt(e) == style) e++
            block(offsets[runStart], offsets[e], style)
            runStart = e
            c = e
        }
    }

    /** Trailing-space-trimmed text, for copying and tests. */
    fun trimmedText(): String = text.trimEnd()

    override fun toString(): String = trimmedText()

    companion object {
        const val WIDE_TAIL = -1
        fun blank(columns: Int) = TerminalRow(IntArray(columns), LongArray(columns), false)
    }
}

/** An immutable snapshot of the terminal, published at most ~60 times a second. */
class TerminalScreen(
    val columns: Int,
    val rows: Int,
    /** The visible rows, top to bottom; size == [rows]. */
    val lines: List<TerminalRow>,
    /** Lines scrolled off the top of the main screen, oldest first. Empty while the alternate screen is shown. */
    val scrollback: List<TerminalRow>,
    val cursorRow: Int,
    val cursorColumn: Int,
    val cursorVisible: Boolean,
    val title: String,
    val altScreen: Boolean,
    val bracketedPaste: Boolean,
    val applicationCursorKeys: Boolean,
    val reverseVideo: Boolean,
) {
    /** The visible screen as trimmed text lines (handy in tests and for copy). */
    fun text(): String = lines.joinToString("\n") { it.trimmedText() }.trimEnd()

    companion object {
        val EMPTY = TerminalScreen(
            columns = 0, rows = 0, lines = emptyList(), scrollback = emptyList(),
            cursorRow = 0, cursorColumn = 0, cursorVisible = true, title = "", altScreen = false,
            bracketedPaste = false, applicationCursorKeys = false, reverseVideo = false,
        )
    }
}
