package atom.overlay.hey

import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.view.View
import android.view.WindowManager.LayoutParams
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import atom.overlay.Overlay
import atom.overlay.OverlayMessage
import atom.overlay.OverlayRuntime
import atom.overlay.R
import atom.overlay.RunHub
import atom.overlay.assistant.AutoSpeak
import atom.overlay.run.CapsuleStatus
import atom.overlay.run.RunOverlays
import atom.overlay.ui.Markdown
import atom.overlay.window.CardWindow
import atom.overlay.window.OverlayWindow
import atom.overlay.window.Windows
import atom.ui.feature.HostApproval
import atom.ui.feature.HostRun
import atom.ui.feature.RunPhase
import atom.ui.feature.RunSource
import atom.ui.components.AtomGlass
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

    /** Counts new recognised words while listening; each one sends a wave through the light. */
    internal var wordsTick by mutableIntStateOf(0)
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

    /** The chat bar's text while the user types. */
    internal var draft by mutableStateOf("")

    /** What screen context the latest command took along, for the debug hook ("none" or its size). */
    private var sentScreen: String? = null

    /** The screen is being read: the windows wait, so ATOM's own overlay is never in what is read. */
    private var holding = false
    private var captureJob: Job? = null

    /** A debug sample is on screen: no capture, run or timer behind it. */
    private var sample = false
    private var glow: OverlayWindow? = null
    private var scrim: OverlayWindow? = null
    private var panel: CardWindow? = null
    private var scrimBaseFlags = 0
    private var screenReceiver: BroadcastReceiver? = null

    /** The card follows the system theme: a light or a dark glass. */
    internal var dark by mutableStateOf(true)
        private set

    /** True while the card's glass blurs what is behind (otherwise its tint is nearly opaque). */
    internal var blurred by mutableStateOf(false)
        private set

    /** Screen height in px, for the card's height cap. */
    internal var screenHeight by mutableFloatStateOf(0f)
        private set

    /** The conversation's last turns, shown in the card once a command has an answer (newest last). */
    internal var turns by mutableStateOf<List<OverlayMessage>>(emptyList())
        private set
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
        val hadWindows = glow != null || panel != null || scrim != null
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

    override fun onPartial(text: String) {
        val before = (stage as? HeyStage.Listening)?.words
        apply(HeyEvent.Partial(text))
        // New words send a wave through the light too (for recognizers that report no loudness).
        if ((stage as? HeyStage.Listening)?.words?.let { it != before } == true) wordsTick++
    }

    override fun onCommand(text: String): Boolean {
        if (stage !is HeyStage.Listening) return false
        if (holding) { // spoken before the screen was read: go without it
            captureJob?.cancel()
            captureJob = null
            holding = false
            sync()
        }
        // The windows could not be added: the user saw nothing, so the command opens ATOM as before. (Over screens
        // that hide other apps' overlays, such as Settings, the window exists but is not drawn; Android does not tell
        // the app, so that case cannot be told apart here.)
        if (panel == null) {
            reset()
            return false
        }
        apply(HeyEvent.Final(text))
        val heard = stage as? HeyStage.Heard ?: return false
        // With ATOM itself in front, the chat shows the run; the overlay only confirms and fades.
        val id = if (OverlayRuntime.appInFront) null else startRun(heard.words, heard.continueIn)
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

    private fun startRun(text: String, continueIn: String? = null): String? {
        val host = OverlayRuntime.host() ?: return null
        RunHub.start()
        if (continueIn != null) {
            // A follow-up continues that conversation; while it still runs, the words steer it instead.
            val started = runCatching { host.start(text, continueIn, RunSource.Voice) }.getOrNull()
            if (started != null) return started
            return runCatching { host.steer(continueIn, text) }.map { continueIn }.getOrNull()
        }
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

    /** True while the light shows for [conversationId]'s run, the same light the run glow draws. */
    internal fun glowLitFor(conversationId: String): Boolean {
        val s = stage as? HeyStage.Working ?: return false
        return glow != null && !leaving && s.conversationId == conversationId &&
            HeyMachine.glow(s, workingGlow = OverlayRuntime.prefs.edgeGlow.value) != HeyGlow.None
    }

    /** A run starts using the screen: nothing of the overlay may cover what the agent reads and taps. */
    internal fun hideForAgent(conversationId: String): Boolean {
        if (stage == HeyStage.Hidden || panel == null) return false
        apply(HeyEvent.ScreenTaken(conversationId))
        return true
    }

    // ------------------------------------------------------------------ Panel actions

    /** The X: stops listening, or stops the run while it works, and closes. */
    fun cancel() = apply(HeyEvent.Cancel)

    /**
     * Back, a swipe down, a tap outside the card or the screen turning off: closes the panel. A working run goes on
     * (the run capsule and the notification carry it).
     */
    fun close() = apply(HeyEvent.Close)

    internal fun tapPanel() = apply(HeyEvent.Open)

    // ------------------------------------------------------------------ Chat bar

    /** The chat bar's field: listening stops at once (the microphone is released) and the keyboard opens. */
    fun startTyping() {
        apply(HeyEvent.StartTyping)
        (stage as? HeyStage.Typing)?.let { draft = it.draft }
    }

    /**
     * The chat bar's Send: the typed words go the same way as spoken ones (the screen context read at the wake word,
     * the same approvals, the same hand-over to the run capsule), or continue the conversation of the result.
     */
    fun send(text: String = draft): Boolean {
        val typed = text.trim()
        if (typed.isEmpty() || stage !is HeyStage.Typing) return false
        apply(HeyEvent.Send(typed))
        val heard = stage as? HeyStage.Heard ?: return false
        draft = ""
        if (sample) return true
        val id = startRun(heard.words, heard.continueIn)
        if (id == null) {
            apply(HeyEvent.NotStarted)
            openApp(heard.continueIn)
            return false
        }
        apply(HeyEvent.Started(id))
        OverlayRuntime.host()?.runs?.value?.firstOrNull { it.conversationId == id }?.let { apply(HeyEvent.Progress(it)) }
        return true
    }

    /** The chat bar's mic: back to speaking (the "Hey ATOM" capture starts again, without the wake word). */
    fun listenAgain(): Boolean {
        val s = stage
        if (s !is HeyStage.Typing && s !is HeyStage.Done && s != HeyStage.Missed) return false
        if (!sample && !Wake.listenAgain()) return false
        apply(HeyEvent.Listen)
        draft = ""
        return true
    }

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
            is HeyStage.Typing -> "typing"
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
                is HeyStage.Typing -> s.draft
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
            "draft" to draft,
            "continueIn" to when (s) {
                is HeyStage.Typing -> s.continueIn
                is HeyStage.Listening -> s.continueIn
                is HeyStage.Heard -> s.continueIn
                else -> null
            },
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
     * For the debug builds' screenshot hook only: shows [name] with sample content, without a microphone, a run or a
     * timer: listening, partial, partial_screen_off, heard, working, approval, done, failed, missed, opening;
     * run_glow (the run overlay's own glow, without a run); hide. [words] is what the user said; [detail] the
     * working step or the result; [app] the app on the screen chip. False when the overlay cannot show.
     */
    fun showSample(name: String, words: String? = null, detail: String? = null, level: Float = 0.55f, app: String? = null): Boolean {
        if (!OverlayRuntime.ready) return false
        RunOverlays.showSampleGlow(false)
        if (name == "hide") {
            cancel()
            return true
        }
        if (name == "run_glow") { // the light while ATOM operates the phone, the same one this overlay draws
            cancel()
            RunOverlays.showSampleGlow(true)
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
            "listening_voice" -> HeyStage.Listening(said)
            // The answer and a follow-up, with the conversation's turns in the card.
            "multiturn" -> HeyStage.Done(
                "And one for 5 minutes for the sauce",
                SAMPLE_ID,
                ok = true,
                result = "Done: a second timer for 5 minutes is running too.",
            )
            "missed" -> HeyStage.Missed
            "opening" -> HeyStage.Opening(said)
            // A misheard command opened in the keyboard to fix it.
            "typing" -> HeyStage.Typing(detail ?: "Set a timer for ten minutes and tell me when the past")
            // A follow-up typed under the result.
            "followup" -> HeyStage.Typing(
                detail ?: "And one for 5 minutes for the sauce",
                SAMPLE_ID,
                previous = HeyMachine.shorten("Your 10 minute timer is running. I’ll ring when the pasta is done."),
            )
            else -> return false
        }
        stage = next
        draft = (next as? HeyStage.Typing)?.draft.orEmpty()
        turns = if (name == "multiturn") SAMPLE_TURNS else emptyList()
        sampleVoice?.cancel()
        sampleVoice = if (name == "listening_voice") OverlayRuntime.scope.launch { speakSample() } else null
        captureJob?.cancel()
        holding = false
        // The chip as it shows over the Settings app; no screen is read for a sample.
        screen = if (next is HeyStage.Listening || next is HeyStage.Heard || name == "typing") HeyScreen(app ?: "Settings", "", null, "") else null
        screenUse = name != "partial_screen_off"
        this.level = if (next is HeyStage.Listening && motion) level.coerceIn(0f, 1f) else 0f
        sync()
        return panel != null
    }

    private const val SAMPLE_ID = "debug-hey-atom-sample"
    private var sampleVoice: Job? = null

    private val SAMPLE_TURNS = listOf(
        OverlayMessage(fromUser = true, text = "Set a timer for ten minutes and tell me when the pasta is done"),
        OverlayMessage(fromUser = false, text = "Your 10 minute timer is running. I’ll ring when the pasta is done."),
        OverlayMessage(fromUser = true, text = "And one for 5 minutes for the sauce"),
        OverlayMessage(fromUser = false, text = "Done: a second timer for 5 minutes is running too."),
    )

    /** Debug sample only: a voice that rises and falls like speech, for the frames that show the light moving. */
    private suspend fun speakSample() {
        var t = 0
        while (sample && stage is HeyStage.Listening) {
            val syllable = (t % 7) / 6f
            level = if (t % 7 < 3) 0.35f + 0.6f * (1f - syllable) else 0.08f
            if (t % 21 == 0) wordsTick++
            t++
            delay(90)
        }
    }

    // ------------------------------------------------------------------ Machine

    private fun apply(event: HeyEvent) {
        val before = stage
        val step = HeyMachine.reduce(stage, event)
        stage = step.stage
        val now = stage
        if (now is HeyStage.Heard && now.continueIn == null && before !is HeyStage.Heard) turns = emptyList()
        if (now is HeyStage.Done && before !is HeyStage.Done && !sample) loadTurns(now)
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

    /** The conversation's last turns for the card (one query when the answer arrives; the answer itself until then). */
    private fun loadTurns(done: HeyStage.Done) {
        turns = listOf(OverlayMessage(fromUser = true, text = done.words), OverlayMessage(fromUser = false, text = done.result))
        val id = done.conversationId
        OverlayRuntime.scope.launch {
            val stored = runCatching { Overlay.hooks.transcript(id) }.getOrNull()
            val current = stage as? HeyStage.Done ?: return@launch
            if (current.conversationId == id && !stored.isNullOrEmpty()) turns = stored.takeLast(MAX_TURNS).map { m ->
                if (m.fromUser) m else m.copy(text = Markdown.plain(m.text))
            }
        }
    }

    private fun openApp(conversationId: String?) {
        val ctx = panel?.view?.context ?: OverlayRuntime.app
        runCatching { ctx.startActivity(Overlay.hooks.appIntent(ctx, conversationId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    // ------------------------------------------------------------------ Windows

    /**
     * Makes the windows match [stage]. Three windows: the light (never touchable), the scrim (full screen and
     * transparent: a tap on it, or Back, closes the panel; only while listening, typing or showing the answer) and the
     * card, a window of its own so its frosted glass blurs only what is behind the card.
     */
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
            // Handed to the run capsule: the panel goes at once, so the agent's first look at the screen sees only the
            // app. The light stays a few frames, until the run glow (the same light) has drawn, so the edge never
            // blinks.
            shown = s
            removePanel()
            val light = glow ?: return
            glow = null
            OverlayRuntime.scope.launch {
                delay(HANDOVER_MS)
                light.remove()
            }
            return
        }
        shown = s
        ensureGlow()
        val wantScrim = mode == HeyPanel.Modal || mode == HeyPanel.Typing
        if (wantScrim && scrim == null) {
            // The scrim must sit below the card: a card already shown is added again above it.
            panel?.remove()
            panel = null
            ensureScrim()
        } else if (!wantScrim) {
            scrim?.remove()
            scrim = null
        }
        ensurePanel()
        if (mode != panelMode || wasLeaving) applyMode(mode)
        watchScreen(true)
    }

    private fun leave() {
        timer?.cancel()
        timer = null
        level = 0f
        draft = ""
        turns = emptyList()
        captureJob?.cancel()
        captureJob = null
        holding = false
        screen = null
        if (glow == null && panel == null && scrim == null) {
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
        panel?.setTouchable(false)
        scrim?.let { w ->
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

    /** The transparent full-screen layer under the card: a tap on it, or Back while it has focus, closes the panel. */
    private fun ensureScrim() {
        if (scrim != null) return
        val slot = Windows.appOverlay() ?: return
        val params = OverlayWindow.params(slot.type, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, 0).apply {
            title = SCRIM_TITLE
            @Suppress("DEPRECATION")
            softInputMode = LayoutParams.SOFT_INPUT_ADJUST_NOTHING or LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        }
        scrimBaseFlags = params.flags
        val w = OverlayWindow(slot.context, params, onBack = {
            close()
            true
        }) { HeyScrim() }
        if (w.show()) scrim = w
    }

    private fun ensurePanel() {
        if (panel != null) return
        val slot = Windows.appOverlay() ?: return
        val ctx = slot.context
        val area = Windows.area(ctx)
        bottomInset = area.bottom
        dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_NO
        val d = ctx.resources.displayMetrics.density
        screenHeight = area.height
        val w = CardWindow(ctx, PANEL_TITLE, onBack = { close() }, onOutsideTouch = { close() }) { HeyCardWindow() }
        w.place(
            widthPx = minOf(area.width - 24 * d, 560 * d).roundToInt(),
            bottomPx = (area.bottom + 10 * d).roundToInt(),
        )
        w.onBlurChanged = { enabled ->
            blurred = enabled
            applyGlass()
        }
        panelMode = HeyPanel.None
        if (!w.show()) return
        panel = w
        blurred = w.blurEnabled() && !Windows.powerSave()
        applyGlass()
    }

    /** The card's frosted glass: ATOM's glass tint for the theme, over a blur of what is behind when available. */
    private fun applyGlass() {
        val w = panel ?: return
        val d = w.view.resources.displayMetrics.density
        val tint = HeyGlass.tint(dark = dark, blurred = blurred)
        w.setGlass(tint, cornerPx = HeyGlass.CORNER_DP * d, blurPx = if (blurred) (AtomGlass.BlurRadius.value * d).roundToInt() else 0)
    }

    /**
     * Modal: the scrim has focus (Back closes) and catches taps outside the card. Typing: the card takes focus and
     * the keyboard; the scrim still closes on a tap. Inline: no scrim, the app keeps every touch, and a touch outside
     * the card closes it.
     */
    private fun applyMode(mode: HeyPanel) {
        panelMode = mode
        panel?.setInput(typing = mode == HeyPanel.Typing, watchOutside = mode == HeyPanel.Inline)
        panel?.setTouchable(true)
        scrim?.let { w ->
            w.params.flags = scrimBaseFlags or (if (mode == HeyPanel.Typing) LayoutParams.FLAG_NOT_FOCUSABLE else LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            w.update()
        }
    }

    private fun removePanel() {
        panel?.remove()
        panel = null
        scrim?.remove()
        scrim = null
        panelMode = HeyPanel.None
    }

    private fun removeWindows() {
        exit?.cancel()
        exit = null
        glow?.remove()
        glow = null
        removePanel()
        watchScreen(false)
        leaving = false
        level = 0f
        if (stage == HeyStage.Hidden) {
            shown = HeyStage.Hidden
            sample = false
        }
    }

    /** The screen turning off closes the panel. Registered only while the overlay shows. */
    private fun watchScreen(on: Boolean) {
        if (on == (screenReceiver != null)) return
        if (on) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == Intent.ACTION_SCREEN_OFF) close()
                }
            }
            runCatching {
                ContextCompat.registerReceiver(OverlayRuntime.app, receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
            }.onSuccess { screenReceiver = receiver }
        } else {
            screenReceiver?.let { runCatching { OverlayRuntime.app.unregisterReceiver(it) } }
            screenReceiver = null
        }
    }

    private fun keyguardLocked(): Boolean =
        OverlayRuntime.app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    /** False when "Remove animations" (animator duration scale 0) is on. */
    private fun animationsOn(): Boolean = runCatching { ValueAnimator.areAnimatorsEnabled() }.getOrDefault(true)

    private const val EXIT_MS = 280L
    private const val MAX_TURNS = 8
    private const val CAPTURE_WAIT_MS = 600L
    private const val SETTLE_MS = 120L
    private const val HANDOVER_MS = 80L
    const val GLOW_TITLE = "AtomHeyAtomGlow"
    const val PANEL_TITLE = "AtomHeyAtom"
    const val SCRIM_TITLE = "AtomHeyAtomScrim"
}
