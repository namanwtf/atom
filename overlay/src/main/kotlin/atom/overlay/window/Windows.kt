package atom.overlay.window

import android.accessibilityservice.AccessibilityService
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.graphics.Rect
import android.view.Display
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import atom.overlay.bubble.Area

/** What the accessibility service measured: the keyboard's top edge in screen px, or null when it is closed. */
internal data class KeyboardProbe(val top: Int?)

/** A context that can add overlay windows, and the window type that goes with it. */
internal class WindowSlot(val context: Context, val type: Int)

/**
 * Where overlay windows come from. The bubble and mini chat need the "display over other apps" permission; the run
 * capsule and edge glow prefer the accessibility service's own overlay (no permission, trusted for touches) and
 * fall back to the app overlay.
 */
internal object Windows {
    private lateinit var app: Context
    private var overlayContext: Context? = null
    private var accessibility: Context? = null
    private val configListeners = mutableListOf<() -> Unit>()

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(app)

    /** For the bubble and the mini chat. Null without the overlay permission. */
    fun appOverlay(): WindowSlot? {
        if (!canDrawOverlays()) return null
        val ctx = overlayContext ?: createOverlayContext().also { overlayContext = it } ?: return null
        return WindowSlot(ctx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
    }

    /** For the run capsule and edge glow. */
    fun runOverlay(): WindowSlot? =
        accessibility?.let { WindowSlot(it, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) } ?: appOverlay()

    val hasAccessibility: Boolean get() = accessibility != null

    fun attachAccessibility(service: Context) {
        accessibility = service
    }

    fun detachAccessibility(service: Context) {
        if (accessibility === service) accessibility = null
    }

    /**
     * The keyboard as the accessibility service sees it: null when the service is not connected or cannot list
     * windows; otherwise the keyboard window's top edge in screen px, or null inside when no keyboard is showing.
     * On demand only (the service subscribes to no events for this).
     */
    fun keyboardProbe(): KeyboardProbe? {
        val service = accessibility as? AccessibilityService ?: return null
        val windows = runCatching { service.windows }.getOrNull() ?: return null
        val ime = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: return KeyboardProbe(null)
        val r = Rect()
        ime.getBoundsInScreen(r)
        return KeyboardProbe(if (r.height() > 0) r.top else null)
    }

    /** Called on rotation, resolution or density changes while any overlay listens. */
    fun onConfigurationChanged(listener: () -> Unit): () -> Unit {
        configListeners += listener
        return { configListeners -= listener }
    }

    fun area(context: Context): Area {
        val metrics = context.getSystemService(WindowManager::class.java).currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
        )
        val b = metrics.bounds
        return Area(
            width = b.width().toFloat(),
            height = b.height().toFloat(),
            left = insets.left.toFloat(),
            top = insets.top.toFloat(),
            right = insets.right.toFloat(),
            bottom = insets.bottom.toFloat(),
        )
    }

    fun powerSave(): Boolean = app.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true

    /** Cross-window blur is a compositor feature; it is off on some devices, with battery saver, and in multi-window. */
    fun blurAvailable(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !powerSave() &&
            context.getSystemService(WindowManager::class.java).isCrossWindowBlurEnabled

    private fun createOverlayContext(): Context? = runCatching {
        val display = app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        app.createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            .also { ctx ->
                ctx.registerComponentCallbacks(object : ComponentCallbacks {
                    override fun onConfigurationChanged(newConfig: Configuration) {
                        configListeners.toList().forEach { it() }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onLowMemory() {}
                })
            }
    }.getOrNull()
}
