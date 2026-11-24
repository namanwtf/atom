package atom.linux.term

/**
 * Incremental UTF-8 decoder: a sequence split across two reads decodes correctly. Malformed input
 * (bad continuation, overlong form, surrogate, > U+10FFFF) yields U+FFFD, like a terminal should.
 */
internal class Utf8Decoder {
    private var codePoint = 0
    private var remaining = 0
    private var min = 0

    /** Feeds one byte; returns a complete code point, [NONE] while a sequence is incomplete. */
    fun feed(b: Int): Int {
        val byte = b and 0xFF
        if (remaining == 0) {
            return when {
                byte < 0x80 -> byte
                byte and 0xE0 == 0xC0 -> start(byte and 0x1F, 1, 0x80)
                byte and 0xF0 == 0xE0 -> start(byte and 0x0F, 2, 0x800)
                byte and 0xF8 == 0xF0 -> start(byte and 0x07, 3, 0x10000)
                else -> REPLACEMENT
            }
        }
        if (byte and 0xC0 != 0x80) {
            // The sequence broke off; report it and reprocess this byte as a fresh start.
            remaining = 0
            val next = feed(byte)
            pendingAfterError = next
            return REPLACEMENT
        }
        codePoint = (codePoint shl 6) or (byte and 0x3F)
        remaining--
        if (remaining > 0) return NONE
        val cp = codePoint
        return if (cp < min || cp > 0x10FFFF || cp in 0xD800..0xDFFF) REPLACEMENT else cp
    }

    /**
     * After [feed] returned [REPLACEMENT] for a broken sequence, the byte that broke it may itself have
     * produced a code point; the caller must take it with this before feeding more.
     */
    var pendingAfterError: Int = NONE
        private set

    fun takePending(): Int = pendingAfterError.also { pendingAfterError = NONE }

    private fun start(bits: Int, more: Int, minimum: Int): Int {
        codePoint = bits
        remaining = more
        min = minimum
        return NONE
    }

    fun reset() {
        remaining = 0
        pendingAfterError = NONE
    }

    companion object {
        const val NONE = -1
        const val REPLACEMENT = 0xFFFD
    }
}

/** Display width of a code point in a terminal: 0 (combining), 1 or 2 (East Asian wide / emoji). */
internal object CharWidth {
    private val zero = intArrayOf(
        0x0300, 0x036F, 0x0483, 0x0489, 0x0591, 0x05BD, 0x05BF, 0x05BF, 0x05C1, 0x05C2, 0x05C4, 0x05C5,
        0x05C7, 0x05C7, 0x0610, 0x061A, 0x064B, 0x065F, 0x0670, 0x0670, 0x06D6, 0x06DC, 0x06DF, 0x06E4,
        0x06E7, 0x06E8, 0x06EA, 0x06ED, 0x0900, 0x0902, 0x093A, 0x093A, 0x093C, 0x093C, 0x0941, 0x0948,
        0x094D, 0x094D, 0x0951, 0x0957, 0x0962, 0x0963, 0x0E31, 0x0E31, 0x0E34, 0x0E3A, 0x0E47, 0x0E4E,
        0x1AB0, 0x1AFF, 0x1DC0, 0x1DFF, 0x200B, 0x200F, 0x2028, 0x202E, 0x2060, 0x2064, 0x20D0, 0x20FF,
        0xFE00, 0xFE0F, 0xFE20, 0xFE2F, 0xFEFF, 0xFEFF, 0x1F3FB, 0x1F3FF, 0xE0000, 0xE007F, 0xE0100, 0xE01EF,
    )
    private val wide = intArrayOf(
        0x1100, 0x115F, 0x231A, 0x231B, 0x2329, 0x232A, 0x23E9, 0x23EC, 0x23F0, 0x23F0, 0x23F3, 0x23F3,
        0x25FD, 0x25FE, 0x2614, 0x2615, 0x2648, 0x2653, 0x267F, 0x267F, 0x2693, 0x2693, 0x26A1, 0x26A1,
        0x26AA, 0x26AB, 0x26BD, 0x26BE, 0x26C4, 0x26C5, 0x26CE, 0x26CE, 0x26D4, 0x26D4, 0x26EA, 0x26EA,
        0x26F2, 0x26F3, 0x26F5, 0x26F5, 0x26FA, 0x26FA, 0x26FD, 0x26FD, 0x2705, 0x2705, 0x270A, 0x270B,
        0x2728, 0x2728, 0x274C, 0x274C, 0x274E, 0x274E, 0x2753, 0x2755, 0x2757, 0x2757, 0x2795, 0x2797,
        0x27B0, 0x27B0, 0x27BF, 0x27BF, 0x2B1B, 0x2B1C, 0x2B50, 0x2B50, 0x2B55, 0x2B55, 0x2E80, 0x303E,
        0x3041, 0x33FF, 0x3400, 0x4DBF, 0x4E00, 0x9FFF, 0xA000, 0xA4CF, 0xA960, 0xA97F, 0xAC00, 0xD7A3,
        0xF900, 0xFAFF, 0xFE10, 0xFE19, 0xFE30, 0xFE6F, 0xFF00, 0xFF60, 0xFFE0, 0xFFE6, 0x1F004, 0x1F004,
        0x1F0CF, 0x1F0CF, 0x1F18E, 0x1F18E, 0x1F191, 0x1F19A, 0x1F200, 0x1F251, 0x1F300, 0x1F64F,
        0x1F680, 0x1F6FF, 0x1F7E0, 0x1F7EB, 0x1F90C, 0x1F9FF, 0x1FA70, 0x1FAFF, 0x20000, 0x2FFFD,
        0x30000, 0x3FFFD,
    )

    fun of(cp: Int): Int {
        if (cp < 0x300) return 1
        if (inRanges(zero, cp)) return 0
        if (cp >= 0x1100 && inRanges(wide, cp)) return 2
        return 1
    }

    private fun inRanges(ranges: IntArray, cp: Int): Boolean {
        var lo = 0
        var hi = ranges.size / 2 - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                cp < ranges[mid * 2] -> hi = mid - 1
                cp > ranges[mid * 2 + 1] -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }
}
