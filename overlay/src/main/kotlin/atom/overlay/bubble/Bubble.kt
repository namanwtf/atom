package atom.overlay.bubble

import android.annotation.SuppressLint
import android.content.Intent
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager.LayoutParams
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import atom.overlay.OverlayPrefs
import atom.overlay.OverlayRuntime
import atom.overlay.R
import atom.overlay.panel.MiniChat
import atom.overlay.window.OverlayWindow
import atom.overlay.window.Windows
import atom.ui.feature.HostRun
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The one floating bubble. Exists only while switched on, permitted and not hidden; idle it is a single static
 * window with no timers, no animation and no recomposition. Main thread only.
 */
internal object Bubble {
    private var window: OverlayWindow? = null
    private var dragLayer: OverlayWindow? = null
    private var menuWindow: OverlayWindow? = null
    private var menu: MenuState? = null
    private var drag: DragState? = null
    private var removeConfigListener: (() -> Unit)? = null
    private var serviceRunning = false

    private var hiddenUntilAppOpen = false
    private var panelOpen = false
    private var passThrough = false

    private var mood by mutableStateOf(BubbleMood.Idle)
    private var pressed by mutableStateOf(false)

    private val prefs get() = OverlayRuntime.prefs

    val isShowing: Boolean get() = window != null

    val wantsService: Boolean get() = OverlayRuntime.ready && prefs.bubbleEnabled.value && Windows.canDrawOverlays()

    fun setEnabled(on: Boolean) {
        prefs.setBubbleEnabled(on)
        if (on) hiddenUntilAppOpen = false
        sync()
    }

    /** Re-evaluates whether the bubble should exist. Cheap; call after any relevant change. */
    fun sync() {
        if (!OverlayRuntime.ready) return
        val permitted = Windows.canDrawOverlays()
        val enabled = prefs.bubbleEnabled.value
        if (enabled && permitted && !hiddenUntilAppOpen) show() else removeAll()
        setService(enabled && permitted)
    }

    fun onAppOpened() {
        hiddenUntilAppOpen = false
        sync()
    }

    fun resetPosition() {
        prefs.dock = null
        window?.let {
            place(it)
            it.update()
        }
    }

    fun onRuns(runs: List<HostRun>) {
        val next = BubbleReducer.mood(runs, prefs.bubbleConversation)
        if (next != mood) mood = next
    }

    /** The panel covers the bubble while open; the bubble keeps its place and simply reappears. */
    fun onPanel(open: Boolean) {
        panelOpen = open
        window?.setHidden(open)
    }

    /** While the agent works the screen, the bubble lets its taps through and fades back. */
    fun setPassThrough(on: Boolean) {
        if (passThrough == on) return
        passThrough = on
        val w = window ?: return
        applyPassThrough(w)
        w.update()
    }

    private fun applyPassThrough(w: OverlayWindow) {
        val p = w.params
        p.flags = if (passThrough) p.flags or LayoutParams.FLAG_NOT_TOUCHABLE else p.flags and LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        p.alpha = if (passThrough) 0.55f else 1f
    }

    private fun show() {
        if (window != null) return
        val slot = Windows.appOverlay() ?: return
        val density = slot.context.resources.displayMetrics.density
        val size = (BUBBLE_WINDOW.value * density).roundToInt()
        val params = OverlayWindow.params(slot.type, size, size, LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        val w = OverlayWindow(slot.context, params) { BubbleContent(mood, pressed) }
        w.view.contentDescription = slot.context.getString(R.string.overlay_bubble_description)
        w.view.setOnClickListener { MiniChat.open() }
        w.view.setOnLongClickListener {
            openMenu()
            true
        }
        w.view.touchInterceptor = Touch(w)
        // Resting at the screen edge, the bubble would otherwise lose drags to the back gesture.
        w.view.systemGestureExclusionRects = listOf(android.graphics.Rect(0, 0, size, size))
        place(w)
        applyPassThrough(w)
        if (!w.show()) return
        window = w
        if (panelOpen) w.setHidden(true)
        removeConfigListener = Windows.onConfigurationChanged {
            window?.let {
                closeMenu()
                place(it)
                it.update()
            }
        }
    }

    private fun removeAll() {
        closeMenu()
        dragLayer?.remove()
        dragLayer = null
        drag = null
        window?.remove()
        window = null
        removeConfigListener?.invoke()
        removeConfigListener = null
    }

    private fun setService(on: Boolean) {
        if (on == serviceRunning) return
        val app = OverlayRuntime.app
        val intent = Intent(app, BubbleService::class.java)
        serviceRunning = if (on) {
            runCatching { app.startService(intent) }.isSuccess
        } else {
            runCatching { app.stopService(intent) }
            false
        }
    }

    // ------------------------------------------------------------------ Geometry

    private class Metrics(val area: Area, val size: Float, val pad: Float, val density: Float) {
        val rest: Range get() = BubbleGeometry.range(area, size, -pad + 6f * density)
        val free: Range get() = BubbleGeometry.range(Area(area.width, area.height), size, -pad)
    }

    private fun metrics(w: OverlayWindow): Metrics {
        val ctx = w.view.context
        val density = ctx.resources.displayMetrics.density
        val size = w.params.width.toFloat()
        return Metrics(Windows.area(ctx), size, (size - ORB.value * density) / 2f, density)
    }

    private fun place(w: OverlayWindow) {
        val m = metrics(w)
        val p = BubbleGeometry.place(prefs.dock ?: Docked(Side.Right, OverlayPrefs.DEFAULT_FRACTION), m.rest)
        w.params.x = p.x.roundToInt()
        w.params.y = p.y.roundToInt()
    }

    private fun centerOf(w: OverlayWindow): Pt {
        val half = w.params.width / 2f
        return Pt(w.params.x + half, w.params.y + half)
    }

    // ------------------------------------------------------------------ Drag

    private fun beginDrag(w: OverlayWindow) {
        val slot = Windows.appOverlay() ?: return
        val m = metrics(w)
        val target = DismissMath.target(m.area, lift = 84f * m.density)
        val state = DragState(target).apply { pos = Offset(w.params.x.toFloat(), w.params.y.toFloat()) }
        state.onReady = { if (drag === state) w.view.alpha = 0f }
        val params = OverlayWindow.params(slot.type, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, OverlayWindow.PASS_THROUGH)
        val layer = OverlayWindow(slot.context, params) { DragLayer(state, mood) }
        if (!layer.show()) return
        drag = state
        dragLayer = layer
    }

    private fun moveDrag(w: OverlayWindow, x: Float, y: Float) {
        val state = drag ?: return
        val m = metrics(w)
        val p = BubbleGeometry.clamp(Pt(x, y), m.free)
        val center = Pt(p.x + m.size / 2f, p.y + m.size / 2f)
        val wasCaptured = state.captured
        state.pos = Offset(p.x, p.y)
        state.proximity = DismissMath.proximity(center, state.target, reach = 280f * m.density)
        state.captured = DismissMath.captured(center, state.target, radius = 72f * m.density, wasCaptured = wasCaptured)
        if (state.captured != wasCaptured) {
            w.view.performHapticFeedback(if (state.captured) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    private fun endDrag(w: OverlayWindow, vx: Float, vy: Float) {
        val state = drag ?: run {
            w.view.alpha = 1f
            return
        }
        if (state.captured) {
            state.onDismissed = {
                hiddenUntilAppOpen = true
                OverlayRuntime.main.post { sync() }
            }
            state.dismissing = true
            return
        }
        val m = metrics(w)
        val rest = m.rest
        val docked = BubbleGeometry.snap(Pt(state.pos.x, state.pos.y), vx, vy, rest, flingVelocity = 900f * m.density)
        prefs.dock = docked
        val target = BubbleGeometry.place(docked, rest)
        state.onSettled = {
            w.params.x = target.x.roundToInt()
            w.params.y = target.y.roundToInt()
            w.update()
            w.view.alpha = 1f
            val layer = dragLayer
            w.view.postOnAnimation {
                if (dragLayer === layer) {
                    layer?.remove()
                    dragLayer = null
                    drag = null
                }
            }
        }
        state.settleTo = Offset(target.x, target.y)
    }

    // ------------------------------------------------------------------ Hold menu

    private fun openMenu() {
        val w = window ?: return
        if (menuWindow != null) return
        val slot = Windows.appOverlay() ?: return
        val m = metrics(w)
        val center = centerOf(w)
        val side = if (center.x < m.area.width / 2f) Side.Left else Side.Right
        val centers = HoldMenuLayout.place(
            center,
            side,
            count = MenuAction.entries.size,
            radius = 88f * m.density,
            button = MENU_BUTTON.value * m.density,
            area = m.area.copy(
                left = m.area.left + 8f * m.density,
                top = m.area.top + 8f * m.density,
                right = m.area.right + 8f * m.density,
                bottom = m.area.bottom + 8f * m.density,
            ),
        )
        val state = MenuState(centers)
        val params = OverlayWindow.params(slot.type, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, LayoutParams.FLAG_NOT_FOCUSABLE)
        val mw = OverlayWindow(slot.context, params, onBack = {
            closeMenu()
            true
        }) { HoldMenu(state, onAction = ::onMenuAction, onDismiss = ::closeMenu) }
        if (!mw.show()) return
        menu = state
        menuWindow = mw
    }

    private fun closeMenu() {
        menuWindow?.remove()
        menuWindow = null
        menu = null
    }

    private fun onMenuAction(action: MenuAction) {
        closeMenu()
        when (action) {
            MenuAction.Hide -> {
                hiddenUntilAppOpen = true
                sync()
            }
            MenuAction.OpenChat -> MiniChat.open()
            MenuAction.Talk -> MiniChat.open(listen = true)
        }
    }

    private fun hoverIndex(x: Float, y: Float): Int {
        val state = menu ?: return -1
        val reach = (MENU_BUTTON.value * 0.8f) * (window?.view?.resources?.displayMetrics?.density ?: 1f)
        return state.centers.indexOfFirst { hypot(it.x - x, it.y - y) <= reach }
    }

    // ------------------------------------------------------------------ Touch

    /** Raw-coordinate gesture handling: tap opens the chat, hold opens the menu (slide onto a button and lift), drag moves. */
    private class Touch(private val w: OverlayWindow) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(w.view.context).scaledTouchSlop.toFloat()
        private var downX = 0f
        private var downY = 0f
        private var startX = 0f
        private var startY = 0f
        private var dragging = false
        private var held = false
        private var velocity: VelocityTracker? = null

        private val longPress = Runnable {
            if (!dragging) {
                held = true
                pressed = false
                w.view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                openMenu()
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = w.params.x.toFloat()
                    startY = w.params.y.toFloat()
                    dragging = false
                    held = false
                    velocity?.recycle()
                    velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                    pressed = true
                    v.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    velocity?.addMovement(e)
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && !held && hypot(dx, dy) > slop) {
                        dragging = true
                        pressed = false
                        v.removeCallbacks(longPress)
                        beginDrag(w)
                    }
                    when {
                        dragging -> moveDrag(w, startX + dx, startY + dy)
                        held -> {
                            val i = hoverIndex(e.rawX, e.rawY)
                            val state = menu
                            if (state != null && state.hover != i) {
                                if (i >= 0) v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                state.hover = i
                            }
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    pressed = false
                    val tracker = velocity
                    tracker?.computeCurrentVelocity(1000)
                    when {
                        dragging -> endDrag(w, tracker?.xVelocity ?: 0f, tracker?.yVelocity ?: 0f)
                        held -> {
                            val i = hoverIndex(e.rawX, e.rawY)
                            if (i >= 0) onMenuAction(MenuAction.entries[i])
                        }
                        else -> {
                            v.playSoundEffect(android.view.SoundEffectConstants.CLICK)
                            MiniChat.open()
                        }
                    }
                    release()
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPress)
                    pressed = false
                    if (dragging) endDrag(w, 0f, 0f)
                    release()
                }
            }
            return true
        }

        private fun release() {
            velocity?.recycle()
            velocity = null
            dragging = false
        }
    }
}
