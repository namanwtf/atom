package atom.overlay.run

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.Gravity
import android.view.WindowManager.LayoutParams
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import atom.overlay.Overlay
import atom.overlay.OverlayRuntime
import atom.overlay.assistant.AssistantController
import atom.overlay.bubble.Bubble
import atom.overlay.bubble.BubbleMood
import atom.overlay.bubble.BubbleReducer
import atom.overlay.hey.HeyAtomOverlay
import atom.overlay.panel.MiniChat
import atom.overlay.ui.Markdown
import atom.overlay.window.OverlayWindow
import atom.overlay.window.Windows
import atom.ui.feature.HostApproval
import atom.ui.feature.HostRun
import atom.ui.feature.RunPhase
import atom.ui.feature.RunSource
import atom.ui.feature.StepState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** What the capsule shows for a run. Kept small so streaming tokens do not recompose it. */
internal data class CapsuleView(
    val conversationId: String,
    val source: RunSource,
    val phase: RunPhase,
    val step: String?,
    val pending: HostApproval?,
    val queued: Int,
    val viewfinder: Boolean,
    val mood: BubbleMood,
    /** A step (tool) is running right now. */
    val toolRunning: Boolean = false,
    /** The final answer is streaming (text, no tool running). */
    val answering: Boolean = false,
)

/** [usedScreen]: the run worked on the background virtual display, so the card offers "Open screen". */
internal data class ResultView(
    val conversationId: String,
    val source: RunSource,
    val ok: Boolean,
    val text: String,
    val usedScreen: Boolean,
)

/**
 * The run capsule and edge glow, shown while any run uses the screen (whatever started it), then the result card.
 * Windows exist only while there is something to show. Main thread only.
 */
internal object RunOverlays {
    private var capsule: OverlayWindow? = null
    private var resultWindow: OverlayWindow? = null
    private val usedDisplay = HashSet<String>()
    private var glow: OverlayWindow? = null
    private val driving = LinkedHashSet<String>()
    private var lastRuns: List<HostRun> = emptyList()
    private var resultTimer: Job? = null

    var view by mutableStateOf<CapsuleView?>(null)
        private set
    var result by mutableStateOf<ResultView?>(null)
        private set
    var expanded by mutableStateOf(false)
    var steering by mutableStateOf(false)
        private set
    var steerText by mutableStateOf("")
    private var glowPaused by mutableStateOf(false)

    /** Conversation the user asked to stop; the capsule reads "Stopping" until the run ends. */
    var stoppingId by mutableStateOf<String?>(null)
        private set

    /** A short-lived status after a control was used ("Continuing", "Additional input received. Continuing"). */
    var notice by mutableStateOf<CapsuleNotice?>(null)
        private set
    private var noticeJob: Job? = null

    private fun flash(next: CapsuleNotice) {
        notice = next
        noticeJob?.cancel()
        noticeJob = OverlayRuntime.scope.launch {
            delay(CapsuleStatuses.NOTICE_MS)
            notice = null
        }
    }

    /** The capsule's status for [v]. */
    fun statusOf(v: CapsuleView, waitingForUnlock: Boolean = false): CapsuleStatus = CapsuleStatuses.of(
        phase = v.phase,
        pending = v.pending != null,
        toolRunning = v.toolRunning,
        answering = v.answering,
        stopping = stoppingId == v.conversationId,
        notice = notice,
        waitingForUnlock = waitingForUnlock,
    )

    private val prefs get() = OverlayRuntime.prefs

    fun markDriving(conversationId: String) {
        driving += conversationId
        refresh()
    }

    fun onRuns(runs: List<HostRun>) {
        lastRuns = runs
        for (run in runs) {
            if (run.phase.active && run.conversationId !in driving && Overlay.hooks.drivesScreen(run)) {
                driving += run.conversationId
                // Fallback for when the app does not call Overlay.clearScreenForAgent before screen tools.
                MiniChat.hideForAgent(run.conversationId)
                AssistantController.hideForAgent(run.conversationId)
                HeyAtomOverlay.hideForAgent(run.conversationId)
            }
        }
        refresh()
    }

    fun onFinished(run: HostRun, presentedElsewhere: Boolean) {
        val drove = driving.remove(run.conversationId)
        // Only this run's own record counts: the display state now may belong to another run, or be lost.
        val onDisplay = usedDisplay.remove(run.conversationId)
        if (drove && !presentedElsewhere && run.phase != RunPhase.Cancelled) {
            showResult(
                ResultView(
                    conversationId = run.conversationId,
                    source = run.source,
                    ok = run.phase == RunPhase.Done,
                    text = (if (run.phase == RunPhase.Done) Markdown.plain(run.text) else run.error.orEmpty()).take(MAX_RESULT_CHARS),
                    usedScreen = onDisplay,
                ),
            )
        }
        refresh()
    }

    fun onWindowSourceChanged() {
        removeCapsule()
        removeResultWindow()
        removeGlow()
        refresh()
    }

    // ------------------------------------------------------------------ Controls

    fun pause() = view?.let { OverlayRuntime.host()?.pause(it.conversationId) }
    fun resume() = view?.let {
        OverlayRuntime.host()?.resume(it.conversationId)
        flash(CapsuleNotice.Continuing)
    }

    fun stop() {
        val v = view ?: return
        // Without a host the stop cannot be delivered, so the capsule must not claim "Stopping".
        val host = OverlayRuntime.host() ?: return
        stoppingId = v.conversationId
        host.stop(v.conversationId)
        expanded = false
        endSteer()
    }

    fun answer(approved: Boolean) {
        if (locked) return
        val pending = view?.pending ?: return
        OverlayRuntime.host()?.answer(pending.id, approved)
    }

    fun beginSteer() {
        if (locked) return
        val v = view
        CapsuleStatuses.steerBlocked(v?.answering == true, v?.toolRunning == true, OverlayRuntime.host() != null)?.let {
            flash(it)
            return
        }
        steering = true
        steerText = ""
        setFocusable(true)
    }

    fun sendSteer() {
        if (locked) {
            endSteer()
            return
        }
        val v = view
        val text = steerText.trim()
        if (v != null && text.isNotEmpty()) {
            val host = OverlayRuntime.host()
            if (host != null) {
                host.steer(v.conversationId, text)
                flash(CapsuleNotice.InputReceived)
            } else {
                flash(CapsuleNotice.InputUnsupported)
            }
        }
        endSteer()
    }

    fun endSteer() {
        if (!steering) return
        steering = false
        steerText = ""
        setFocusable(false)
    }

    fun openResult() {
        val r = result ?: return
        dismissResult()
        if (r.source == RunSource.Bubble && r.conversationId == MiniChat.conversationId) {
            MiniChat.open()
            return
        }
        val ctx = capsule?.view?.context ?: OverlayRuntime.app
        runCatching { ctx.startActivity(Overlay.hooks.appIntent(ctx, r.conversationId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun openApp() {
        val r = result
        val id = r?.conversationId ?: view?.conversationId
        dismissResult()
        val ctx = capsule?.view?.context ?: OverlayRuntime.app
        runCatching { ctx.startActivity(Overlay.hooks.appIntent(ctx, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Shows the screen the run worked on in the background (the stage, or its app on the main display). */
    fun openScreen() {
        val r = result ?: return
        val ctx = resultWindow?.view?.context ?: OverlayRuntime.app
        dismissResult()
        Overlay.viewfinder?.openScreen(ctx, r.conversationId)
    }

    fun copy(context: Context, text: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Atom", text))
    }

    fun dismissResult() {
        resultTimer?.cancel()
        resultTimer = null
        result = null
        refresh()
    }

    /**
     * True while the keyguard is locked. The capsule and the result card may draw over the lock screen
     * (accessibility overlay), so while locked the capsule shows only its phase and Stop (no approval buttons, no
     * steer field, no step text, no viewfinder) and the result card only the outcome (no answer text, no Copy, no
     * buttons that open anything). Watched while either window exists: the screen turning off counts as locked
     * at once, and turning on or the user unlocking re-reads the keyguard.
     */
    var locked by mutableStateOf(false)
        private set
    private var lockReceiver: BroadcastReceiver? = null

    private fun keyguardLocked(): Boolean =
        OverlayRuntime.app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    private fun updateLocked(value: Boolean) {
        if (locked == value) return
        locked = value
        if (value) {
            endSteer()
            expanded = false
        }
    }

    private fun watchLock() {
        if (lockReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> updateLocked(true)
                    Intent.ACTION_USER_PRESENT -> updateLocked(false)
                    else -> updateLocked(keyguardLocked())
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching {
            ContextCompat.registerReceiver(OverlayRuntime.app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }.onSuccess { lockReceiver = receiver }
        updateLocked(keyguardLocked())
    }

    private fun unwatchLock() {
        val receiver = lockReceiver ?: return
        lockReceiver = null
        runCatching { OverlayRuntime.app.unregisterReceiver(receiver) }
    }

    // ------------------------------------------------------------------ State

    private fun showResult(r: ResultView) {
        expanded = false
        endSteer()
        result = r
        watchLock()
        resultTimer?.cancel()
        resultTimer = OverlayRuntime.scope.launch {
            delay(RESULT_MS)
            dismissResult()
        }
    }

    private fun refresh() {
        val active = lastRuns.firstOrNull { it.phase.active && it.conversationId in driving }
        val next = active?.let(::viewOf)
        if (next != null && next.viewfinder) usedDisplay += next.conversationId
        if (next != view) view = next
        if (next == null) {
            expanded = false
            endSteer()
        }
        if (stoppingId != null && stoppingId != next?.conversationId) stoppingId = null
        // Glow first, so a capsule created in the same pass sits above it.
        val glowing = next != null && prefs.edgeGlow.value &&
            (next.phase == RunPhase.Running || next.phase == RunPhase.Starting || next.phase == RunPhase.Paused)
        if (glowing) ensureGlow() else removeGlow()
        glowPaused = next?.phase == RunPhase.Paused
        if (next != null) ensureCapsule() else removeCapsule()
        if (result != null) ensureResultWindow() else removeResultWindow()
        if (next != null || result != null) watchLock() else unwatchLock()
        Bubble.setPassThrough(next != null && next.phase != RunPhase.Paused)
    }

    private fun viewOf(run: HostRun): CapsuleView = CapsuleView(
        conversationId = run.conversationId,
        source = run.source,
        phase = run.phase,
        step = run.steps.lastOrNull { it.state == StepState.Running }?.let { it.summary.ifBlank { it.tool } }
            ?: run.steps.lastOrNull()?.let { it.summary.ifBlank { it.tool } },
        pending = run.pending,
        queued = run.queued.size,
        viewfinder = Overlay.viewfinder?.shows(run) == true,
        mood = BubbleReducer.moodOf(run),
        toolRunning = run.steps.any { it.state == StepState.Running },
        answering = CapsuleStatuses.answerStarted(run.text) && run.steps.none { it.state == StepState.Running },
    )

    /** The run behind the capsule, for the viewfinder slot. */
    fun runFor(conversationId: String): HostRun? = lastRuns.firstOrNull { it.conversationId == conversationId }

    // ------------------------------------------------------------------ Windows

    private fun ensureCapsule() {
        if (capsule != null) return
        val slot = Windows.runOverlay() ?: return
        val ctx = slot.context
        val top = Windows.area(ctx).top + 6f * ctx.resources.displayMetrics.density
        val params = OverlayWindow.params(
            slot.type,
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCH_MODAL,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = top.roundToInt()
            softInputMode = LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        // The capsule takes focus only while steering, so Back reaches it only then; it always handles it.
        val w = OverlayWindow(ctx, params, onBack = {
            when {
                steering -> endSteer()
                else -> expanded = false
            }
            true
        }) { RunCapsule() }
        if (w.show()) capsule = w
    }

    private fun removeCapsule() {
        capsule?.remove()
        capsule = null
    }

    private fun setFocusable(focusable: Boolean) {
        val w = capsule ?: return
        val p = w.params
        p.flags = if (focusable) p.flags and LayoutParams.FLAG_NOT_FOCUSABLE.inv() else p.flags or LayoutParams.FLAG_NOT_FOCUSABLE
        p.softInputMode = if (focusable) LayoutParams.SOFT_INPUT_STATE_VISIBLE else LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        w.update()
    }

    private fun ensureGlow() {
        if (glow != null) return
        val slot = Windows.runOverlay() ?: return
        val params = OverlayWindow.params(slot.type, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, OverlayWindow.PASS_THROUGH).apply {
            alpha = OverlayWindow.MAX_PASS_THROUGH_ALPHA
        }
        val w = OverlayWindow(slot.context, params) { EdgeGlow(glowPaused) }
        if (w.show()) glow = w
    }

    private fun ensureResultWindow() {
        if (resultWindow != null) return
        val slot = Windows.runOverlay() ?: return
        val ctx = slot.context
        val area = Windows.area(ctx)
        val params = OverlayWindow.params(
            slot.type,
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT,
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCH_MODAL,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = area.bottom.roundToInt()
        }
        val w = OverlayWindow(ctx, params) { ResultCard() }
        if (w.show()) resultWindow = w
    }

    private fun removeResultWindow() {
        resultWindow?.remove()
        resultWindow = null
    }

    private fun removeGlow() {
        glow?.remove()
        glow = null
    }

    private const val RESULT_MS = 30_000L
    private const val MAX_RESULT_CHARS = 4_000
}
