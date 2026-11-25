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
    fun resume() = view?.let { OverlayRuntime.host()?.resume(it.conversationId) }

    fun stop() {
        val v = view ?: return
        OverlayRuntime.host()?.stop(v.conversationId)
        expanded = false
        endSteer()
    }

    fun answer(approved: Boolean) {
        val pending = view?.pending ?: return
        OverlayRuntime.host()?.answer(pending.id, approved)
    }

    fun beginSteer() {
        steering = true
        steerText = ""
        setFocusable(true)
    }

    fun sendSteer() {
        val v = view
        val text = steerText.trim()
        if (v != null && text.isNotEmpty()) OverlayRuntime.host()?.steer(v.conversationId, text)
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
        unwatchUnlock()
        refresh()
    }

    /**
     * True while the keyguard is locked. The result card may draw over the lock screen (accessibility overlay), so
     * until the user unlocks it shows only the outcome: no answer text, no Copy, no buttons that open anything.
     */
    var locked by mutableStateOf(false)
        private set
    private var unlockReceiver: BroadcastReceiver? = null

    private fun watchUnlock() {
        val app = OverlayRuntime.app
        locked = app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        if (!locked || unlockReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                locked = false
                unwatchUnlock()
            }
        }
        runCatching {
            ContextCompat.registerReceiver(app, receiver, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED)
        }.onSuccess { unlockReceiver = receiver }
    }

    private fun unwatchUnlock() {
        val receiver = unlockReceiver ?: return
        unlockReceiver = null
        runCatching { OverlayRuntime.app.unregisterReceiver(receiver) }
    }

    // ------------------------------------------------------------------ State

    private fun showResult(r: ResultView) {
        expanded = false
        endSteer()
        result = r
        watchUnlock()
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
        // Glow first, so a capsule created in the same pass sits above it.
        val glowing = next != null && prefs.edgeGlow.value &&
            (next.phase == RunPhase.Running || next.phase == RunPhase.Starting || next.phase == RunPhase.Paused)
        if (glowing) ensureGlow() else removeGlow()
        glowPaused = next?.phase == RunPhase.Paused
        if (next != null) ensureCapsule() else removeCapsule()
        if (result != null) ensureResultWindow() else removeResultWindow()
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
