package atom.overlay.panel

import kotlin.math.max

/** Vertical placement of the mini chat sheet, in px from the top of its full-screen window. */
data class SheetBox(val top: Int, val height: Int)

object PanelFit {
    /**
     * The sheet is top-anchored below the status bar and its bottom sits above the navigation bar, or above the
     * keyboard while it is open, keeping [margin] from each: the sheet shrinks, it never slides. When the keyboard
     * leaves less than [minHeight], the sheet keeps that height and rises into the status-bar margin, never above
     * the top of the window.
     */
    fun fit(windowHeight: Int, statusTop: Int, navBottom: Int, imeBottom: Int, margin: Int, minHeight: Int): SheetBox {
        val bottomInset = max(navBottom, imeBottom) + margin
        val bottom = windowHeight - bottomInset
        var top = statusTop + margin
        if (bottom - top < minHeight) top = max(0, bottom - minHeight)
        return SheetBox(top, max(0, bottom - top))
    }
}

/** Where the keyboard height came from. */
enum class KeyboardSource { None, Measured, Insets, Frame, Estimate }

data class KeyboardFitResult(val height: Int, val source: KeyboardSource)

/**
 * What the panel knows about the keyboard right now. Each source counts as "unknown" until it has shown a
 * keyboard during this panel session, because on some ROMs a source never reports one at all.
 *
 * @property serviceTop the keyboard window's top edge (screen px) as the accessibility service reads it; null when
 *   it lists no keyboard window, or is not connected.
 * @property serviceSawKeyboard the service reported a keyboard window during this panel session, so its "no
 *   keyboard" can be trusted.
 * @property imeInset the window's IME inset.
 * @property insetsWork IME insets arrived for this window during this session, so a zero inset means closed.
 * @property frameKeyboard keyboard overlap from the window's visible display frame (it includes the navigation
 *   bar, like an IME inset): null while the frame has never shown a keyboard this session, 0 when it shows none now.
 */
data class KeyboardSignals(
    val focused: Boolean,
    val serviceTop: Int? = null,
    val serviceSawKeyboard: Boolean = false,
    val imeInset: Int = 0,
    val insetsWork: Boolean = false,
    val frameKeyboard: Int? = null,
    val windowTop: Int = 0,
    val windowHeight: Int,
    val displayHeight: Int,
)

object KeyboardFit {
    /** Last-resort keyboard height as a share of the display, used only when nothing measured it. */
    const val ESTIMATE_FRACTION = 0.32f

    /**
     * How much of the bottom of the panel window the keyboard covers, in px. Overlay windows do not receive IME
     * insets on some ROMs, so the order is:
     * 1. a measured "keyboard gone" (the service, the insets or the visible frame showed it and now do not) clears
     *    everything below: a stale reading, an inset that was never re-delivered, and the estimate;
     * 2. the keyboard top measured by the accessibility service;
     * 3. the IME inset;
     * 4. the visible display frame;
     * 5. [ESTIMATE_FRACTION] of the display, only while the composer has focus and no source has ever worked.
     */
    fun keyboard(s: KeyboardSignals): KeyboardFitResult {
        val windowBottom = s.windowTop + s.windowHeight
        if (gone(s)) return KeyboardFitResult(0, KeyboardSource.None)
        val top = s.serviceTop
        if (top != null && top < windowBottom) {
            return KeyboardFitResult((windowBottom - top).coerceIn(0, s.windowHeight), KeyboardSource.Measured)
        }
        if (s.imeInset > 0) return KeyboardFitResult(s.imeInset.coerceAtMost(s.windowHeight), KeyboardSource.Insets)
        val frame = s.frameKeyboard
        if (frame != null && frame > 0) return KeyboardFitResult(frame.coerceAtMost(s.windowHeight), KeyboardSource.Frame)
        if (!s.focused || s.insetsWork) return KeyboardFitResult(0, KeyboardSource.None)
        // Already resized by the system for the keyboard (adjustResize honoured): nothing to add.
        if (s.displayHeight > 0 && s.windowHeight < s.displayHeight * RESIZED_FRACTION) return KeyboardFitResult(0, KeyboardSource.None)
        return KeyboardFitResult((s.displayHeight * ESTIMATE_FRACTION).toInt(), KeyboardSource.Estimate)
    }

    /**
     * A source that showed the keyboard this session now measures none: the keyboard is closed. Any one such
     * source wins over a reading that still shows it (the service's window list can lag behind).
     */
    fun gone(s: KeyboardSignals): Boolean =
        (s.serviceSawKeyboard && s.serviceTop == null) || s.frameKeyboard == 0 || (s.insetsWork && s.imeInset == 0)

    /**
     * Keyboard overlap read from the visible display frame: [windowBottom] minus the frame's [visibleBottom]. It
     * counts as a keyboard only above [navBottom] plus [threshold]; [seen] says whether the frame has shown one this
     * session (until then the answer is unknown, null).
     */
    fun frameKeyboard(windowBottom: Int, visibleBottom: Int, navBottom: Int, threshold: Int, seen: Boolean): Int? {
        val overlap = max(0, windowBottom - visibleBottom)
        val showing = overlap > navBottom + threshold
        return when {
            showing -> overlap
            seen -> 0
            else -> null
        }
    }

    private const val RESIZED_FRACTION = 0.85f
}
