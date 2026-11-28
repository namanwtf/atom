package atom.overlay.hey

import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.WindowManager.LayoutParams
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import atom.overlay.Overlay
import atom.overlay.OverlayRuntime
import atom.overlay.R
import atom.overlay.RunHub
import atom.overlay.assistant.AutoSpeak
import atom.overlay.run.CapsuleStatus
import atom.overlay.run.RunOverlays
import atom.overlay.ui.Markdown
import atom.overlay.window.OverlayWindow
import atom.overlay.window.Windows
import atom.ui.feature.HostApproval
import atom.ui.feature.HostRun
import atom.ui.feature.RunPhase
import atom.ui.feature.RunSource
import atom.ui.feature.TurnContext
import atom.voice.Wake
import atom.voice.WakePresenter
import atom.voice.speech.Speech
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * "Hey ATOM" on screen: a light around the screen edges and a panel at the bottom while ATOM listens (with the
 * words as they are recognised), then while it works where the user is, then its result.
 *
 * The command runs in place, like a bubble run: nothing opens unless the user taps the panel. Once the run uses
 * the screen, the overlay steps aside (the run capsule at the top shows the step, approvals and Stop, and the agent
 * reads and taps the app, not the overlay); the result comes back here. Without "display over other apps", or
 * over the lock screen, it declines and the command opens ATOM as before.
 *
 * Cost: the two windows exist only while something shows. The light moves only while listening (at most the
 * capture's 10 s); afterwards it is drawn once and requests no frames. No service, wake lock or polling; the one
 * timer is the short fade. Main thread only.
 */
object HeyAtomOverlay : WakePresenter {
    /** The machine's stage. */
    internal var stage by mutableStateOf<HeyStage>(HeyStage.Hidden)
        private set

    /** The stage the windows draw; it keeps the last visible one while they fade out. */
    internal var shown by mutableStateOf<HeyStage>(HeyStage.Hidden)
        private set
    internal var leaving by mutableStateOf(false)
        private set

    /** Voice loudness 0..1 while listening. */
    internal var level by mutableFloatStateOf(0f)
        private set

    /** False under "Remove animations": a still border, no fades. */
    internal var motion by mutableStateOf(true)
        private set
    internal var panelMode by mutableStateOf(HeyPanel.None)
        private set

    /** Navigation bar height in px, kept clear below the panel. */
    internal var bottomInset by mutableFloatStateOf(0f)
        private set

    /**
     * What was on screen at the wake word, for this command only (null: none, or not usable). [screenUse] is the
     * panel chip's switch for this request.
     */
    internal var screen by mutableStateOf<HeyScreen?>(null)
        private set
    internal var screenUse by mutableStateOf(true)
        private set

    /** What screen context the latest command took along, for the debug hook ("none" or its size). */
    private var sentScreen: String? = null

    /** The screen is being read: the windows wait, so ATOM's own overlay is never in what is read. */
    private var holding = false
    private var captureJob: Job? = null

    /** A debug sample is on screen: no capture, run or timer behind it. */
    private var sample = false
    private var glow: OverlayWindow? = null
    private var panel: OverlayWindow? = null
    private var panelBaseFlags = 0
    private var timer: Job? = null
    private var exit: Job? = null

    // ------------------------------------------------------------------ WakePresenter (the "Hey ATOM" service)

    override fun onWake(): Boolean {
        if (!OverlayRuntime.ready || !Windows.canDrawOverlays() || keyguardLocked()) return false
        if (Windows.appOverlay() == null) return false
        sample = false
        motion = animationsOn()
        level = 0f
        captureScreen()
        apply(HeyEvent.Wake)
        if (panel == null && !holding) { // the window could not be added: the command opens ATOM as before
            reset()
            return false
        }
        return true
    }

    /**
     * One look at the app in front, before the overlay draws (the windows wait for it, at most [CAPTURE_WAIT_MS]).
     * Skipped when "Use what's on screen" is off or ATOM itself is in front.
     */
    private fun captureScreen() {
        captureJob?.cancel()
        screen = null
        screenUse = true
        holding = false
        if (!Speech.config.value.wakeScreenContext || OverlayRuntime.appInFront) return
        holding = true
        // An earlier result still on screen must not be in the capture: it goes first, and the compositor gets a
        // frame to drop it.
        val hadWindows = glow != null || panel != null
        if (hadWindows) removeWindows()
        val at = System.currentTimeMillis()
        val app = OverlayRuntime.app
        captureJob = OverlayRuntime.scope.launch {
            if (hadWindows) delay(SETTLE_MS)
            val capture = withTimeoutOrNull(CAPTURE_WAIT_MS) { runCatching { Overlay.hooks.captureScreen() }.getOrNull() }
            if (!isActive) return@launch
            holding = false
            sync()
            val sees = runCatching { Overlay.hooks.seesImages(RunSource.Voice) }.getOrDefault(false)
            val result = withContext(Dispatchers.Default) {
                HeyContext.build(capture, enabled = true, seesImages = sees, ownPackage = app.packageName, capturedAt = at, note = { name ->
                    if (name != null) app.getString(R.string.hey_atom_screen_note_app, name) else app.getString(R.string.hey_atom_screen_note)
                })
            }
            if (stage is HeyStage.Listening || stage is HeyStage.Heard) screen = (result as? HeyScreenResult.Used)?.screen
            captureJob = null
        }
    }

    /** The chip: use (or not) what was on screen for this request. */
    internal fun toggleScreen() {
        if (screen != null) screenUse = !screenUse
    }

    override fun onLevel(level: Float) {
        if (stage is HeyStage.Listening && motion) this.level = level.coerceIn(0f, 1f)
    }

    override fun onPartial(text: String) = apply(HeyEvent.Partial(text))

    override fun onCommand(text: String): Boolean {
        if (stage !is HeyStage.Listening) return false
        if (holding) { // spoken before the screen was read: go without it
            captureJob?.cancel()
            captureJob = null
            holding = false
            sync()
        }
        if (panel == null) { // the windows could not be added: the command opens ATOM as before
            reset()
            return false
        }
        apply(HeyEvent.Final(text))
        val heard = stage as? HeyStage.Heard ?: return false
        // With ATOM itself in front, the chat shows the run; the overlay only confirms and fades.
        val id = if (OverlayRuntime.appInFront) null else startRun(heard.words)
        if (id == null) {
            apply(HeyEvent.NotStarted)
            return false
        }
        apply(HeyEvent.Started(id))
        // The run may have moved on (or ended) before its first update reaches the overlay.
        OverlayRuntime.host()?.runs?.value?.firstOrNull { it.conversationId == id }?.let { apply(HeyEvent.Progress(it)) }
        return true
    }

    override fun onMissed() = apply(HeyEvent.NoSpeech)

    override fun onStopped() = apply(HeyEvent.CaptureStopped)

    private fun startRun(text: String): String? {
        val host = OverlayRuntime.host() ?: return null
        RunHub.start()
        val context = screen?.takeIf { screenUse }
        // The run holds the context from here on; the overlay lets go of it.
        screen = null
        sentScreen = context?.let { "app=${it.app} chars=${it.text.length} image=${it.imageJpeg != null}" } ?: "none"
        return runCatching {
            if (context != null) {
                host.start(text, RunSource.Voice, TurnContext(context.text, context.imageJpeg, context.note))
            } else {
                host.start(text, null, RunSource.Voice)
            }
        }.getOrNull()
    }

    // ------------------------------------------------------------------ Runs (from RunHub / RunOverlays)

    internal fun onRuns(runs: List<HostRun>) {
        val s = stage as? HeyStage.Working ?: return
        if (sample) return
        runs.firstOrNull { it.conversationId == s.conversationId }?.let { apply(HeyEvent.Progress(it)) }
    }

    /** True when this overlay presents [run]'s result (so the run capsule shows no result card of its own). */
    internal fun onFinished(run: HostRun): Boolean {
        val s = stage as? HeyStage.Working ?: return false
        if (sample || s.conversationId != run.conversationId) return false
        apply(HeyEvent.Finished(run))
        val answer = run.text.trim()
        if (AutoSpeak.should(
                enabled = OverlayRuntime.prefs.autoSpeak.value,
                shown = stage is HeyStage.Done,
                done = run.phase == RunPhase.Done,
                spokenQuestion = true,
                answer = answer,
                canSpeak = Overlay.readAloud.available,
            )
        ) {
            Overlay.readAloud.speak(Markdown.plain(answer))
        }
        return run.phase != RunPhase.Cancelled
    }

    /** A run starts using the screen: nothing of the overlay may cover what the agent reads and taps. */
    internal fun hideForAgent(conversationId: String): Boolean {
        if (stage == HeyStage.Hidden || panel == null) return false
        apply(HeyEvent.ScreenTaken(conversationId))
        return true
    }

    // ------------------------------------------------------------------ Panel actions

    /** The X, Back, or a tap outside the panel: stops listening, or stops the run while it works. */
    fun cancel() = apply(HeyEvent.Cancel)

    internal fun tapPanel() = apply(HeyEvent.Open)

    internal fun answer(approved: Boolean) {
        val pending = (stage as? HeyStage.Working)?.pending ?: return
        if (!sample) OverlayRuntime.host()?.answer(pending.id, approved)
    }

    // ------------------------------------------------------------------ Debug hook

    /** The stage's name ("hidden", "listening", "working", ...), for the debug builds' test hook. */
    val stageName: String
        get() = when (val s = stage) {
            HeyStage.Hidden -> "hidden"
            is HeyStage.Listening -> if (s.words.isEmpty()) "listening" else "partial"
            is HeyStage.Heard -> "heard"
            is HeyStage.Working -> if (s.handedOff) "handed_off" else "working"
            is HeyStage.Opening -> "opening"
            is HeyStage.Done -> "done"
            HeyStage.Missed -> "missed"
        }

    /** The words, run and result of the stage plus which windows exist, for the debug builds' test hook. */
    fun describe(): Map<String, Any?> {
        val s = stage
        return mapOf(
            "stage" to stageName,
            "words" to when (s) {
                is HeyStage.Listening -> s.words
                is HeyStage.Heard -> s.words
                is HeyStage.Working -> s.words
                is HeyStage.Opening -> s.words
                is HeyStage.Done -> s.words
                else -> null
            },
            "conversationId" to when (s) {
                is HeyStage.Working -> s.conversationId
                is HeyStage.Done -> s.conversationId
                else -> null
            },
            "result" to (s as? HeyStage.Done)?.result,
            "screenApp" to screen?.app,
            "screenChars" to (screen?.text?.length ?: 0),
            "screenImage" to (screen?.imageJpeg != null),
            "screenUse" to screenUse,
            "sentScreen" to sentScreen,
            "glowWindow" to (glow != null),
            "panelWindow" to (panel != null),
            "panelMode" to panelMode.name,
            "motion" to motion,
        )
    }

    /**
     * For the debug builds' screenshot hook only: shows [name] (listening, partial, partial_screen_off, heard,
     * working, approval, done, failed, missed, opening; hide) with sample content, without a microphone, a run or a
     * timer. [words] is what the user said; [detail] the working step or the result; [app] the app on the screen
     * chip. False when the overlay cannot show.
     */
    fun showSample(name: String, words: String? = null, detail: String? = null, level: Float = 0.55f, app: String? = null): Boolean {
        if (!OverlayRuntime.ready) return false
        if (name == "hide") {
            cancel()
            return true
        }
        if (!Windows.canDrawOverlays()) return false
        sample = true
        timer?.cancel()
        motion = animationsOn()
        val said = words ?: "Set a timer for ten minutes and tell me when the pasta is done"
        val next = when (name) {
            "listening" -> HeyStage.Listening()
            "partial", "partial_screen_off" -> HeyStage.Listening(said)
            "heard" -> HeyStage.Heard(said)
            "working" -> HeyStage.Working(said, SAMPLE_ID, CapsuleStatus.Working, detail ?: "Setting a 10 minute timer in Clock")
            "approval" -> HeyStage.Working(
                said,
                SAMPLE_ID,
                CapsuleStatus.NeedsApproval,
                pending = HostApproval(1, detail ?: "Send a text message to Sam: “Running ten minutes late”", Long.MAX_VALUE),
            )
            "done" -> HeyStage.Done(said, SAMPLE_ID, ok = true, result = HeyMachine.shorten(detail ?: "Your 10 minute timer is running. I’ll ring when the pasta is done."))
            "failed" -> HeyStage.Done(said, SAMPLE_ID, ok = false, result = HeyMachine.shorten(detail ?: "Clock didn’t open. Try again in a moment."))
            "missed" -> HeyStage.Missed
            "opening" -> HeyStage.Opening(said)
            else -> return false
        }
        stage = next
        captureJob?.cancel()
        holding = false
        // The chip as it shows over the Settings app; no screen is read for a sample.
        screen = if (next is HeyStage.Listening || next is HeyStage.Heard) HeyScreen(app ?: "Settings", "", null, "") else null
        screenUse = name != "partial_screen_off"
        this.level = if (next is HeyStage.Listening && motion) level.coerceIn(0f, 1f) else 0f
        sync()
        return panel != null
    }

    private const val SAMPLE_ID = "debug-hey-atom-sample"

    // ------------------------------------------------------------------ Machine

    private fun apply(event: HeyEvent) {
        val step = HeyMachine.reduce(stage, event)
        stage = step.stage
        step.effects.forEach(::perform)
        sync()
    }

    private fun perform(effect: HeyEffect) {
        when (effect) {
            HeyEffect.StopCapture -> if (!sample) Wake.cancelCapture()
            is HeyEffect.StopRun -> if (!sample) OverlayRuntime.host()?.stop(effect.conversationId)
            is HeyEffect.OpenConversation -> openApp(effect.conversationId.takeUnless { sample })
            is HeyEffect.Timer -> {
                timer?.cancel()
                if (!sample) {
                    timer = OverlayRuntime.scope.launch {
                        delay(effect.ms)
                        timer = null
                        apply(HeyEvent.TimerElapsed)
                    }
                }
            }
            HeyEffect.ClearTimer -> {
                timer?.cancel()
                timer = null
            }
        }
    }

    private fun openApp(conversationId: String?) {
        val ctx = panel?.view?.context ?: OverlayRuntime.app
        runCatching { ctx.startActivity(Overlay.hooks.appIntent(ctx, conversationId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    // ------------------------------------------------------------------ Windows

    /** Makes the windows match [stage]. */
    private fun sync() {
        val s = stage
        if (s == HeyStage.Hidden) {
            leave()
            return
        }
        if (holding && panel == null) return
        exit?.cancel()
        exit = null
        val wasLeaving = leaving
        leaving = false
        val mode = HeyMachine.panel(s, screenBusy = RunOverlays.view != null)
        if (mode == HeyPanel.None) {
            // Handed to the run capsule: gone at once, so the agent's first look at the screen sees only the app.
            removeWindows()
            shown = s
            return
        }
        shown = s
        ensureGlow()
        ensurePanel(mode, relayout = wasLeaving)
    }

    private fun leave() {
        timer?.cancel()
        timer = null
        level = 0f
        captureJob?.cancel()
        captureJob = null
        holding = false
        screen = null
        if (glow == null && panel == null) {
            reset()
            return
        }
        if (!motion) {
            removeWindows()
            return
        }
        if (leaving) return
        leaving = true
        // Touches go to the app below while the overlay fades.
        panel?.let { w ->
            w.params.flags = w.params.flags or LayoutParams.FLAG_NOT_TOUCHABLE or LayoutParams.FLAG_NOT_FOCUSABLE
            w.update()
        }
        exit = OverlayRuntime.scope.launch {
            delay(EXIT_MS)
            exit = null
            removeWindows()
        }
    }

    private fun reset() {
        timer?.cancel()
        timer = null
        captureJob?.cancel()
        captureJob = null
        holding = false
        screen = null
        removeWindows()
        stage = HeyStage.Hidden
    }

    private fun ensureGlow() {
        if (glow != null) return
        val slot = Windows.appOverlay() ?: return
        val params = OverlayWindow.params(slot.type, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, OverlayWindow.PASS_THROUGH).apply {
            alpha = OverlayWindow.MAX_PASS_THROUGH_ALPHA
            title = GLOW_TITLE
        }
        val w = OverlayWindow(slot.context, params) { HeyEdgeLight() }
        // Decoration only: screen readers and the agent's screen reading skip it.
        w.view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        if (w.show()) glow = w
    }

    private fun ensurePanel(mode: HeyPanel, relayout: Boolean) {
        val existing = panel
        if (existing != null) {
            if (mode != panelMode || relayout) {
                panelMode = mode
                layout(existing.params, mode)
                existing.update()
            }
            return
        }
        val slot = Windows.appOverlay() ?: return
        bottomInset = Windows.area(slot.context).bottom
        val params = OverlayWindow.params(slot.type, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, 0).apply {
            title = PANEL_TITLE
            softInputMode = LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        panelBaseFlags = params.flags
        panelMode = mode
        layout(params, mode)
        // Back reaches the window only while it is modal (focusable), and then it always cancels.
        val w = OverlayWindow(slot.context, params, onBack = {
            cancel()
            true
        }) { HeyPanelWindow() }
        if (w.show()) panel = w else panelMode = HeyPanel.None
    }

    /** Modal: the whole screen, focusable (Back and taps outside cancel). Inline: only the panel, never focused. */
    private fun layout(p: LayoutParams, mode: HeyPanel) {
        if (mode == HeyPanel.Modal) {
            p.width = LayoutParams.MATCH_PARENT
            p.height = LayoutParams.MATCH_PARENT
            p.gravity = Gravity.TOP or Gravity.START
            p.y = 0
            p.flags = panelBaseFlags
        } else {
            p.width = LayoutParams.MATCH_PARENT
            p.height = LayoutParams.WRAP_CONTENT
            p.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            p.y = bottomInset.roundToInt()
            p.flags = panelBaseFlags or LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCH_MODAL
        }
    }

    private fun removeWindows() {
        exit?.cancel()
        exit = null
        glow?.remove()
        glow = null
        panel?.remove()
        panel = null
        panelMode = HeyPanel.None
        leaving = false
        level = 0f
        if (stage == HeyStage.Hidden) {
            shown = HeyStage.Hidden
            sample = false
        }
    }

    private fun keyguardLocked(): Boolean =
        OverlayRuntime.app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    /** False when "Remove animations" (animator duration scale 0) is on. */
    private fun animationsOn(): Boolean = runCatching { ValueAnimator.areAnimatorsEnabled() }.getOrDefault(true)

    private const val EXIT_MS = 280L
    private const val CAPTURE_WAIT_MS = 600L
    private const val SETTLE_MS = 120L
    const val GLOW_TITLE = "AtomHeyAtomGlow"
    const val PANEL_TITLE = "AtomHeyAtom"
}
