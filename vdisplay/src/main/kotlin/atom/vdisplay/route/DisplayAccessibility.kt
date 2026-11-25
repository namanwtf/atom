package atom.vdisplay.route

import android.graphics.Bitmap

/**
 * What the accessibility service can do on a display other than the main one. The device module owns the
 * service; the app installs an adapter into [DisplayRouter.accessibility]. Every member may return null / false
 * when unsupported, and the router then falls back to root.
 */
interface DisplayAccessibility {
    /** Outline of the windows on [displayId] (from `getWindowsOnAllDisplays()`), or null. */
    suspend fun readTree(displayId: Int, maxChars: Int): String? = null

    /** `takeScreenshot(displayId, ...)`, or null. */
    suspend fun screenshot(displayId: Int): Bitmap? = null

    /** `dispatchGesture` with `GestureDescription.Builder.setDisplayId(displayId)`. */
    suspend fun tap(displayId: Int, x: Int, y: Int, long: Boolean): Boolean = false

    suspend fun swipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean = false
}
