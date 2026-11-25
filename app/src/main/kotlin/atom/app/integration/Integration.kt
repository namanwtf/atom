package atom.app.integration

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import atom.app.AtomApp
import atom.app.agent.SubagentBridge
import atom.automation.Automations
import atom.context.notifications.AtomNotificationListener
import atom.device.Accessibility
import atom.device.root.RootAccess
import atom.device.screen.DisplayUi
import atom.extend.Extend
import atom.general.GeneralHooks
import atom.overlay.Overlay
import atom.vdisplay.route.DisplayAccessibility
import atom.vdisplay.route.DisplayRouter
import atom.vdisplay.system.SystemPrefs
import atom.voice.Wake
import atom.voice.WakePolicy
import atom.voice.speech.Speech
import atom.web.Web
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Every app-side install call for the feature modules, in one place. Runs from [AtomApp.onCreate] after the
 * agent host is installed and before any feature's onAppCreate. Only assignments and listener registrations
 * happen here: no I/O, nothing starts.
 */
internal object Integration {
    fun install(app: AtomApp) {
        val graph = app.graph

        // Extend: subagents run on the parent's tools, lease and model. Its secrets keep their own Keystore store.
        SubagentBridge(graph).let { Extend.install(contextWindow = it, engines = it, toolScope = it) }

        // Voice: credentials in the vault (own prefix, never backed up), proofreading on the title model,
        // Hey Atom switches in the app settings.
        Speech.install(
            credentials = VaultVoiceCredentials(app, graph.vault),
            proofreader = ChatProofreader(graph),
            wakePreferences = AppWakePreferences(graph),
        )

        // Overlay: deep links, model chip, the real phone-control lease, the voice module's dictation and voice.
        Overlay.hooks = AppOverlayHooks(graph)
        Overlay.voice = ModuleVoiceBridge()
        Overlay.readAloud = ModuleReadAloudBridge(graph.appScope)
        Accessibility.serviceHook = object : Accessibility.ServiceHook {
            override fun onConnected(service: AccessibilityService) = Overlay.attachAccessibility(service)
            override fun onDisconnected(service: AccessibilityService) = Overlay.detachAccessibility(service)
        }

        // Automations: real runs, tools, model choices; triggers fed by the notification listener and accessibility.
        Automations.bind(
            toolInvoker = AppToolInvoker(app, graph),
            engineProvider = AppCompileEngine(graph),
            models = AppModelChoices(app, graph),
            launcher = AppRunLauncher(graph),
            backgroundDisplay = { SystemPrefs.get(app).backgroundDisplay.value && RootAccess.granted },
        )
        AtomNotificationListener.postedHook = AtomNotificationListener.PostedHook { r ->
            val events = Automations.notificationEvents
            if (events.wanted) events.onNotificationPosted(r.packageName, r.appLabel, r.title, r.text)
        }
        Accessibility.appOpenHook = object : Accessibility.AppOpenHook {
            override val watching: Boolean get() = Automations.appOpenEvents.watchedPackages.isNotEmpty()
            // Every foreground change goes through, so leaving and re-opening a watched app fires again.
            override fun onAppOpened(packageName: String) = Automations.appOpenEvents.onAppOpened(packageName)
        }
        Automations.onListenersChanged = { Accessibility.refreshEventMask() }

        // Web: provider keys, own secrets, root / Linux file access, run -> conversation, image -> composer.
        Web.install(AppWebHost(app, graph, graph.vault))

        // Background virtual display: accessibility reads, screenshots and gestures by display id.
        DisplayRouter.accessibility = DisplayUiAccessibility

        // Backup sections and the backup screen's view of running tasks.
        AppBackups.register(app, graph)
        GeneralHooks.tasksRunning = { graph.tasks.isRunning }
        GeneralHooks.stopTasks = { graph.tasks.stopAll() }
        GeneralHooks.onImported = {
            // Settings flows refresh themselves; Hey Atom must be re-applied by hand (it owns a service).
            graph.appScope.launch {
                val s = graph.settings.current()
                Wake.setEnabled(app, s.heyAtom, WakePolicy(screenOffListening = s.heyAtomScreenOff, listenWhileCharging = Speech.config.value.wakeWhileCharging))
            }
            NavRequests.reset()
        }
    }
}

/** [DisplayRouter]'s accessibility adapter over the device module's [DisplayUi]. */
private object DisplayUiAccessibility : DisplayAccessibility {
    override suspend fun readTree(displayId: Int, maxChars: Int): String? = DisplayUi.readTree(displayId, maxChars)
    override suspend fun screenshot(displayId: Int): Bitmap? = DisplayUi.screenshot(displayId)
    override suspend fun tap(displayId: Int, x: Int, y: Int, long: Boolean): Boolean = DisplayUi.tap(displayId, x, y, long)
    override suspend fun swipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean =
        DisplayUi.swipe(displayId, x1, y1, x2, y2, durationMs)
}

/** Navigation asked for from outside the composition (deep links, after an import). AtomRoot consumes it. */
object NavRequests {
    sealed interface Request {
        /** Back to the chat, dropping secondary screens. */
        data object Reset : Request
        data class Open(val route: String) : Request
    }

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    fun open(route: String) {
        _pending.value = Request.Open(route)
    }

    fun reset() {
        _pending.value = Request.Reset
    }

    fun consume(request: Request) {
        _pending.compareAndSet(request, null)
    }
}
