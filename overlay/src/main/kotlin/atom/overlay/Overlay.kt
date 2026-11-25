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
import atom.overlay.panel.MiniChat
import atom.overlay.run.RunOverlays
import atom.overlay.window.Windows
import atom.ui.feature.AgentHost
import atom.ui.feature.Atom
import atom.ui.feature.HostRun
import atom.ui.feature.RunPhase
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

    /** The "Read answers aloud" switch of the assistant overlay. */
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
            if (hidMiniChat || hidAssistant) delay(SETTLE_MS)
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
        (app as? Application)?.registerActivityLifecycleCallbacks(AppVisibility)
        ready = true
        // After Application.onCreate returns, so the agent host is installed by then.
        main.post {
            RunHub.start()
            Bubble.sync()
        }
    }

    /** Returns to Atom bring a hidden bubble back. */
    private object AppVisibility : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) = Bubble.onAppOpened()
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
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
            RunOverlays.onFinished(run, presentedElsewhere = chat || assistant)
        }
        RunOverlays.onRuns(runs)
        Bubble.onRuns(runs)
    }
}
