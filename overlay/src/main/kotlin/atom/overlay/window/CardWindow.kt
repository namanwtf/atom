package atom.overlay.window

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.Window
import android.view.WindowManager.LayoutParams
import androidx.compose.runtime.Composable
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import java.util.function.Consumer

/**
 * An overlay window shaped like a card, with real frosted glass: what is behind it is blurred inside its rounded
 * outline (Android 12+ background blur, when the compositor offers it) under a translucent tint. Hosted in a
 * [Dialog] because only a Window can blur its own background; it carries the same lifecycle as [OverlayWindow].
 * Single use: create, [show], [remove]. Main thread only.
 */
internal class CardWindow(
    context: Context,
    title: String,
    private val onBack: () -> Unit,
    private val onOutsideTouch: () -> Unit,
    content: @Composable () -> Unit,
) {
    private val owner = WindowOwner()
    val view: RootLayout = owner.root(context, null, content)
    private val dialog = object : Dialog(context, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar) {
        @Deprecated("Deprecated in Java")
        override fun onBackPressed() = this@CardWindow.onBack()

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                this@CardWindow.onOutsideTouch()
                return true
            }
            return super.onTouchEvent(event)
        }
    }
    private val window: Window = checkNotNull(dialog.window)
    private var blurListener: Consumer<Boolean>? = null

    var attached = false
        private set

    /** Called with the compositor's current answer whenever cross-window blur turns on or off. */
    var onBlurChanged: ((Boolean) -> Unit)? = null

    init {
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setContentView(view)
        // Compose finds its lifecycle from the window's root view, which is the dialog's decor here.
        window.decorView.setViewTreeLifecycleOwner(owner)
        window.decorView.setViewTreeSavedStateRegistryOwner(owner)
        window.setType(LayoutParams.TYPE_APPLICATION_OVERLAY)
        window.clearFlags(LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(LayoutParams.FLAG_HARDWARE_ACCELERATED or LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        window.setWindowAnimations(android.R.style.Animation_Toast)
        window.decorView.setPadding(0, 0, 0, 0)
        window.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        window.setTitle(title)
    }

    /** Size and place the card: [widthPx] wide, its own height, [bottomPx] above the bottom edge. */
    fun place(widthPx: Int, bottomPx: Int) {
        val p = window.attributes
        p.width = widthPx
        p.height = LayoutParams.WRAP_CONTENT
        p.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        p.y = bottomPx
        window.attributes = p
    }

    /**
     * Not focusable: the app (or the scrim) keeps focus. [watchOutside]: a touch anywhere else closes it. Typing:
     * focusable, and only then for the keyboard.
     */
    fun setInput(typing: Boolean, watchOutside: Boolean) {
        var flags = LayoutParams.FLAG_NOT_TOUCH_MODAL
        if (!typing) flags = flags or LayoutParams.FLAG_NOT_FOCUSABLE
        if (watchOutside) flags = flags or LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        val mask = LayoutParams.FLAG_NOT_TOUCH_MODAL or LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            LayoutParams.FLAG_NOT_TOUCHABLE
        window.setFlags(flags, mask)
        @Suppress("DEPRECATION")
        window.setSoftInputMode(
            if (typing) LayoutParams.SOFT_INPUT_ADJUST_RESIZE or LayoutParams.SOFT_INPUT_STATE_VISIBLE else LayoutParams.SOFT_INPUT_ADJUST_NOTHING,
        )
    }

    /** While it fades out: touches go to what is below. */
    fun setTouchable(touchable: Boolean) {
        if (touchable) window.clearFlags(LayoutParams.FLAG_NOT_TOUCHABLE) else window.addFlags(LayoutParams.FLAG_NOT_TOUCHABLE)
    }

    /**
     * The glass: a rounded [tint] (ARGB) and, when [blurPx] > 0 and the compositor allows it, the blurred view of what
     * is behind inside the same rounded outline.
     */
    fun setGlass(tint: Int, cornerPx: Float, blurPx: Int) {
        window.setBackgroundDrawable(GradientDrawable().apply {
            cornerRadius = cornerPx
            setColor(tint)
        })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window.setBackgroundBlurRadius(blurPx)
    }

    /** True when the compositor can blur across windows now (off on some devices, with battery saver, in multi-window). */
    fun blurEnabled(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && window.windowManager.isCrossWindowBlurEnabled

    fun show(): Boolean {
        if (attached) return true
        return try {
            dialog.show()
            attached = true
            owner.resume()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val listener = Consumer<Boolean> { enabled -> onBlurChanged?.invoke(enabled) }
                window.windowManager.addCrossWindowBlurEnabledListener(listener)
                blurListener = listener
            }
            true
        } catch (e: RuntimeException) { // BadTokenException, SecurityException
            owner.destroy()
            false
        }
    }

    fun remove() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            blurListener?.let { runCatching { window.windowManager.removeCrossWindowBlurEnabledListener(it) } }
        }
        blurListener = null
        owner.destroy()
        if (attached) runCatching { dialog.dismiss() }
        attached = false
    }
}
