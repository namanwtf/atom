package atom.linux.term

import java.io.ByteArrayOutputStream

/**
 * One command and its result in Simple mode. [rows] are the command's output as a terminal would finally show
 * it: colours kept, carriage-return progress bars and cursor-up repaints collapsed to their last state.
 */
class SimpleBlock(
    val id: Int,
    /** The command line, or null for output that arrived while no command was running (e.g. job notices). */
    val command: String?,
    val rows: List<TerminalRow>,
    /** Null while running. */
    val exitCode: Int?,
    val running: Boolean,
    val startedAtMs: Long,
    val finishedAtMs: Long?,
) {
    fun outputText(): String = rows.joinToString("\n") { it.trimmedText() }.trimEnd()
}

/**
 * Turns the raw output of an interactive shell into [SimpleBlock]s.
 *
 * The shell's prompt is set to an invisible marker, `ESC ] 7770 ; <exit status> BEL` (see [SHELL_SETUP]), which
 * it prints each time a command finishes. Everything between submitting a command and the next marker is that
 * command's output, rendered by a private [TerminalEmulator] per block. Output before the first marker (login
 * banner, the setup line) is dropped. Markers split across reads are handled. Pure Kotlin, so it is unit-tested.
 */
class SimpleTranscript(
    columns: Int = 120,
    private val maxBlocks: Int = 200,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    var columns: Int = columns.coerceAtLeast(20)
        private set

    private class Block(val id: Int, val command: String?, val emulator: TerminalEmulator, val startedAt: Long) {
        var exitCode: Int? = null
        var finishedAt: Long? = null
        var cached: SimpleBlock? = null
        var dirty = true
    }

    private val blocks = ArrayList<Block>()
    private var current: Block? = null
    private var nextId = 1
    private var ready = false

    /** True once the shell has printed its first marker and accepts commands. */
    val isReady: Boolean get() = ready

    /** True while a submitted command has not finished. */
    val isRunning: Boolean get() = current?.let { it.command != null && it.exitCode == null } == true

    fun resize(columns: Int) {
        this.columns = columns.coerceAtLeast(20)
    }

    /** Starts a block for [command]; call just before writing it to the shell. Returns the block id. */
    fun submit(command: String): Int {
        closeNote()
        val b = Block(nextId++, command, newEmulator(), clock())
        blocks += b
        current = b
        trim()
        return b.id
    }

    /** Feeds raw shell output. */
    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        for (i in offset until offset + length) feedByte(bytes[i].toInt() and 0xFF)
        flushOut()
    }

    fun snapshot(): List<SimpleBlock> = blocks.map { b ->
        val cached = b.cached
        if (!b.dirty && cached != null) cached else freeze(b).also { b.cached = it; b.dirty = false }
    }

    fun clear() {
        blocks.clear()
        current = null
    }

    // ------------------------------------------------------------------ marker scanning

    private val out = ByteArrayOutputStream()
    private val partial = ByteArrayOutputStream()
    private var digits = 0
    private var digitCount = 0
    private var sawEscape = false

    private fun feedByte(b: Int) {
        val pos = partial.size()
        if (pos < PREFIX.size) {
            if (b == PREFIX[pos].toInt()) {
                partial.write(b)
                return
            }
            if (pos > 0) {
                releasePartial()
                feedByte(b) // it may start a new marker
                return
            }
            out.write(b)
            return
        }
        if (sawEscape) {
            if (b == '\\'.code) {
                complete()
            } else {
                releasePartial()
                feedByte(b)
            }
            return
        }
        when {
            b in '0'.code..'9'.code && digitCount < 4 -> {
                digits = digits * 10 + (b - '0'.code)
                digitCount++
                partial.write(b)
            }
            b == 0x07 && digitCount > 0 -> complete()
            b == 0x1B && digitCount > 0 -> {
                sawEscape = true
                partial.write(b)
            }
            else -> {
                releasePartial()
                feedByte(b)
            }
        }
    }

    /** What looked like a marker was not one: it is ordinary output. */
    private fun releasePartial() {
        partial.writeTo(out)
        resetMatcher()
    }

    private fun complete() {
        val code = digits
        flushOut()
        resetMatcher()
        onMarker(code)
    }

    private fun resetMatcher() {
        partial.reset()
        digits = 0
        digitCount = 0
        sawEscape = false
    }

    private fun flushOut() {
        if (out.size() == 0) return
        val bytes = out.toByteArray()
        out.reset()
        emit(bytes)
    }

    // ------------------------------------------------------------------ blocks

    private fun emit(bytes: ByteArray) {
        if (!ready) return
        val target = current ?: Block(nextId++, null, newEmulator(), clock()).also {
            blocks += it
            current = it
            trim()
        }
        target.emulator.process(bytes)
        target.emulator.takeResponses() // the live terminal answers queries, not this copy
        target.dirty = true
    }

    private fun onMarker(code: Int) {
        if (!ready) {
            ready = true
            return
        }
        val b = current ?: return
        if (b.command != null) b.exitCode = code
        b.finishedAt = clock()
        b.dirty = true
        current = null
    }

    /** Output-only blocks end when the next command starts. */
    private fun closeNote() {
        val b = current ?: return
        if (b.command == null) {
            b.finishedAt = clock()
            b.dirty = true
            current = null
        }
    }

    private fun trim() {
        while (blocks.size > maxBlocks) blocks.removeAt(0)
    }

    private fun newEmulator() = TerminalEmulator(columns, ROWS, SCROLLBACK)

    private fun freeze(b: Block): SimpleBlock {
        val screen = b.emulator.snapshot()
        val all = ArrayList<TerminalRow>(screen.scrollback.size + screen.lines.size)
        all.addAll(screen.scrollback)
        all.addAll(screen.lines)
        // Drop the shell's echo of the command itself.
        val cmdLines = b.command?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        var skip = 0
        while (skip < cmdLines.size && skip < all.size && all[skip].trimmedText().trim().endsWith(cmdLines[skip])) skip++
        var end = all.size
        while (end > skip && all[end - 1].trimmedText().isEmpty()) end--
        return SimpleBlock(
            id = b.id,
            command = b.command,
            rows = ArrayList(all.subList(skip, end)),
            exitCode = b.exitCode,
            running = b.command != null && b.exitCode == null,
            startedAtMs = b.startedAt,
            finishedAtMs = b.finishedAt,
        )
    }

    companion object {
        private val PREFIX = "\u001b]7770;".toByteArray(Charsets.US_ASCII)
        private const val ROWS = 40
        private const val SCROLLBACK = 3000

        /** The marker the shell prints after each command (exposed for tests and tools). */
        fun marker(exitCode: Int) = "\u001b]7770;$exitCode\u0007"

        /**
         * Turns an interactive POSIX shell (mksh, ash, bash) into a marker-emitting one: the prompt becomes only the
         * invisible marker with the last exit status.
         */
        const val SHELL_SETUP =
            "PS1=\"\$(printf '\\033]7770;')\"'\$?'\"\$(printf '\\007')\"; PS2=''; PROMPT_COMMAND=''; export PS1 PS2"
    }
}
