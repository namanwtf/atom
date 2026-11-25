package atom.overlay.bubble

import android.annotation.SuppressLint
import android.content.Intent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager.LayoutParams
import android.widget.Toast
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
import atom.ui.feature.RunPhase
import atom.ui.feature.StepState
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The one floating bubble. Exists only while switched on and permitted; idle it is a single static window with no
 * timers, no animation and no recomposition. It rests exactly where the user dropped it: the position is saved
 * when a drag ends and restored on start, and nothing else (the panel, the menu, a run) ever moves it.
 * Main thread only.
 */
internal object Bubble {
    private var window: OverlayWindow? = null
    private var pillWindow: OverlayWindow? = null
    private var dragLayer: OverlayWindow? = null
    private var menuWindow: OverlayWindow? = null
    private var menu: MenuState? = null
    private var menuOrigin = Pt(0f, 0f)
    private var menuCenters: List<Pt> = emptyList()
    private var drag: DragState? = null

    /** The keyboard's top edge when the drag began (measured once, not per move). */
    private var dragKeyboardTop: Int? = null
    private var removeConfigListener: (() -> Unit)? = null
    private var serviceRunning = false

    private var panelOpen = false
    private var passThrough = false

    private var mood by mutableStateOf(BubbleMood.Idle)
    private var pressed by mutableStateOf(false)
    private var status by mutableStateOf<String?>(null)
    private var statusPaused by mutableStateOf(false)
    private var lastRuns: List<HostRun> = emptyList()

    private val prefs get() = OverlayRuntime.prefs

    val isShowing: Boolean get() = window != null

    val wantsService: Boolean get() = OverlayRuntime.ready && prefs.bubbleEnabled.value && Windows.canDrawOverlays()

    fun setEnabled(on: Boolean) {
        prefs.setBubbleEnabled(on)
        sync()
    }

    /** Re-evaluates whether the bubble should exist. Cheap; call after any relevant change. */
    fun sync() {
        if (!OverlayRuntime.ready) return
        val permitted = Windows.canDrawOverlays()
        val enabled = prefs.bubbleEnabled.value
        if (enabled && permitted) show() else removeAll()
        setService(enabled && permitted)
    }

    fun onAppOpened() = sync()

    fun resetPosition() {
        prefs.dock = null
        window?.let {
            place(it)
            it.update()
            updatePill()
        }
    }

    fun onRuns(runs: List<HostRun>) {
        lastRuns = runs
        val run = BubbleReducer.pick(runs, prefs.bubbleConversation)
        val next = run?.let(BubbleReducer::moodOf) ?: BubbleMood.Idle
        if (next != mood) mood = next
        val text = run?.let(::statusOf)
        if (text != status) status = text
        statusPaused = run?.phase == RunPhase.Paused
        updatePill()
    }

    /** The panel covers the bubble while open; the bubble keeps its place and simply reappears. */
    fun onPanel(open: Boolean) {
        panelOpen = open
        window?.setHidden(open)
        updatePill()
    }

    /** While the agent works the screen, the bubble lets its taps through and fades back. */
    fun setPassThrough(on: Boolean) {
        if (passThrough == on) return
        passThrough = on
        val w = window ?: return
        applyPassThrough(w)
        w.update()
        updatePill()
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
        params.title = WINDOW_TITLE
        val w = OverlayWindow(slot.context, params) { BubbleContent(mood, pressed) }
        w.view.contentDescription = slot.context.getString(R.string.overlay_bubble_description)
        w.view.setOnClickListener { MiniChat.open() }
        w.view.setOnLongClickListener {
            toggleMenu()
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
                removePill()
                updatePill()
            }
        }
        updatePill()
    }

    private fun removeAll() {
        closeMenu()
        removePill()
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

    /** Hide, from the menu or the dismiss target: the bubble goes away and the switch turns off. */
    private fun hide() {
        setEnabled(false)
        runCatching { Toast.makeText(OverlayRuntime.app, R.string.overlay_bubble_off, Toast.LENGTH_LONG).show() }
    }

    // ------------------------------------------------------------------ Geometry

    private class Metrics(val area: Area, val size: Float, val pad: Float, val density: Float, val keyboardTop: Int?) {
        /** Resting spots: the orbit box [EDGE_DP] inside the system bars. */
        val rest: Range get() = BubbleGeometry.range(area, size, -pad + EDGE_DP * density)

        /** While dragging: [EDGE_DP] inside the screen, above the navigation bar or the keyboard. */
        val free: Range
            get() {
                val keyboard = keyboardTop?.let { max(0f, area.height - it) } ?: 0f
                return BubbleGeometry.range(Area(area.width, area.height, bottom = max(area.bottom, keyboard)), size, -pad + EDGE_DP * density)
            }
    }

    private fun metrics(w: OverlayWindow, keyboardTop: Int? = null): Metrics {
        val ctx = w.view.context
        val density = ctx.resources.displayMetrics.density
        val size = w.params.width.toFloat()
        return Metrics(Windows.area(ctx), size, (size - ORBIT.value * density) / 2f, density, keyboardTop)
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

    // ------------------------------------------------------------------ Status line

    private fun statusOf(run: HostRun): String {
        val app = OverlayRuntime.app
        val step = run.steps.lastOrNull { it.state == StepState.Running }?.let { it.summary.ifBlank { it.tool } }
        return when {
            run.pending != null || run.phase == RunPhase.WaitingForApproval -> app.getString(R.string.overlay_phase_approval)
            run.phase == RunPhase.Paused -> app.getString(R.string.overlay_phase_paused)
            run.phase == RunPhase.WaitingForControl -> app.getString(R.string.overlay_phase_waiting_control)
            step != null -> step
            run.text.isEmpty() -> app.getString(R.string.overlay_thinking)
            else -> app.getString(R.string.overlay_phase_working)
        }
    }

    /** Text for the pill: the run status, plus the hold hint until the user has opened the menu once. */
    private fun pillText(): String? {
        val s = status ?: return null
        return if (prefs.menuDiscovered) s else OverlayRuntime.app.getString(R.string.overlay_status_with_hint, s)
    }

    private fun updatePill() {
        val w = window
        val visible = w != null && status != null && !panelOpen && !passThrough && drag == null && menuWindow == null
        if (!visible) {
            removePill()
            return
        }
        val bubble = w ?: return
        val existing = pillWindow
        if (existing != null) {
            if (placePill(existing, bubble)) existing.update()
            return
        }
        val slot = Windows.appOverlay() ?: return
        val params = OverlayWindow.params(
            slot.type,
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        )
        params.title = PILL_TITLE
        val pw = OverlayWindow(slot.context, params) {
            val text = pillText()
            if (text != null) StatusPill(text, statusPaused)
        }
        pw.view.setOnClickListener { MiniChat.open() }
        // The window wraps the pill; once its height is known, centre it on the bubble.
        pw.view.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) {
                val b = window
                if (pillWindow === pw && b != null && placePill(pw, b)) pw.update()
            }
        }
        placePill(pw, bubble)
        if (pw.show()) pillWindow = pw
    }

    /** Beside the face, toward the middle of the screen, [PILL_GAP_DP] from the face; it grows away from the bubble. */
    private fun placePill(pw: OverlayWindow, bubble: OverlayWindow): Boolean {
        val m = metrics(bubble)
        val faceInset = (m.size - FACE.value * m.density) / 2f
        val gap = PILL_GAP_DP * m.density
        val onLeft = centerOf(bubble).x < m.area.width / 2f
        val p = pw.params
        val gravity = Gravity.TOP or (if (onLeft) Gravity.START else Gravity.END)
        val x = if (onLeft) {
            (bubble.params.x + m.size - faceInset + gap).roundToInt()
        } else {
            (m.area.width - (bubble.params.x + faceInset) + gap).roundToInt()
        }
        val pillHeight = pw.view.height.takeIf { it > 0 } ?: (PILL_HEIGHT_DP * m.density).roundToInt()
        val y = bubble.params.y + (bubble.params.height - pillHeight) / 2
        val changed = p.x != x || p.y != y || p.gravity != gravity
        p.gravity = gravity
        p.x = x
        p.y = y
        return changed
    }

    private fun removePill() {
        pillWindow?.remove()
        pillWindow = null
    }

    // ------------------------------------------------------------------ Drag

    private fun beginDrag(w: OverlayWindow) {
        closeMenu()
        val slot = Windows.appOverlay() ?: return
        val m = metrics(w)
        val target = DismissMath.target(m.area, lift = TARGET_LIFT_DP * m.density, fallbackInset = NAV_FALLBACK_DP * m.density)
        dragKeyboardTop = Windows.keyboardProbe()?.top
        val state = DragState(target).apply { pos = Offset(w.params.x.toFloat(), w.params.y.toFloat()) }
        state.onReady = { if (drag === state) w.view.alpha = 0f }
        val params = OverlayWindow.params(slot.type, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, OverlayWindow.PASS_THROUGH)
        params.title = DRAG_TITLE
        val layer = OverlayWindow(slot.context, params) { DragLayer(state, mood) }
        if (!layer.show()) return
        drag = state
        dragLayer = layer
        removePill()
    }

    private fun moveDrag(w: OverlayWindow, x: Float, y: Float) {
        val state = drag ?: return
        val m = metrics(w, dragKeyboardTop)
        val p = BubbleGeometry.clamp(Pt(x, y), m.free)
        val center = Pt(p.x + m.size / 2f, p.y + m.size / 2f)
        val wasCaptured = state.captured
        state.pos = Offset(p.x, p.y)
        state.closeness = DismissMath.closeness(center, state.target, reach = CLOSENESS_REACH_DP * m.density)
        state.captured = DismissMath.captured(center, state.target, radius = HIT_RADIUS_DP * m.density, wasCaptured = wasCaptured)
        if (state.captured != wasCaptured) {
            w.view.performHapticFeedback(if (state.captured) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    /**
     * A lift settles on the nearer edge (and is saved), or hides the bubble when dropped on the X. A drag the system
     * cancelled (shade pulled, call screen, rotation) never hides it: the bubble goes back to its saved spot.
     */
    private fun endDrag(w: OverlayWindow, vx: Float, vy: Float, cancelled: Boolean = false) {
        val state = drag ?: run {
            w.view.alpha = 1f
            return
        }
        if (DismissMath.dropHides(state.captured, cancelled)) {
            state.onDismissed = { OverlayRuntime.main.post { hide() } }
            state.dismissing = true
            return
        }
        state.captured = false
        val m = metrics(w)
        val rest = m.rest
        val docked = if (cancelled) {
            prefs.dock ?: Docked(Side.Right, OverlayPrefs.DEFAULT_FRACTION)
        } else {
            BubbleGeometry.snap(Pt(state.pos.x, state.pos.y), vx, vy, rest, flingVelocity = 900f * m.density).also {
                // Saved the moment the drag ends: this is where the bubble stays until the user moves it again.
                prefs.dock = it
            }
        }
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
                    updatePill()
                }
            }
        }
        state.settleTo = Offset(target.x, target.y)
    }

    // ------------------------------------------------------------------ Hold menu

    private fun toggleMenu() = if (menuWindow != null) closeMenu() else openMenu()

    /**
     * The menu window is placed first (centred on the bubble, clamped to the display); the arc is then laid out
     * from the bubble's real centre, so it stays anchored to the bubble wherever the window had to move.
     */
    private fun openMenu() {
        val w = window ?: return
        if (menuWindow != null) return
        val slot = Windows.appOverlay() ?: return
        val m = metrics(w)
        val center = centerOf(w)
        val windowPx = MENU_WINDOW.value * m.density
        val origin = HoldMenuLayout.window(center, windowPx, m.area.width, m.area.height)
        val side = if (center.x < m.area.width / 2f) Side.Left else Side.Right
        val margin = 8f * m.density
        val centers = HoldMenuLayout.place(
            center,
            side,
            count = MenuAction.entries.size,
            radius = MENU_RADIUS.value * m.density,
            button = MENU_BUTTON.value * m.density,
            area = m.area.copy(
                left = m.area.left + margin,
                top = m.area.top + margin,
                right = m.area.right + margin,
                bottom = m.area.bottom + margin,
            ),
        )
        val state = MenuState(
            bubble = Offset(center.x - origin.x, center.y - origin.y),
            centers = centers.map { Offset(it.x - origin.x, it.y - origin.y) },
        )
        val size = windowPx.roundToInt()
        val params = OverlayWindow.params(
            slot.type,
            size,
            size,
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        ).apply {
            x = origin.x.roundToInt()
            y = origin.y.roundToInt()
            title = MENU_TITLE
        }
        val mw = OverlayWindow(slot.context, params, onBack = {
            closeMenu()
            true
        }) { HoldMenu(state, onAction = ::onMenuAction, onDismiss = ::closeMenu) }
        mw.view.onOutsideTouch = ::closeMenu
        if (!mw.show()) return
        menu = state
        menuOrigin = origin
        menuCenters = centers
        menuWindow = mw
        prefs.menuDiscovered = true
        removePill()
    }

    private fun closeMenu() {
        if (menuWindow == null) return
        menuWindow?.remove()
        menuWindow = null
        menu = null
        menuCenters = emptyList()
        updatePill()
    }

    private fun onMenuAction(action: MenuAction) {
        closeMenu()
        when (action) {
            MenuAction.Hide -> hide()
            MenuAction.OpenChat -> MiniChat.open()
            MenuAction.Talk -> MiniChat.open(listen = true)
        }
    }

    /** Which button a finger still held after the long-press is over (screen px), for slide-and-lift selection. */
    private fun hoverIndex(x: Float, y: Float): Int {
        if (menu == null) return -1
        val reach = (MENU_BUTTON.value * 0.8f) * (window?.view?.resources?.displayMetrics?.density ?: 1f)
        return menuCenters.indexOfFirst { hypot(it.x - x, it.y - y) <= reach }
    }

    // ------------------------------------------------------------------ Touch

    /** Raw-coordinate gesture handling: tap opens the chat, hold toggles the menu (slide onto a button and lift), drag moves. */
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
                toggleMenu()
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
                    if (!dragging && (!held || menu == null) && hypot(dx, dy) > slop) {
                        dragging = true
                        held = false
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
                            closeMenu()
                            MiniChat.open()
                        }
                    }
                    release()
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPress)
                    pressed = false
                    if (dragging) endDrag(w, 0f, 0f, cancelled = true)
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

    /** Orbit box distance from the screen edges, at rest and while dragging. */
    private const val EDGE_DP = 6f

    /** The X target sits this far above the navigation bar; a missing inset counts as [NAV_FALLBACK_DP]. */
    private const val TARGET_LIFT_DP = 120f
    private const val NAV_FALLBACK_DP = 48f

    /** Dropping within this distance of the target centre hides the bubble. */
    private const val HIT_RADIUS_DP = 96f

    /** The beam and halo start gathering strength this far from the target. */
    private const val CLOSENESS_REACH_DP = 460f

    private const val PILL_GAP_DP = 6f

    /** A one-line pill, until the real height is measured. */
    private const val PILL_HEIGHT_DP = 34f

    /** Window titles, so the windows can be told apart in `dumpsys window`. */
    const val WINDOW_TITLE = "AtomBubble"
    private const val PILL_TITLE = "AtomBubbleStatus"
    private const val MENU_TITLE = "AtomBubbleMenu"
    private const val DRAG_TITLE = "AtomBubbleDrag"
}
