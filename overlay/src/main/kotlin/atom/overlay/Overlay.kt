package atom.overlay

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import atom.overlay.assistant.AssistantController
import atom.overlay.assistant.AssistantUi
import atom.overlay.bubble.Bubble
import atom.overlay.hey.HeyAtomOverlay
import atom.overlay.panel.MiniChat
import atom.overlay.run.RunOverlays
import atom.overlay.window.Windows
import atom.ui.feature.AgentHost
import atom.ui.feature.Atom
import atom.ui.feature.HostRun
import atom.ui.feature.RunPhase
import atom.voice.Wake
import atom.voice.assist.AssistOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Wiring points for the app and other features. Set the bridges once from Application.onCreate. */
object Overlay {
    @Volatile
    var hooks: OverlayHooks = OverlayHooks.Default

    @Volatile
    var voice: VoiceBridge = VoiceBridge.None

    @Volatile
    var readAloud: ReadAloudBridge = ReadAloudBridge.None

    @Volatile
    var viewfinder: ViewfinderSlot? = null

    /** "Speak answers automatically" (assistant overlay), the same setting as in Settings → Speech. */
    fun autoSpeakAnswers(context: Context): StateFlow<Boolean> = OverlayPrefs.get(context).autoSpeak

    /**
     * Call before every tool that observes or acts on the screen. Hides the mini chat and assistant overlay (they come
     * back with the result), makes the bubble pass-through, shows the run capsule, and returns once nothing of Atom
     * covers the app. Returns immediately when nothing needed hiding.
     */
    suspend fun clearScreenForAgent(conversationId: String) {
        withContext(Dispatchers.Main.immediate) {
            if (!OverlayRuntime.ready) return@withContext
            RunHub.start()
            RunOverlays.markDriving(conversationId)
            val hidMiniChat = MiniChat.hideForAgent(conversationId)
            val hidAssistant = AssistantController.hideForAgent(conversationId)
            val hidHeyAtom = HeyAtomOverlay.hideForAgent(conversationId)
            if (hidMiniChat || hidAssistant || hidHeyAtom) delay(SETTLE_MS)
        }
    }

    /** Lets the run capsule and edge glow use the accessibility overlay (no permission needed, touches stay trusted). */
    fun attachAccessibility(service: AccessibilityService) {
        OverlayRuntime.main.post {
            Windows.attachAccessibility(service)
            if (OverlayRuntime.ready) RunOverlays.onWindowSourceChanged()
        }
    }

    fun detachAccessibility(service: AccessibilityService) {
        OverlayRuntime.main.post {
            Windows.detachAccessibility(service)
            if (OverlayRuntime.ready) RunOverlays.onWindowSourceChanged()
        }
    }

    /** Window removal plus one composition pass of the compositor. */
    private const val SETTLE_MS = 120L
}

/** Process-wide state shared by the overlay controllers. Main thread only unless noted. */
internal object OverlayRuntime {
    lateinit var app: Context
        private set

    val main = Handler(Looper.getMainLooper())
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    var ready = false
        private set

    /** Thread-safe. */
    fun host(): AgentHost? = try {
        Atom.host
    } catch (e: IllegalStateException) {
        null
    }

    val prefs: OverlayPrefs get() = OverlayPrefs.get(app)

    fun init(context: Context) {
        if (ready) return
        app = context.applicationContext
        Windows.init(app)
        AssistOverlay.factory = { AssistantUi() }
        // "Hey ATOM" shows its capture and runs the command over the app in front (it declines without the permission).
        Wake.presenter = HeyAtomOverlay
        (app as? Application)?.registerActivityLifecycleCallbacks(AppVisibility)
        ready = true
        // After Application.onCreate returns, so the agent host is installed by then.
        main.post {
            prefs.migrateAutoSpeak()
            RunHub.start()
            Bubble.sync()
        }
    }

    /** True while one of Atom's own activities is started (on screen). */
    val appInFront: Boolean get() = startedActivities > 0
    private var startedActivities = 0

    /** Returns to Atom bring a hidden bubble back. */
    private object AppVisibility : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            startedActivities++
            Bubble.onAppOpened()
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {
            startedActivities = (startedActivities - 1).coerceAtLeast(0)
        }
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
}

/**
 * Single listener on [AgentHost.runs] for all overlays. Collecting a StateFlow is event-driven: nothing runs
 * between run updates.
 */
internal object RunHub {
    private var job: Job? = null
    private val phases = HashMap<String, RunPhase>()

    fun start(): Boolean {
        if (job != null) return true
        val host = OverlayRuntime.host() ?: return false
        job = OverlayRuntime.scope.launch { host.runs.collect { dispatch(it) } }
        return true
    }

    private fun dispatch(runs: List<HostRun>) {
        val finished = ArrayList<HostRun>(1)
        for (run in runs) {
            val before = phases.put(run.conversationId, run.phase)
            if (before != null && before.active && !run.phase.active) finished += run
        }
        if (phases.size > runs.size) phases.keys.retainAll(runs.mapTo(HashSet()) { it.conversationId })
        // Results first, so the capsule turns into the result card instead of closing and reopening.
        for (run in finished) {
            val chat = MiniChat.onFinished(run)
            val assistant = AssistantController.onFinished(run)
            val heyAtom = HeyAtomOverlay.onFinished(run)
            RunOverlays.onFinished(run, presentedElsewhere = chat || assistant || heyAtom)
        }
        HeyAtomOverlay.onRuns(runs)
        RunOverlays.onRuns(runs)
        Bubble.onRuns(runs)
    }
}
