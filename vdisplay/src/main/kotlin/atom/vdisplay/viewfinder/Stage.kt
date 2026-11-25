package atom.vdisplay.viewfinder

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import atom.ui.feature.Atom
import atom.ui.feature.RunPhase
import atom.ui.theme.AtomTheme
import atom.ui.theme.ThemeMode
import atom.vdisplay.present.Presenter
import atom.vdisplay.route.DisplayRouter
import atom.vdisplay.route.LeaseState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The expanded stage for the hidden display: live preview, Take over / Continue, Fullscreen, Present to main screen,
 * Collapse stage. A floating overlay window when Atom may draw over other apps, otherwise [StageActivity]. Exists
 * only while open, and closes itself when the hidden display goes away. Main thread only.
 */
internal object Stage {
    var open by mutableStateOf(false)
        private set
    var fullscreen by mutableStateOf(false)
        private set
    var controlling by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set

    private var conversationId: String? = null
    private var window: StageWindow? = null
    private var activityClose: (() -> Unit)? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watch: Job? = null

    fun show(context: Context, conversationId: String) {
        this.conversationId = conversationId
        if (open) return
        open = true
        fullscreen = false
        watch = scope.launch {
            DisplayRouter.state.first { s -> !(s is LeaseState.Active && !s.lease.main) }
            collapse()
        }
        val app = context.applicationContext
        if (Settings.canDrawOverlays(app)) {
            window = StageWindow(app).also { it.show() }
        } else {
            app.startActivity(Intent(app, StageActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    internal fun attachActivity(close: () -> Unit) {
        activityClose = close
    }

    internal fun detachActivity() {
        activityClose = null
        if (window == null) collapse()
    }

    fun toggleFullscreen() {
        fullscreen = !fullscreen
        window?.applyMode(fullscreen)
    }

    private var controlWatch: Job? = null

    /** "Take over": the run pauses and the user's touches go to the hidden display. */
    fun takeOver() {
        val id = conversationId ?: return
        runCatching { Atom.host.pause(id) }
        controlling = true
        // Resumed from elsewhere (the capsule's Resume): the agent has the display again.
        controlWatch?.cancel()
        controlWatch = scope.launch {
            val host = runCatching { Atom.host }.getOrNull() ?: return@launch
            var paused = false
            host.runs.first { runs ->
                val phase = runs.firstOrNull { it.conversationId == id }?.phase
                if (phase == RunPhase.Paused) paused = true
                phase == null || !phase.active || (paused && phase != RunPhase.Paused)
            }
            controlling = false
        }
    }

    /** "Continue": hand the display back to the agent. */
    fun handBack() {
        val id = conversationId
        controlWatch?.cancel()
        controlling = false
        if (id != null) runCatching { Atom.host.resume(id) }
    }

    fun present() {
        val lease = DisplayRouter.current?.takeIf { !it.main } ?: return
        busy = true
        scope.launch {
            withContext(Dispatchers.Default) { Presenter.present(lease) }
            busy = false
            if (controlling) handBack()
            collapse()
        }
    }

    /** Closes the stage. A take-over in progress stays paused: the capsule shows Resume. */
    fun collapse() {
        if (!open) return
        open = false
        fullscreen = false
        watch?.cancel()
        watch = null
        window?.remove()
        window = null
        activityClose?.invoke()
        activityClose = null
    }

    val runId: String? get() = conversationId
}

/** The stage as a WindowManager overlay with its own minimal lifecycle (resumed while attached). */
private class StageWindow(private val context: Context) : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    private val wm = context.getSystemService(WindowManager::class.java)
    private val root: FrameLayout
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.CENTER
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
    }
    private var attached = false

    init {
        saved.performAttach()
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
        val compose = ComposeView(context).apply {
            setContent { AtomTheme(ThemeMode.Dark) { StageContent() } }
        }
        root = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                        if (Stage.fullscreen) Stage.toggleFullscreen() else Stage.collapse()
                    }
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setViewTreeLifecycleOwner(this@StageWindow)
            setViewTreeSavedStateRegistryOwner(this@StageWindow)
            addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
    }

    fun show() {
        if (attached) return
        try {
            wm.addView(root, params)
            attached = true
            registry.currentState = Lifecycle.State.RESUMED
        } catch (e: RuntimeException) {
            registry.currentState = Lifecycle.State.DESTROYED
            Stage.collapse()
        }
    }

    fun applyMode(fullscreen: Boolean) {
        params.height = if (fullscreen) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT
        params.layoutInDisplayCutoutMode = if (fullscreen) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        }
        if (attached) runCatching { wm.updateViewLayout(root, params) }
    }

    fun remove() {
        registry.currentState = Lifecycle.State.DESTROYED
        if (!attached) return
        attached = false
        root.visibility = View.GONE
        runCatching { wm.removeViewImmediate(root) }
    }
}
