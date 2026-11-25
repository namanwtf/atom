package atom.overlay.run

import android.content.Intent
import android.view.Gravity
import android.view.WindowManager.LayoutParams
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

internal data class ResultView(val conversationId: String, val source: RunSource, val ok: Boolean, val preview: String)

/**
 * The run capsule and edge glow, shown while any run uses the screen (whatever started it), then the result card.
 * Windows exist only while there is something to show. Main thread only.
 */
internal object RunOverlays {
    private var capsule: OverlayWindow? = null
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
        if (drove && !presentedElsewhere && run.phase != RunPhase.Cancelled) {
            showResult(
                ResultView(
                    conversationId = run.conversationId,
                    source = run.source,
                    ok = run.phase == RunPhase.Done,
                    preview = (if (run.phase == RunPhase.Done) Markdown.plain(run.text) else run.error.orEmpty()).take(280),
                ),
            )
        }
        refresh()
    }

    fun onWindowSourceChanged() {
        removeCapsule()
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

    fun dismissResult() {
        resultTimer?.cancel()
        resultTimer = null
        result = null
        refresh()
    }

    // ------------------------------------------------------------------ State

    private fun showResult(r: ResultView) {
        expanded = false
        endSteer()
        result = r
        resultTimer?.cancel()
        resultTimer = OverlayRuntime.scope.launch {
            delay(RESULT_MS)
            dismissResult()
        }
    }

    private fun refresh() {
        val active = lastRuns.firstOrNull { it.phase.active && it.conversationId in driving }
        val next = active?.let(::viewOf)
        if (next != view) view = next
        if (next == null) {
            expanded = false
            endSteer()
        }
        // Glow first, so a capsule created in the same pass sits above it.
        val glowing = next != null && prefs.edgeGlow.value && (next.phase == RunPhase.Running || next.phase == RunPhase.Starting)
        if (glowing) ensureGlow() else removeGlow()
        if (next != null || result != null) ensureCapsule() else removeCapsule()
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
        val w = OverlayWindow(ctx, params, onBack = {
            when {
                steering -> endSteer()
                expanded -> expanded = false
                else -> return@OverlayWindow false
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
        val w = OverlayWindow(slot.context, params) { EdgeGlow() }
        if (w.show()) glow = w
    }

    private fun removeGlow() {
        glow?.remove()
        glow = null
    }

    private const val RESULT_MS = 30_000L
}
