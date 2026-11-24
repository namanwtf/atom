package atom.linux.term

import kotlinx.coroutines.flow.StateFlow

/** An interactive program on a pseudo-terminal. */
interface TerminalSession {
    /** The latest screen; updated at most ~60 times a second while output arrives, never while idle. */
    val screen: StateFlow<TerminalScreen>

    /** Null while running; the exit status (or 128 + signal) once the program has ended. */
    val exitCode: StateFlow<Int?>

    /** Sends text typed by the user (UTF-8 encoded). */
    fun write(text: String)

    /** Sends raw bytes (escape sequences, control characters). */
    fun writeBytes(bytes: ByteArray)

    fun resize(cols: Int, rows: Int)

    /** Hangs up and kills the program and everything it started. Safe to call more than once. */
    fun close()
}

/** Byte sequences for keys the soft keyboard lacks, honouring the terminal's current modes. */
object TerminalKeys {
    const val ESC = "\u001b"
    const val TAB = "\t"
    const val ENTER = "\r"
    const val BACKSPACE = "\u007f"

    enum class Key { Up, Down, Right, Left, Home, End, PageUp, PageDown, Insert, Delete }

    fun key(key: Key, appCursor: Boolean, alt: Boolean = false, ctrl: Boolean = false, shift: Boolean = false): String {
        val mod = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
        fun cursor(c: Char): String = when {
            mod > 1 -> "\u001b[1;$mod$c"
            appCursor -> "\u001bO$c"
            else -> "\u001b[$c"
        }
        fun tilde(n: Int): String = if (mod > 1) "\u001b[$n;$mod~" else "\u001b[$n~"
        return when (key) {
            Key.Up -> cursor('A')
            Key.Down -> cursor('B')
            Key.Right -> cursor('C')
            Key.Left -> cursor('D')
            Key.Home -> cursor('H')
            Key.End -> cursor('F')
            Key.Insert -> tilde(2)
            Key.Delete -> tilde(3)
            Key.PageUp -> tilde(5)
            Key.PageDown -> tilde(6)
        }
    }

    /** Applies Ctrl / Alt to one typed character: Ctrl+C -> 0x03, Alt+x -> ESC x. */
    fun modified(text: String, ctrl: Boolean, alt: Boolean): String {
        if (text.isEmpty()) return text
        var out = text
        if (ctrl && text.length == 1) {
            val c = text[0]
            out = when (c) {
                in 'a'..'z' -> (c - 'a' + 1).toChar().toString()
                in 'A'..'Z' -> (c - 'A' + 1).toChar().toString()
                ' ', '@', '2' -> "\u0000"
                '[', '3' -> "\u001b"
                '\\', '4' -> "\u001c"
                ']', '5' -> "\u001d"
                '^', '6' -> "\u001e"
                '_', '7', '/' -> "\u001f"
                '?', '8' -> "\u007f"
                else -> text
            }
        }
        return if (alt) ESC + out else out
    }

    /** Wraps pasted text in bracketed-paste markers when the program asked for them. */
    fun paste(text: String, bracketed: Boolean): String {
        val normalized = text.replace("\r\n", "\r").replace('\n', '\r')
        return if (bracketed) "\u001b[200~" + normalized.replace("\u001b[201~", "") + "\u001b[201~" else normalized
    }
}
