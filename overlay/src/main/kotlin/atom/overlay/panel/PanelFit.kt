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
enum class KeyboardSource { None, Measured, Insets, Estimate }

data class KeyboardFitResult(val height: Int, val source: KeyboardSource)

object KeyboardFit {
    /** Last-resort keyboard height as a share of the display, used only when nothing measured it. */
    const val ESTIMATE_FRACTION = 0.32f

    /**
     * How much of the bottom of the panel window the keyboard covers, in px.
     *
     * Overlay windows do not receive IME insets on some ROMs, so the order is:
     * 1. the keyboard window's top edge measured by the accessibility service ([measuredTop], screen px) while the
     *    service is connected ([measuredAvailable]); with the service connected, no keyboard window means none;
     * 2. the window's IME inset ([imeInset]);
     * 3. [ESTIMATE_FRACTION] of [displayHeight], only while the composer has focus and nothing above could tell.
     *
     * [windowTop] and [windowHeight] place the panel window on screen. A window the system already shrank for the
     * keyboard ([windowHeight] well short of [displayHeight]) needs no further room.
     */
    fun keyboard(
        focused: Boolean,
        measuredAvailable: Boolean,
        measuredTop: Int?,
        imeInset: Int,
        windowTop: Int,
        windowHeight: Int,
        displayHeight: Int,
    ): KeyboardFitResult {
        val windowBottom = windowTop + windowHeight
        if (measuredAvailable && measuredTop != null && measuredTop < windowBottom) {
            return KeyboardFitResult((windowBottom - measuredTop).coerceIn(0, windowHeight), KeyboardSource.Measured)
        }
        if (imeInset > 0) return KeyboardFitResult(imeInset.coerceAtMost(windowHeight), KeyboardSource.Insets)
        if (measuredAvailable || !focused) return KeyboardFitResult(0, KeyboardSource.None)
        // Already resized by the system for the keyboard (adjustResize honoured): nothing to add.
        if (displayHeight > 0 && windowHeight < displayHeight * RESIZED_FRACTION) return KeyboardFitResult(0, KeyboardSource.None)
        return KeyboardFitResult((displayHeight * ESTIMATE_FRACTION).toInt(), KeyboardSource.Estimate)
    }

    private const val RESIZED_FRACTION = 0.85f
}
