package atom.app.integration

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import atom.app.AtomApp
import atom.app.agent.SubagentBridge
import atom.automation.Automations
import atom.context.notifications.AtomNotificationListener
import atom.device.Accessibility
import atom.device.root.RootAccess
import atom.device.root.RootMonitor
import atom.device.root.RootStatus
import atom.device.screen.DisplayUi
import atom.extend.Extend
import atom.general.GeneralHooks
import atom.overlay.Overlay
import atom.vdisplay.route.DisplayAccessibility
import atom.vdisplay.route.DisplayRouter
import atom.vdisplay.system.SystemPrefs
import atom.app.ondevice.OnDevicePack
import atom.app.ui.Routes
import atom.core.ondevice.WakeModelsLoad
import atom.voice.Wake
import atom.voice.WakeModelSource
import atom.voice.WakePolicy
import atom.voice.speech.Speech
import atom.web.Web
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Every app-side install call for the feature modules, in one place. Runs from [AtomApp.onCreate] after the
 * agent host is installed and before any feature's onAppCreate. Only assignments and listener registrations
 * happen here: no I/O, nothing starts.
 */
internal object Integration {
    fun install(app: AtomApp) {
        val graph = app.graph

        // Tool list: web, browser and image tools follow their own settings pages.
        atom.device.caps.ToolCatalog.featureOff = { id -> atom.web.WebFeature.toolsOffReason(app, id) }

        // Extend: subagents run on the parent's tools, lease and model. Its secrets keep their own Keystore store.
        SubagentBridge(graph).let { Extend.install(contextWindow = it, engines = it, toolScope = it) }

        // Voice: credentials in the vault (own prefix, never backed up), proofreading on the title model,
        // Hey Atom switches in the app settings.
        Speech.install(
            credentials = VaultVoiceCredentials(app, graph.vault),
            proofreader = ChatProofreader(graph),
            wakePreferences = AppWakePreferences(graph),
        )
        // "Hey Atom" models and runtime come from the optional on-device AI pack.
        Wake.modelSource = PackWakeModels
        Wake.openPackSettings = { NavRequests.open(Routes.ON_DEVICE) }
        // The pack can appear (download, start-up check) or go (removed) at any time: re-apply a saved "Hey Atom".
        graph.appScope.launch {
            OnDevicePack.ready.drop(1).distinctUntilChanged().collect {
                val s = graph.settings.current()
                if (s.heyAtom) {
                    Wake.setEnabled(app, true, WakePolicy(screenOffListening = s.heyAtomScreenOff, listenWhileCharging = Speech.config.value.wakeWhileCharging))
                }
            }
        }

        // Overlay: deep links, model chip, the real phone-control lease, the voice module's dictation and voice.
        Overlay.hooks = AppOverlayHooks(app, graph)
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

        // Linux: Root mode and the root terminal follow the app's one root status instead of a su check of their own.
        atom.linux.LinuxEnv.rootLink = object : atom.linux.RootLink {
            override val granted: StateFlow<Boolean> = RootAccess.status
                .map { it == RootStatus.Granted }
                .stateIn(graph.appScope, SharingStarted.Eagerly, RootAccess.granted)

            override suspend fun ensure(): Boolean = RootAccess.ensure(app)

            override suspend fun authorize(): Boolean = RootMonitor.checkAgain(app) == RootStatus.Granted
        }

        // Web: provider keys, own secrets, root / Linux file access, run -> conversation, image -> composer.
        Web.install(AppWebHost(app, graph, graph.vault))

        // Background virtual display: accessibility reads, screenshots and gestures by display id.
        DisplayRouter.accessibility = DisplayUiAccessibility

        // Backup sections and the backup screen's view of running tasks.
        AppBackups.register(app, graph)
        GeneralHooks.tasksRunning = { graph.tasks.isRunning }
        GeneralHooks.stopTasks = { graph.tasks.stopAll() }
        GeneralHooks.conversationsSaving = { graph.conversations.isSaving }
        GeneralHooks.legacyImport = object : GeneralHooks.LegacyImportHook {
            override fun available(): Boolean = atom.app.data.legacy.LegacyImport.available(app)

            override suspend fun run(): String = atom.app.data.legacy.LegacyImport.runManually(app, graph)

            override fun cleanupOffer() = atom.app.data.legacy.LegacyImport.cleanupOffer(app)

            override suspend fun removeOldData() = atom.app.data.legacy.LegacyImport.removeOldData(app)

            override suspend fun removeOldMemory() = atom.app.data.legacy.LegacyImport.removeOldMemory(app)

            override fun changes() = atom.app.data.legacy.LegacyImport.changes
        }
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

/** The wake-word models, read from the on-device AI pack through its interface. Never throws. */
private object PackWakeModels : WakeModelSource {
    override val available: StateFlow<Boolean> get() = OnDevicePack.ready

    override fun load(): WakeModelsLoad {
        return try {
            val engines = OnDevicePack.engines() ?: return WakeModelsLoad.Failed(NO_PACK)
            val path = OnDevicePack.loadedPath() ?: return WakeModelsLoad.Failed(NO_PACK)
            engines.wakeModels(path)
        } catch (e: Throwable) {
            WakeModelsLoad.Failed("The on-device AI pack could not be loaded: " + (e.message ?: e.javaClass.simpleName).take(200))
        }
    }

    private const val NO_PACK = "Needs the on-device AI pack (Settings > LLM providers > On-device models)"
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
