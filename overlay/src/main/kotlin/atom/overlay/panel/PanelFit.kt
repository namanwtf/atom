package atom.overlay.panel

import kotlin.math.max

/** Vertical placement of the mini chat sheet, in px from the top of its full-screen window. */
data class SheetBox(val top: Int, val height: Int)

object PanelFit {
    /**
     * The sheet spans from below the status bar to above the navigation bar, or above the keyboard while it is
     * open, keeping [margin] from each. Measured from live insets, so it fits any device and keyboard. When the
     * keyboard leaves less than [minHeight], the sheet keeps that height and rises into the status-bar margin,
     * never below the top of the window.
     */
    fun fit(windowHeight: Int, statusTop: Int, navBottom: Int, imeBottom: Int, margin: Int, minHeight: Int): SheetBox {
        val bottomInset = max(navBottom, imeBottom) + margin
        val bottom = windowHeight - bottomInset
        var top = statusTop + margin
        if (bottom - top < minHeight) top = max(0, bottom - minHeight)
        return SheetBox(top, max(0, bottom - top))
    }
}
