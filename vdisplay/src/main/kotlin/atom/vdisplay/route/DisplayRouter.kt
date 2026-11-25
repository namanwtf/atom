package atom.vdisplay.route

import android.content.Context
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import atom.device.root.RootAccess
import atom.ui.feature.Atom
import atom.vdisplay.display.HiddenDisplay
import atom.vdisplay.io.RootDisplayIo
import atom.vdisplay.system.SystemPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where the agent's screen work happens. The runtime and the device tools call [acquire] before working in an app
 * and [release] when the run is done; observation and input then go through [screenshot], [tap], [swipe], [type],
 * [key] with the lease, which never touch another display.
 *
 * Routing: hidden display when Settings → System enhancements → Background virtual display is on, root is granted,
 * and the target app is neither already in front nor the launcher / system UI. Otherwise the main screen.
 *
 * Battery: the display, its frame thread and the display listener exist only while a hidden lease is held; the
 * lease is released when its run ends even if the caller forgets.
 */
object DisplayRouter {
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var display: HiddenDisplay? = null
    private var runWatch: Job? = null
    private val io by lazy { RootDisplayIo(app) }

    /** Installed by the app: accessibility on other displays (see [DisplayAccessibility]). */
    @Volatile
    var accessibility: DisplayAccessibility? = null

    private val backend = object : DisplayBackend {
        override fun create(): DisplayInfo {
            val d = HiddenDisplay.create(app, HiddenDisplay.phoneSpec(app))
            display = d
            listen(true)
            return d.info
        }

        override fun destroy(id: Int) {
            listen(false)
            display?.close()
            display = null
        }
    }

    private val leases = LeaseManager(backend)

    val state: StateFlow<LeaseState> get() = leases.state

    val current: DisplayLease? get() = leases.current

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
    }

    /**
     * Claims a display for work in [targetPackage]. [foregroundPackage] is what is in front on the main screen if
     * the caller knows it (else root asks the activity manager). Never throws: when the hidden display cannot be
     * created the lease is on the main screen with the reason.
     */
    suspend fun acquire(targetPackage: String?, conversationId: String? = null, foregroundPackage: String? = null): DisplayLease {
        val prefs = SystemPrefs.get(app)
        val enabled = prefs.backgroundDisplay.value
        val rootOk = enabled && (RootAccess.granted || RootAccess.ensure(app))
        val fg = foregroundPackage ?: if (enabled && rootOk && targetPackage != null) runCatching { io.resumedPackage(Display.DEFAULT_DISPLAY) }.getOrNull() else null
        val decision = RoutingPolicy.decide(
            RouteInput(
                enabled = enabled,
                rootAvailable = rootOk,
                supported = true,
                targetPackage = targetPackage,
                foregroundPackage = fg,
                selfPackage = app.packageName,
                launcherPackages = runCatching { io.launcherPackages() }.getOrDefault(emptySet()),
            ),
        )
        val existing = leases.current
        if (decision.hidden) {
            try {
                val lease = withContext(Dispatchers.Main) { leases.acquireHidden(targetPackage, conversationId) }
                if (existing == null || existing.main) io.helper("ime-policy", lease.displayId.toString(), IME_POLICY_HIDE.toString())
                watchRun(conversationId)
                return lease
            } catch (e: DisplayUnavailable) {
                return mainLease(targetPackage, conversationId, RouteReason.Unsupported)
            }
        }
        return mainLease(targetPackage, conversationId, decision.reason)
    }

    private suspend fun mainLease(target: String?, conversationId: String?, reason: RouteReason): DisplayLease =
        withContext(Dispatchers.Main) {
            val m = app.resources.displayMetrics
            leases.acquireMain(target, conversationId, reason, m.widthPixels, m.heightPixels, m.densityDpi)
        }

    /** Releases the current lease (and the hidden display with it). */
    fun release() {
        runWatch?.cancel()
        runWatch = null
        main.post { leases.releaseAll() }
    }

    /** Throws [DisplayUnavailable] unless [lease] is still live. */
    fun require(lease: DisplayLease): DisplayLease = leases.require(lease)

    // ------------------------------------------------------------------ Observation and input

    /** Newest frame of the leased display (hidden: from our own frames; main: accessibility only). */
    suspend fun screenshot(lease: DisplayLease): Bitmap? {
        require(lease)
        if (lease.main) return accessibility?.screenshot(lease.displayId)
        accessibility?.screenshot(lease.displayId)?.let { return it }
        return withContext(Dispatchers.IO) { display?.snapshot() }
    }

    suspend fun readTree(lease: DisplayLease, maxChars: Int = 6_000): String? {
        require(lease)
        return accessibility?.readTree(lease.displayId, maxChars)
    }

    suspend fun tap(lease: DisplayLease, x: Int, y: Int, long: Boolean = false): String? {
        require(lease)
        if (accessibility?.tap(lease.displayId, x, y, long) == true) return null
        return io.tap(lease.displayId, x, y, long).also { require(lease) }
    }

    suspend fun swipe(lease: DisplayLease, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): String? {
        require(lease)
        if (accessibility?.swipe(lease.displayId, x1, y1, x2, y2, durationMs) == true) return null
        return io.swipe(lease.displayId, x1, y1, x2, y2, durationMs).also { require(lease) }
    }

    suspend fun type(lease: DisplayLease, text: String): String? {
        require(lease)
        return io.type(lease.displayId, text)
    }

    suspend fun key(lease: DisplayLease, keyCode: Int): String? {
        require(lease)
        return io.key(lease.displayId, keyCode)
    }

    suspend fun launch(lease: DisplayLease, packageName: String): String? {
        require(lease)
        return io.launch(lease.displayId, packageName)
    }

    suspend fun openUri(lease: DisplayLease, uri: String, packageName: String?): String? {
        require(lease)
        return io.openUri(lease.displayId, uri, packageName)
    }

    /** Live preview frames of the hidden display, or null when there is none. */
    internal fun hiddenDisplay(): HiddenDisplay? = display

    internal val rootIo: RootDisplayIo get() = io

    // ------------------------------------------------------------------ Lifecycle

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayRemoved(displayId: Int) {
            if (display?.info?.id != displayId) return
            display?.close()
            display = null
            listen(false)
            leases.onDisplayRemoved(displayId)
        }

        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = Unit
    }

    private var listening = false

    private fun listen(on: Boolean) {
        if (on == listening) return
        val dm = app.getSystemService(DisplayManager::class.java)
        if (on) dm.registerDisplayListener(displayListener, main) else dm.unregisterDisplayListener(displayListener)
        listening = on
    }

    /**
     * Safety net: the hidden display never outlives the run that asked for it. [ownerId] may be a conversation id
     * (then that conversation's run ending releases it) or an id the host does not know, such as a tool-context
     * run id (then it is released once no run is active any more). Event-driven: run updates only.
     */
    private fun watchRun(ownerId: String?) {
        if (runWatch?.isActive == true) return
        val host = try {
            Atom.host
        } catch (e: IllegalStateException) {
            return
        }
        runWatch = scope.launch {
            var seenOwner = false
            var seenActive = false
            host.runs.first { runs ->
                val own = runs.firstOrNull { it.conversationId == ownerId }
                if (own != null) seenOwner = true
                val anyActive = runs.any { it.phase.active }
                if (anyActive) seenActive = true
                when {
                    seenOwner -> own == null || !own.phase.active
                    else -> seenActive && !anyActive
                }
            }
            val held = when (val s = leases.state.value) {
                is LeaseState.Active -> !s.lease.main
                is LeaseState.Lost -> true
                LeaseState.Idle -> false
            }
            if (held) main.post { leases.releaseAll() }
            runWatch = null
        }
    }

    /** WindowManager.DISPLAY_IME_POLICY_HIDE. */
    private const val IME_POLICY_HIDE = 2
}
