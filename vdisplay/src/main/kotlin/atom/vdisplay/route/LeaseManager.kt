package atom.vdisplay.route

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Thrown (and reported to the agent) when the hidden display is gone or cannot be created. */
class DisplayUnavailable(message: String) : IllegalStateException(message)

/** A display the agent may observe and act on. */
data class DisplayInfo(val id: Int, val width: Int, val height: Int, val densityDpi: Int)

/**
 * The agent's claim on a display for one run. [main] = the phone's own screen (display 0), else the hidden display.
 * Released through [release]; a lease whose display disappeared stays [LeaseState.Lost] until released, so no
 * coordinates meant for it are ever replayed elsewhere.
 */
class DisplayLease internal constructor(
    val generation: Long,
    val displayId: Int,
    val main: Boolean,
    val targetPackage: String?,
    val conversationId: String?,
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val reason: RouteReason,
    private val releaser: (DisplayLease) -> Unit,
) {
    fun release() = releaser(this)

    override fun toString() =
        "DisplayLease(#$generation display=$displayId main=$main target=$targetPackage ${width}x$height@$densityDpi)"
}

sealed interface LeaseState {
    data object Idle : LeaseState
    data class Active(val lease: DisplayLease) : LeaseState

    /** The hidden display vanished while [lease] used it. */
    data class Lost(val lease: DisplayLease, val why: String) : LeaseState
}

/** Creates and destroys the hidden display; the Android implementation wraps a VirtualDisplay. */
interface DisplayBackend {
    /** Creates a display, or throws [DisplayUnavailable] with a user-readable reason. */
    fun create(): DisplayInfo
    fun destroy(id: Int)
}

/**
 * Lease lifecycle, independent of Android so it is unit tested. At most one hidden display exists; it is created
 * on the first hidden lease and destroyed on release (battery: nothing stays alive between runs). Thread-safe.
 */
class LeaseManager(private val backend: DisplayBackend, private val mainDisplayId: Int = 0) {
    private val lock = Any()
    private var generation = 0L
    private var hidden: DisplayInfo? = null
    private val _state = MutableStateFlow<LeaseState>(LeaseState.Idle)
    val state: StateFlow<LeaseState> = _state.asStateFlow()

    val current: DisplayLease?
        get() = (state.value as? LeaseState.Active)?.lease

    /**
     * True while the hidden display (live, or lost and not yet released) is leased to one of [owners] (a run id or a
     * conversation id). A run working only there never takes phone control, so the run capsule asks this instead.
     */
    fun hiddenHeldBy(owners: Collection<String>): Boolean {
        val lease = when (val s = state.value) {
            is LeaseState.Active -> s.lease.takeIf { !it.main }
            is LeaseState.Lost -> s.lease
            LeaseState.Idle -> null
        } ?: return false
        return lease.conversationId != null && lease.conversationId in owners
    }

    /** A lease on the hidden display, creating it if needed; reuses the display if one is already up. */
    fun acquireHidden(targetPackage: String?, conversationId: String?, reason: RouteReason = RouteReason.Hidden): DisplayLease =
        synchronized(lock) {
            val info = hidden ?: backend.create().also { hidden = it }
            val lease = DisplayLease(++generation, info.id, false, targetPackage, conversationId, info.width, info.height, info.densityDpi, reason, ::release)
            _state.value = LeaseState.Active(lease)
            lease
        }

    /** A lease on the main screen. Any hidden display is released first: the run no longer uses it. */
    fun acquireMain(targetPackage: String?, conversationId: String?, reason: RouteReason, width: Int, height: Int, densityDpi: Int): DisplayLease =
        synchronized(lock) {
            destroyHiddenLocked()
            val lease = DisplayLease(++generation, mainDisplayId, true, targetPackage, conversationId, width, height, densityDpi, reason, ::release)
            _state.value = LeaseState.Active(lease)
            lease
        }

    /** Releases [lease] if it is still the current one (a stale handle cannot release a newer lease). */
    fun release(lease: DisplayLease) = synchronized(lock) {
        val s = _state.value
        val currentGen = when (s) {
            is LeaseState.Active -> s.lease.generation
            is LeaseState.Lost -> s.lease.generation
            LeaseState.Idle -> return@synchronized
        }
        if (currentGen != lease.generation) return@synchronized
        releaseLocked()
    }

    /** Releases whatever is held. */
    fun releaseAll() = synchronized(lock) { releaseLocked() }

    /** The system removed display [id]. */
    fun onDisplayRemoved(id: Int, why: String = "the background display was removed by the system") = synchronized(lock) {
        if (hidden?.id != id) return@synchronized
        hidden = null // already gone: do not destroy again
        val s = _state.value
        if (s is LeaseState.Active && !s.lease.main) _state.value = LeaseState.Lost(s.lease, why)
    }

    /** Throws [DisplayUnavailable] unless [lease] is the live current lease. */
    fun require(lease: DisplayLease): DisplayLease {
        when (val s = state.value) {
            is LeaseState.Active -> if (s.lease.generation == lease.generation) return lease
            is LeaseState.Lost -> if (s.lease.generation == lease.generation) throw DisplayUnavailable(unavailable(s.why))
            LeaseState.Idle -> Unit
        }
        throw DisplayUnavailable(unavailable("this display lease was already released"))
    }

    private fun releaseLocked() {
        destroyHiddenLocked()
        _state.value = LeaseState.Idle
    }

    private fun destroyHiddenLocked() {
        val h = hidden ?: return
        hidden = null
        runCatching { backend.destroy(h.id) }
    }

    companion object {
        fun unavailable(why: String) =
            "Display unavailable: $why. Nothing was done on the main screen. Open the app again (background_open_app) " +
                "and take a fresh look before acting; earlier coordinates are no longer valid."
    }
}
