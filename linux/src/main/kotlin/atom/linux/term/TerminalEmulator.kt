package atom.linux.term

/**
 * A VT100/xterm-subset terminal emulator: pure Kotlin, no Android dependencies, not thread-safe
 * (the session serialises access). Feed program output to [process]; read [snapshot]; send
 * [takeResponses] (replies to queries such as cursor-position reports) back to the program.
 *
 * Supported: UTF-8 (split across reads), wrap with pending-wrap semantics, C0 controls, ESC 7/8/D/E/M/H/c,
 * DEC line-drawing charset, CSI cursor movement, ED/EL/ECH, ICH/DCH/IL/DL, SU/SD, DECSTBM, origin mode,
 * insert mode, SGR (16/256/truecolour, colon and semicolon forms), alternate screen (47/1047/1049),
 * 1048, DECTCEM (25), DECCKM (1), bracketed paste (2004), autowrap (7), reverse video (5), tab stops,
 * OSC 0/2 titles, DSR 5/6, DA1/DA2, window-size report (CSI 18 t), and a scrollback for the main screen.
 */
class TerminalEmulator(columns: Int, rows: Int, private val scrollbackLimit: Int = 2000) {

    var columns: Int = columns.coerceAtLeast(1)
        private set
    var rows: Int = rows.coerceAtLeast(1)
        private set

    private var main = Buffer(this.columns, this.rows)
    private var alt = Buffer(this.columns, this.rows)
    private var buf = main

    private val scrollback = ArrayDeque<TerminalRow>()
    private var scrollbackSnapshot: List<TerminalRow> = emptyList()
    private var scrollbackDirty = false

    // Cursor and rendition state (per active buffer: the buffer keeps its own saved cursor).
    private var cx = 0
    private var cy = 0
    private var wrapPending = false
    private var fg = 0
    private var bg = 0
    private var attrs = 0

    private var top = 0
    private var bottom = this.rows - 1

    private var autowrap = true
    private var originMode = false
    private var insertMode = false
    private var cursorVisible = true
    private var appCursorKeys = false
    private var bracketedPaste = false
    private var reverseVideo = false

    // Charsets: G0/G1 may be ASCII or DEC special graphics; SO/SI pick which is active.
    private var g0Graphics = false
    private var g1Graphics = false
    private var useG1 = false

    private var tabStops = BooleanArray(this.columns) { it % 8 == 0 }
    private var lastPrinted = ' '.code

    var title: String = ""
        private set

    private val responses = StringBuilder()

    // ---------------------------------------------------------------- parser state
    private val utf8 = Utf8Decoder()
    private var state = State.Ground
    private val params = IntArray(MAX_PARAMS)
    private val subParam = BooleanArray(MAX_PARAMS) // params[i] was introduced by ':' (a sub-parameter)
    private var paramCount = 0
    private var paramStarted = false
    private var privateMarker = 0
    private var intermediate = 0
    private val oscBuffer = StringBuilder()

    private enum class State { Ground, Escape, EscapeCharset, EscapeHash, Csi, Osc, OscEscape, IgnoreString, IgnoreStringEscape }

    // ================================================================ input

    fun process(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        val end = offset + length
        var i = offset
        while (i < end) {
            val b = bytes[i].toInt() and 0xFF
            i++
            // Fast path: printable ASCII in the ground state.
            if (state == State.Ground && b in 0x20..0x7E) {
                if (utf8Pending) flushBrokenUtf8()
                print(b)
                continue
            }
            if (b < 0x80 || state != State.Ground) {
                if (b >= 0x80 && state != State.Ground) {
                    // UTF-8 inside OSC titles is collected byte-wise below; elsewhere high bytes are ignored.
                    if (state == State.Osc) oscByte(b)
                    continue
                }
                if (utf8Pending) flushBrokenUtf8()
                byteInState(b)
                continue
            }
            utf8Pending = true
            val cp = utf8.feed(b)
            if (cp == Utf8Decoder.NONE) continue
            utf8Pending = false
            if (cp == Utf8Decoder.REPLACEMENT) {
                print(cp)
                val p = utf8.takePending()
                if (p != Utf8Decoder.NONE) print(p)
            } else {
                print(cp)
            }
        }
    }

    fun process(text: String) = process(text.toByteArray(Charsets.UTF_8))

    private var utf8Pending = false

    /** An ASCII/control byte interrupted a multi-byte sequence. */
    private fun flushBrokenUtf8() {
        utf8Pending = false
        utf8.reset()
        print(Utf8Decoder.REPLACEMENT)
    }

    /** Replies the program asked for (DSR, DA); the caller writes them to the pty. */
    fun takeResponses(): String? {
        if (responses.isEmpty()) return null
        return responses.toString().also { responses.setLength(0) }
    }

    private fun byteInState(b: Int) {
        when (state) {
            State.Ground -> control(b)
            State.Escape -> escape(b)
            State.EscapeCharset -> {
                val graphics = b == '0'.code
                if (intermediate == '('.code) g0Graphics = graphics else if (intermediate == ')'.code) g1Graphics = graphics
                state = State.Ground
            }
            State.EscapeHash -> {
                if (b == '8'.code) decaln()
                state = State.Ground
            }
            State.Csi -> csiByte(b)
            State.Osc -> oscByte(b)
            State.OscEscape -> {
                // ESC ends the string either way (ESC \\ is the proper ST).
                finishOsc()
                if (b == '\\'.code) state = State.Ground else escape(b)
            }
            State.IgnoreString -> if (b == 0x1B) state = State.IgnoreStringEscape else if (b == 0x07) state = State.Ground
            State.IgnoreStringEscape -> state = if (b == '\\'.code) State.Ground else State.IgnoreString
        }
    }

    private fun control(b: Int) {
        when (b) {
            0x07 -> Unit // BEL
            0x08 -> { // BS
                wrapPending = false
                if (cx > 0) cx--
            }
            0x09 -> tab(1)
            0x0A, 0x0B, 0x0C -> lineFeed()
            0x0D -> {
                cx = 0
                wrapPending = false
            }
            0x0E -> useG1 = true
            0x0F -> useG1 = false
            0x1B -> {
                state = State.Escape
                intermediate = 0
            }
            else -> Unit // other C0 controls (NUL, ENQ, ...) are ignored
        }
    }

    private fun escape(b: Int) {
        state = State.Ground
        when (b) {
            '['.code -> {
                state = State.Csi
                paramCount = 0
                paramStarted = false
                privateMarker = 0
                intermediate = 0
                params.fill(0)
                subParam.fill(false)
            }
            ']'.code -> {
                state = State.Osc
                oscBuffer.setLength(0)
            }
            'P'.code, 'X'.code, '^'.code, '_'.code -> state = State.IgnoreString // DCS, SOS, PM, APC
            '('.code, ')'.code, '*'.code, '+'.code -> {
                intermediate = b
                state = State.EscapeCharset
            }
            '#'.code -> state = State.EscapeHash
            '7'.code -> saveCursor()
            '8'.code -> restoreCursor()
            'D'.code -> index()
            'E'.code -> {
                cx = 0
                index()
            }
            'M'.code -> reverseIndex()
            'H'.code -> if (cx < columns) tabStops[cx] = true
            'c'.code -> reset()
            '='.code, '>'.code -> Unit // keypad modes: the soft keyboard has no keypad
            '\\'.code -> Unit // stray ST
            0x1B -> state = State.Escape
            else -> if (b < 0x20) control(b)
        }
    }

    // ================================================================ printing

    private fun print(codePoint: Int) {
        var cp = codePoint
        val graphics = if (useG1) g1Graphics else g0Graphics
        if (graphics && cp in 0x5F..0x7E) cp = DEC_GRAPHICS[cp - 0x5F].code
        val width = CharWidth.of(cp)
        if (width == 0) return // combining marks are dropped; the base character stays
        lastPrinted = cp

        if (wrapPending) {
            wrapPending = false
            if (autowrap) {
                buf.lines[cy].setWrapped(true)
                cx = 0
                index()
            }
        }
        if (width == 2 && cx == columns - 1) {
            if (!autowrap) return
            buf.lines[cy].let { line ->
                clearWide(line, cx)
                line.set(cx, 0, eraseStyle())
                line.setWrapped(true)
            }
            cx = 0
            index()
        }
        if (width == 2 && columns < 2) return

        val line = buf.lines[cy]
        if (insertMode) line.insert(cx, width, eraseStyle())
        val style = currentStyle()
        clearWide(line, cx)
        if (width == 2) clearWide(line, cx + 1)
        line.set(cx, cp, style)
        if (width == 2) line.set(cx + 1, TerminalRow.WIDE_TAIL, style)

        cx += width
        if (cx >= columns) {
            cx = columns - 1
            wrapPending = true
        }
    }

    /** Overwriting either half of a wide character blanks the other half. */
    private fun clearWide(line: Line, x: Int) {
        if (x < 0 || x >= columns) return
        val cp = line.cp[x]
        if (cp == TerminalRow.WIDE_TAIL && x > 0) line.set(x - 1, 0, line.st[x - 1])
        else if (cp > 0 && x + 1 < columns && line.cp[x + 1] == TerminalRow.WIDE_TAIL) line.set(x + 1, 0, line.st[x + 1])
    }

    private fun currentStyle(): Long = CellStyle.pack(fg, bg, attrs)

    /** Erased cells keep the current background (xterm's bce) but no attributes. */
    private fun eraseStyle(): Long = CellStyle.pack(0, bg, 0)

    // ================================================================ cursor movement and scrolling

    private fun lineFeed() {
        wrapPending = false
        index()
    }

    private fun index() {
        if (cy == bottom) scrollUp(top, bottom, 1)
        else if (cy < rows - 1) cy++
    }

    private fun reverseIndex() {
        wrapPending = false
        if (cy == top) scrollDown(top, bottom, 1)
        else if (cy > 0) cy--
    }

    private fun scrollUp(from: Int, to: Int, count: Int) {
        val n = count.coerceIn(0, to - from + 1)
        if (n == 0) return
        val lines = buf.lines
        repeat(n) {
            val gone = lines[from]
            if (buf === main && from == 0) pushScrollback(gone)
            for (y in from until to) lines[y] = lines[y + 1]
            lines[to] = gone.recycled(eraseStyle())
        }
    }

    private fun scrollDown(from: Int, to: Int, count: Int) {
        val n = count.coerceIn(0, to - from + 1)
        if (n == 0) return
        val lines = buf.lines
        repeat(n) {
            val gone = lines[to]
            for (y in to downTo from + 1) lines[y] = lines[y - 1]
            lines[from] = gone.recycled(eraseStyle())
        }
    }

    private fun pushScrollback(line: Line) {
        if (scrollbackLimit <= 0) return
        scrollback.addLast(line.freeze())
        while (scrollback.size > scrollbackLimit) scrollback.removeFirst()
        scrollbackDirty = true
    }

    private fun tab(count: Int) {
        wrapPending = false
        repeat(count) {
            var x = cx + 1
            while (x < columns - 1 && !tabStops[x]) x++
            cx = x.coerceAtMost(columns - 1)
        }
    }

    private fun backTab(count: Int) {
        wrapPending = false
        repeat(count) {
            var x = cx - 1
            while (x > 0 && !tabStops[x]) x--
            cx = x.coerceAtLeast(0)
        }
    }

    /** Moves to (row, col), 0-based, honouring origin mode. */
    private fun moveTo(row: Int, col: Int) {
        wrapPending = false
        cx = col.coerceIn(0, columns - 1)
        cy = if (originMode) (row + top).coerceIn(top, bottom) else row.coerceIn(0, rows - 1)
    }

    /** Vertical relative moves stop at the margins when starting inside them. */
    private fun moveVertical(delta: Int) {
        wrapPending = false
        val (lo, hi) = if (cy in top..bottom) top to bottom else 0 to rows - 1
        cy = (cy + delta).coerceIn(lo, hi)
    }

    private fun saveCursor() {
        buf.saved = SavedCursor(cx, cy, fg, bg, attrs, wrapPending, originMode, g0Graphics, g1Graphics, useG1)
    }

    private fun restoreCursor() {
        val s = buf.saved ?: SavedCursor(0, 0, 0, 0, 0, false, false, false, false, false)
        cx = s.x.coerceIn(0, columns - 1)
        cy = s.y.coerceIn(0, rows - 1)
        fg = s.fg
        bg = s.bg
        attrs = s.attrs
        wrapPending = s.wrapPending
        originMode = s.originMode
        g0Graphics = s.g0
        g1Graphics = s.g1
        useG1 = s.useG1
    }

    // ================================================================ CSI

    private fun csiByte(b: Int) {
        when (b) {
            in '0'.code..'9'.code -> {
                if (paramCount == 0) paramCount = 1
                val i = paramCount - 1
                if (i < MAX_PARAMS) params[i] = (params[i] * 10 + (b - '0'.code)).coerceAtMost(65535)
                paramStarted = true
            }
            ';'.code, ':'.code -> {
                if (paramCount == 0) paramCount = 1
                if (paramCount < MAX_PARAMS) {
                    subParam[paramCount] = b == ':'.code
                    paramCount++
                }
            }
            '?'.code, '>'.code, '<'.code, '='.code -> if (!paramStarted && paramCount == 0) privateMarker = b
            in 0x20..0x2F -> intermediate = b
            in 0x40..0x7E -> {
                state = State.Ground
                dispatchCsi(b)
            }
            0x1B -> state = State.Escape
            0x18, 0x1A -> state = State.Ground // CAN, SUB abort the sequence
            else -> if (b < 0x20) control(b) // C0 controls execute inside CSI
        }
    }

    /** Parameter [i] or [default] when missing or zero (the VT convention for counts). */
    private fun p(i: Int, default: Int = 1): Int {
        val v = if (i < paramCount) params[i] else 0
        return if (v == 0) default else v
    }

    private fun rawParam(i: Int): Int = if (i < paramCount) params[i] else 0

    private fun dispatchCsi(final: Int) {
        if (privateMarker == '?'.code) {
            when (final) {
                'h'.code -> for (i in 0 until paramCount.coerceAtLeast(1)) decMode(rawParam(i), true)
                'l'.code -> for (i in 0 until paramCount.coerceAtLeast(1)) decMode(rawParam(i), false)
                'J'.code -> eraseDisplay(rawParam(0))
                'K'.code -> eraseLine(rawParam(0))
            }
            return
        }
        if (privateMarker == '>'.code) {
            if (final == 'c'.code) responses.append("\u001b[>1;10;0c") // DA2: VT220-ish
            return
        }
        if (privateMarker != 0) return

        if (intermediate != 0) {
            when {
                intermediate == '!'.code && final == 'p'.code -> softReset()
                else -> Unit // DECSCUSR (" q") and friends: cursor shape is up to the UI
            }
            return
        }

        when (final.toChar()) {
            '@' -> {
                wrapPending = false
                clearWide(buf.lines[cy], cx)
                buf.lines[cy].insert(cx, p(0), eraseStyle())
            }
            'A' -> moveVertical(-p(0))
            'B', 'e' -> moveVertical(p(0))
            'C', 'a' -> {
                wrapPending = false
                cx = (cx + p(0)).coerceAtMost(columns - 1)
            }
            'D' -> {
                wrapPending = false
                cx = (cx - p(0)).coerceAtLeast(0)
            }
            'E' -> {
                moveVertical(p(0))
                cx = 0
            }
            'F' -> {
                moveVertical(-p(0))
                cx = 0
            }
            'G', '`' -> {
                wrapPending = false
                cx = (p(0) - 1).coerceIn(0, columns - 1)
            }
            'H', 'f' -> moveTo(p(0) - 1, p(1) - 1)
            'I' -> tab(p(0))
            'J' -> eraseDisplay(rawParam(0))
            'K' -> eraseLine(rawParam(0))
            'L' -> insertLines(p(0))
            'M' -> deleteLines(p(0))
            'P' -> {
                wrapPending = false
                buf.lines[cy].delete(cx, p(0), eraseStyle())
            }
            'S' -> scrollUp(top, bottom, p(0))
            'T' -> if (paramCount <= 1) scrollDown(top, bottom, p(0))
            'X' -> {
                wrapPending = false
                val line = buf.lines[cy]
                val n = p(0).coerceAtMost(columns - cx)
                clearWide(line, cx)
                clearWide(line, cx + n - 1)
                line.fill(cx, cx + n, eraseStyle())
            }
            'Z' -> backTab(p(0))
            'b' -> repeat(p(0).coerceAtMost(columns * rows)) { print(lastPrinted) }
            'c' -> if (rawParam(0) == 0) responses.append("\u001b[?62;22c") // DA1: VT220 with ANSI colour
            'd' -> {
                wrapPending = false
                val row = p(0) - 1
                cy = if (originMode) (row + top).coerceIn(top, bottom) else row.coerceIn(0, rows - 1)
            }
            'g' -> when (rawParam(0)) {
                0 -> if (cx < columns) tabStops[cx] = false
                3 -> tabStops.fill(false)
            }
            'h' -> for (i in 0 until paramCount.coerceAtLeast(1)) if (rawParam(i) == 4) insertMode = true
            'l' -> for (i in 0 until paramCount.coerceAtLeast(1)) if (rawParam(i) == 4) insertMode = false
            'm' -> sgr()
            'n' -> when (rawParam(0)) {
                5 -> responses.append("\u001b[0n")
                6 -> {
                    val row = if (originMode) cy - top + 1 else cy + 1
                    responses.append("\u001b[").append(row).append(';').append(cx + 1).append('R')
                }
            }
            'r' -> setMargins(p(0), p(1, rows))
            's' -> saveCursor()
            'u' -> restoreCursor()
            't' -> when (rawParam(0)) {
                18 -> responses.append("\u001b[8;").append(rows).append(';').append(columns).append('t')
                else -> Unit
            }
            else -> Unit
        }
    }

    private fun setMargins(topParam: Int, bottomParam: Int) {
        val t = (topParam - 1).coerceIn(0, rows - 1)
        val b = (bottomParam - 1).coerceIn(0, rows - 1)
        if (t < b) {
            top = t
            bottom = b
            moveTo(0, 0) // DECSTBM homes the cursor (to the margin when in origin mode)
        }
    }

    private fun insertLines(n: Int) {
        if (cy !in top..bottom) return
        scrollDown(cy, bottom, n)
        cx = 0
        wrapPending = false
    }

    private fun deleteLines(n: Int) {
        if (cy !in top..bottom) return
        val lines = buf.lines
        val count = n.coerceIn(0, bottom - cy + 1)
        repeat(count) {
            val gone = lines[cy]
            for (y in cy until bottom) lines[y] = lines[y + 1]
            lines[bottom] = gone.recycled(eraseStyle())
        }
        cx = 0
        wrapPending = false
    }

    private fun eraseDisplay(mode: Int) {
        val style = eraseStyle()
        wrapPending = false
        when (mode) {
            0 -> {
                eraseLine(0)
                for (y in cy + 1 until rows) buf.lines[y].clear(style)
            }
            1 -> {
                for (y in 0 until cy) buf.lines[y].clear(style)
                eraseLine(1)
            }
            2 -> for (y in 0 until rows) buf.lines[y].clear(style)
            3 -> {
                if (buf === main && scrollback.isNotEmpty()) {
                    scrollback.clear()
                    scrollbackDirty = true
                }
            }
        }
    }

    private fun eraseLine(mode: Int) {
        val line = buf.lines[cy]
        val style = eraseStyle()
        when (mode) {
            0 -> {
                clearWide(line, cx)
                line.fill(cx, columns, style)
                line.setWrapped(false)
            }
            1 -> {
                clearWide(line, cx)
                line.fill(0, cx + 1, style)
            }
            2 -> line.clear(style)
        }
    }

    private fun decMode(mode: Int, on: Boolean) {
        when (mode) {
            1 -> appCursorKeys = on
            5 -> reverseVideo = on
            6 -> {
                originMode = on
                moveTo(0, 0)
            }
            7 -> autowrap = on
            25 -> cursorVisible = on
            47, 1047 -> switchScreen(on, clear = mode == 1047 && !on)
            1048 -> if (on) saveCursor() else restoreCursor()
            1049 -> {
                if (on) {
                    if (buf !== alt) {
                        saveCursor()
                        switchScreen(true, clear = false)
                        for (y in 0 until rows) alt.lines[y].clear(eraseStyle())
                    }
                } else if (buf === alt) {
                    switchScreen(false, clear = false)
                    restoreCursor()
                }
            }
            2004 -> bracketedPaste = on
            else -> Unit // mouse reporting, focus events, etc. are not supported
        }
    }

    private fun switchScreen(toAlt: Boolean, clear: Boolean) {
        val target = if (toAlt) alt else main
        if (target === buf) return
        if (clear && buf === alt) for (y in 0 until rows) alt.lines[y].clear(CellStyle.DEFAULT)
        buf = target
        wrapPending = false
    }

    // ================================================================ SGR

    private fun sgr() {
        if (paramCount == 0) {
            fg = 0; bg = 0; attrs = 0
            return
        }
        var i = 0
        while (i < paramCount) {
            val code = params[i]
            when (code) {
                0 -> { fg = 0; bg = 0; attrs = 0 }
                1 -> attrs = attrs or CellStyle.BOLD
                2 -> attrs = attrs or CellStyle.DIM
                3 -> attrs = attrs or CellStyle.ITALIC
                4 -> {
                    // 4:0 means "no underline"; other 4:n styles render as a plain underline.
                    attrs = if (i + 1 < paramCount && subParam[i + 1] && params[i + 1] == 0) {
                        attrs and CellStyle.UNDERLINE.inv()
                    } else {
                        attrs or CellStyle.UNDERLINE
                    }
                }
                5, 6 -> attrs = attrs or CellStyle.BLINK
                7 -> attrs = attrs or CellStyle.INVERSE
                8 -> attrs = attrs or CellStyle.HIDDEN
                9 -> attrs = attrs or CellStyle.STRIKE
                21 -> attrs = attrs or CellStyle.UNDERLINE
                22 -> attrs = attrs and (CellStyle.BOLD or CellStyle.DIM).inv()
                23 -> attrs = attrs and CellStyle.ITALIC.inv()
                24 -> attrs = attrs and CellStyle.UNDERLINE.inv()
                25 -> attrs = attrs and CellStyle.BLINK.inv()
                27 -> attrs = attrs and CellStyle.INVERSE.inv()
                28 -> attrs = attrs and CellStyle.HIDDEN.inv()
                29 -> attrs = attrs and CellStyle.STRIKE.inv()
                in 30..37 -> fg = CellStyle.indexed(code - 30)
                38 -> {
                    val (color, used) = extendedColor(i)
                    if (color >= 0) fg = color
                    i += used
                }
                39 -> fg = 0
                in 40..47 -> bg = CellStyle.indexed(code - 40)
                48 -> {
                    val (color, used) = extendedColor(i)
                    if (color >= 0) bg = color
                    i += used
                }
                49 -> bg = 0
                in 90..97 -> fg = CellStyle.indexed(code - 90 + 8)
                in 100..107 -> bg = CellStyle.indexed(code - 100 + 8)
                else -> Unit
            }
            // Skip any further sub-parameters of this code (e.g. 4:3).
            i++
            while (i < paramCount && subParam[i]) i++
        }
    }

    /**
     * Parses 38/48 colours starting at params[i]: `5;n`, `2;r;g;b`, `5:n`, `2:r:g:b` or `2::r:g:b`.
     * Returns the colour (or -1) and how many parameters after [i] it consumed.
     */
    private fun extendedColor(i: Int): Pair<Int, Int> {
        if (i + 1 >= paramCount) return -1 to 0
        val colon = subParam[i + 1]
        val kind = params[i + 1]
        if (colon) {
            // Sub-parameters: count the run of ':'-introduced values after the code.
            var j = i + 1
            while (j + 1 < paramCount && subParam[j + 1]) j++
            val values = (i + 2..j).map { params[it] }
            val color = when (kind) {
                5 -> values.firstOrNull()?.let { CellStyle.indexed(it.coerceIn(0, 255)) } ?: -1
                2 -> {
                    val rgb = if (values.size >= 4) values.subList(1, 4) else values.take(3)
                    if (rgb.size == 3) CellStyle.rgb(rgb[0], rgb[1], rgb[2]) else -1
                }
                else -> -1
            }
            return color to 0 // the caller's sub-parameter skip consumes the rest
        }
        return when (kind) {
            5 -> if (i + 2 < paramCount) CellStyle.indexed(params[i + 2].coerceIn(0, 255)) to 2 else -1 to 1
            2 -> if (i + 4 < paramCount) CellStyle.rgb(params[i + 2], params[i + 3], params[i + 4]) to 4 else -1 to (paramCount - i - 1)
            else -> -1 to 1
        }
    }

    // ================================================================ OSC

    private val oscBytes = java.io.ByteArrayOutputStream()

    private fun oscByte(b: Int) {
        when (b) {
            0x07 -> {
                finishOsc()
                state = State.Ground
            }
            0x1B -> state = State.OscEscape
            0x18, 0x1A -> state = State.Ground
            else -> if (oscBytes.size() < MAX_OSC) oscBytes.write(b)
        }
    }

    private fun finishOsc() {
        val text = oscBytes.toString(Charsets.UTF_8.name())
        oscBytes.reset()
        val sep = text.indexOf(';')
        if (sep <= 0) return
        when (text.substring(0, sep)) {
            "0", "2" -> title = text.substring(sep + 1).take(256)
            else -> Unit // colour queries, hyperlinks, clipboard: not supported
        }
    }

    // ================================================================ reset

    private fun softReset() {
        cursorVisible = true
        insertMode = false
        originMode = false
        autowrap = true
        appCursorKeys = false
        top = 0
        bottom = rows - 1
        fg = 0; bg = 0; attrs = 0
        g0Graphics = false; g1Graphics = false; useG1 = false
        main.saved = null
        alt.saved = null
    }

    private fun reset() {
        softReset()
        bracketedPaste = false
        reverseVideo = false
        buf = main
        for (y in 0 until rows) {
            main.lines[y].clear(CellStyle.DEFAULT)
            alt.lines[y].clear(CellStyle.DEFAULT)
        }
        scrollback.clear()
        scrollbackDirty = true
        tabStops = BooleanArray(columns) { it % 8 == 0 }
        cx = 0; cy = 0; wrapPending = false
        title = ""
    }

    private fun decaln() {
        for (y in 0 until rows) {
            val line = buf.lines[y]
            for (x in 0 until columns) line.set(x, 'E'.code, CellStyle.DEFAULT)
        }
        top = 0
        bottom = rows - 1
        moveTo(0, 0)
    }

    // ================================================================ resize

    /** Changes the size. Lines are cut or padded, not re-wrapped; shrinking pushes top lines to scrollback. */
    fun resize(newColumns: Int, newRows: Int) {
        val c = newColumns.coerceAtLeast(1)
        val r = newRows.coerceAtLeast(1)
        if (c == columns && r == rows) return

        for (b in listOf(main, alt)) {
            val isMain = b === main
            val active = b === buf
            var cursorY = if (active) cy else (b.saved?.y ?: 0)
            val lines = ArrayList<Line>(b.lines.toList())
            if (r < lines.size) {
                // Drop blank lines below the cursor first, then lines from the top (into scrollback).
                var excess = lines.size - r
                while (excess > 0 && lines.size - 1 > cursorY && lines.last().isBlank()) {
                    lines.removeAt(lines.size - 1)
                    excess--
                }
                while (excess > 0) {
                    val gone = lines.removeAt(0)
                    if (isMain) pushScrollback(gone)
                    cursorY--
                    excess--
                }
            } else if (r > lines.size) {
                var missing = r - lines.size
                // Bring scrollback back when the screen grows and the cursor sits at the bottom.
                if (isMain) {
                    while (missing > 0 && scrollback.isNotEmpty() && cursorY >= lines.size - 1) {
                        lines.add(0, Line.fromRow(scrollback.removeLast(), c))
                        scrollbackDirty = true
                        cursorY++
                        missing--
                    }
                }
                repeat(missing) { lines.add(Line(c)) }
            }
            lines.forEach { it.resize(c) }
            b.lines = lines.toTypedArray()
            if (active) {
                cy = cursorY.coerceIn(0, r - 1)
                b.saved = b.saved?.let { it.copy(x = it.x.coerceIn(0, c - 1), y = it.y.coerceIn(0, r - 1)) }
            } else {
                b.saved = b.saved?.let { it.copy(x = it.x.coerceIn(0, c - 1), y = cursorY.coerceIn(0, r - 1)) }
            }
        }
        columns = c
        rows = r
        cx = cx.coerceIn(0, c - 1)
        cy = cy.coerceIn(0, r - 1)
        wrapPending = false
        top = 0
        bottom = r - 1
        tabStops = BooleanArray(c) { it % 8 == 0 }
    }

    // ================================================================ snapshot

    fun snapshot(): TerminalScreen {
        val visible = ArrayList<TerminalRow>(rows)
        for (y in 0 until rows) visible.add(buf.lines[y].freeze())
        if (scrollbackDirty) {
            scrollbackSnapshot = ArrayList(scrollback)
            scrollbackDirty = false
        }
        return TerminalScreen(
            columns = columns,
            rows = rows,
            lines = visible,
            scrollback = if (buf === main) scrollbackSnapshot else emptyList(),
            cursorRow = cy,
            cursorColumn = cx,
            cursorVisible = cursorVisible,
            title = title,
            altScreen = buf === alt,
            bracketedPaste = bracketedPaste,
            applicationCursorKeys = appCursorKeys,
            reverseVideo = reverseVideo,
        )
    }

    // ================================================================ model

    private data class SavedCursor(
        val x: Int, val y: Int, val fg: Int, val bg: Int, val attrs: Int, val wrapPending: Boolean,
        val originMode: Boolean, val g0: Boolean, val g1: Boolean, val useG1: Boolean,
    )

    private class Buffer(columns: Int, rows: Int) {
        var lines: Array<Line> = Array(rows) { Line(columns) }
        var saved: SavedCursor? = null
    }

    /** A mutable row. Every mutation drops [cached], so unchanged rows keep their snapshot instance. */
    internal class Line(columns: Int) {
        var cp = IntArray(columns)
        var st = LongArray(columns)
        private var wrapped = false
        private var cached: TerminalRow? = null

        fun set(x: Int, codePoint: Int, style: Long) {
            if (cp[x] == codePoint && st[x] == style) return
            cp[x] = codePoint
            st[x] = style
            cached = null
        }

        fun setWrapped(value: Boolean) {
            if (wrapped != value) {
                wrapped = value
                cached = null
            }
        }

        fun fill(from: Int, to: Int, style: Long) {
            val end = to.coerceAtMost(cp.size)
            if (from >= end) return
            cp.fill(0, from, end)
            st.fill(style, from, end)
            cached = null
        }

        fun clear(style: Long) {
            fill(0, cp.size, style)
            setWrapped(false)
        }

        fun insert(x: Int, n: Int, style: Long) {
            if (x >= cp.size) return
            val count = n.coerceAtMost(cp.size - x)
            cp.copyInto(cp, x + count, x, cp.size - count)
            st.copyInto(st, x + count, x, st.size - count)
            fill(x, x + count, style)
            // A wide head pushed into the last column loses its tail.
            val last = cp.size - 1
            if (cp[last] > 0 && CharWidth.of(cp[last]) == 2) cp[last] = 0
            cached = null
        }

        fun delete(x: Int, n: Int, style: Long) {
            if (x >= cp.size) return
            val count = n.coerceAtMost(cp.size - x)
            cp.copyInto(cp, x, x + count, cp.size)
            st.copyInto(st, x, x + count, st.size)
            fill(cp.size - count, cp.size, style)
            if (cp[x] == TerminalRow.WIDE_TAIL) cp[x] = 0
            cached = null
        }

        fun isBlank(): Boolean = cp.all { it == 0 }

        fun resize(columns: Int) {
            if (columns == cp.size) return
            cp = cp.copyOf(columns)
            st = st.copyOf(columns)
            if (columns > 0 && cp[columns - 1] > 0 && CharWidth.of(cp[columns - 1]) == 2) cp[columns - 1] = 0
            cached = null
        }

        /** The immutable view of this line, reused until the line changes. */
        fun freeze(): TerminalRow = cached ?: TerminalRow(cp.copyOf(), st.copyOf(), wrapped).also { cached = it }

        /** Turns a line that scrolled away into a fresh blank one (after [freeze] took its contents). */
        fun recycled(style: Long): Line {
            cp.fill(0)
            st.fill(style)
            wrapped = false
            cached = null
            return this
        }

        companion object {
            fun fromRow(row: TerminalRow, columns: Int): Line = Line(columns).also { line ->
                for (x in 0 until minOf(columns, row.columns)) {
                    line.cp[x] = row.codePointAt(x)
                    line.st[x] = row.styleAt(x)
                }
                line.wrapped = row.wrapped
            }
        }
    }

    companion object {
        private const val MAX_PARAMS = 32
        private const val MAX_OSC = 4096

        /** DEC special graphics for 0x5F..0x7E. */
        private val DEC_GRAPHICS = charArrayOf(
            ' ', '◆', '▒', '␉', '␌', '␍', '␊', '°', '±', '␤', '␋', '┘', '┐', '┌', '└', '┼',
            '⎺', '⎻', '─', '⎼', '⎽', '├', '┤', '┴', '┬', '│', '≤', '≥', 'π', '≠', '£', '·',
        )
    }
}
