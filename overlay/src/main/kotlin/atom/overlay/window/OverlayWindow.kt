package atom.overlay.window

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.os.Build
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import atom.ui.theme.AtomTheme
import atom.ui.theme.ThemeMode

/**
 * Lifecycle for Compose content that lives outside an activity. RESUMED while shown; CREATED while hidden, which
 * pauses Compose's frame clock so a hidden window costs nothing.
 */
internal class WindowOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    init {
        saved.performAttach()
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    fun resume() = move(Lifecycle.State.RESUMED)
    fun pause() = move(Lifecycle.State.CREATED)
    fun destroy() = move(Lifecycle.State.DESTROYED)

    private fun move(state: Lifecycle.State) {
        if (registry.currentState != Lifecycle.State.DESTROYED) registry.currentState = state
    }

    /** Hosts [content] in a view tree that Compose can attach to. */
    fun root(context: Context, onBack: (() -> Boolean)? = null, content: @Composable () -> Unit): RootLayout {
        val compose = ComposeView(context).apply {
            setContent {
                CompositionLocalProvider(LocalUriHandler provides NewTaskUriHandler(context)) {
                    AtomTheme(ThemeMode.Dark) { content() }
                }
            }
        }
        return RootLayout(context, onBack).apply {
            setViewTreeLifecycleOwner(this@WindowOwner)
            setViewTreeSavedStateRegistryOwner(this@WindowOwner)
            addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
    }
}

/** Root of every overlay window; routes Back to the owner. */
internal class RootLayout(context: Context, private val onBack: (() -> Boolean)?) : FrameLayout(context) {
    var touchInterceptor: OnTouchListener? = null

    /** Called for a touch that starts outside the window (needs FLAG_WATCH_OUTSIDE_TOUCH). */
    var onOutsideTouch: (() -> Unit)? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && onBack != null) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) return onBack.invoke() || super.dispatchKeyEvent(event)
            if (event.action == KeyEvent.ACTION_DOWN) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_OUTSIDE) {
            onOutsideTouch?.let {
                it()
                return true
            }
        }
        val interceptor = touchInterceptor ?: return super.dispatchTouchEvent(ev)
        return interceptor.onTouch(this, ev)
    }
}

/** Overlay contexts are not activities, so links must open in a new task. */
private class NewTaskUriHandler(private val context: Context) : UriHandler {
    override fun openUri(uri: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
        } catch (e: SecurityException) {
        }
    }
}

/**
 * One window of Compose content added straight to the WindowManager. Single use: create, [show], [remove].
 * All calls on the main thread.
 */
internal class OverlayWindow(
    context: Context,
    val params: WindowManager.LayoutParams,
    private val onBack: (() -> Boolean)? = null,
    content: @Composable () -> Unit,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val owner = WindowOwner()
    val view: RootLayout = owner.root(context, onBack, content)

    var attached = false
        private set

    fun show(): Boolean {
        if (attached) return true
        return try {
            wm.addView(view, params)
            attached = true
            owner.resume()
            registerBack()
            true
        } catch (e: RuntimeException) { // BadTokenException, SecurityException, IllegalStateException
            owner.destroy()
            false
        }
    }

    fun update() {
        if (attached) runCatching { wm.updateViewLayout(view, params) }
    }

    /** Keeps the window but hides it and stops its frame clock. */
    fun setHidden(hidden: Boolean) {
        if (!attached) return
        view.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        if (hidden) owner.pause() else owner.resume()
    }

    private var backCallback: Any? = null

    /**
     * The app opts into predictive back, so Back never reaches a window's views as a key event; an overlay window
     * that handles Back registers a callback with its own dispatcher instead (older Android keeps using the key).
     * Through the callback Back is always consumed, so give [onBack] only to windows that always handle it.
     */
    private fun registerBack() {
        val handler = onBack ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val callback = OnBackInvokedCallback { handler() }
        val dispatcher = view.findOnBackInvokedDispatcher()
        if (dispatcher != null) {
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            backCallback = callback
        } else {
            view.post {
                if (!attached) return@post
                view.findOnBackInvokedDispatcher()?.let {
                    it.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
                    backCallback = callback
                }
            }
        }
    }

    private fun unregisterBack() {
        val callback = backCallback ?: return
        backCallback = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && callback is OnBackInvokedCallback) {
            runCatching { view.findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(callback) }
        }
    }

    fun remove() {
        unregisterBack()
        if (!attached) {
            owner.destroy()
            return
        }
        attached = false
        owner.destroy()
        runCatching { wm.removeViewImmediate(view) }
    }

    companion object {
        /** Full-display coordinates: (0, 0) is the top-left of the display, insets are handled by the content. */
        fun params(type: Int, width: Int, height: Int, flags: Int): WindowManager.LayoutParams =
            WindowManager.LayoutParams(width, height, type, flags or BASE_FLAGS, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }

        private const val BASE_FLAGS = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        const val PASS_THROUGH = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        /** Android blocks touches through untrusted overlays more opaque than this, so pass-through windows stay at it. */
        const val MAX_PASS_THROUGH_ALPHA = 0.8f
    }
}
