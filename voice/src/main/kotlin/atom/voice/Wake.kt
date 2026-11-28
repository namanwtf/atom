package atom.voice

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import atom.core.ondevice.WakeModelsLoad
import atom.voice.service.WakeWordService
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Current state of the opt-in "Hey Atom" listener. */
sealed interface WakeState {
    /** Not enabled. */
    data object Off : WakeState

    /** Microphone open; the cheap speech gate runs, the neural models run only while speech is present. */
    data object Listening : WakeState

    /** Enabled, but the microphone is fully released because of [reason] (screen off, battery saver, call, ...). */
    data class Paused(val reason: String) : WakeState

    /** Cannot run at all until something changes (missing model, missing permission, background start, ...). */
    data class Unavailable(val reason: String) : WakeState
}

/**
 * @param screenOffListening listen with the screen off even on battery. Off by default: with the screen off an
 *   open microphone keeps the audio path and the CPU out of deep sleep, which is the single most expensive thing
 *   this module could do.
 * @param listenWhileCharging listen with the screen off while the phone is plugged in.
 * @param threshold classifier score (0..1) that counts as a detection; two consecutive frames are required.
 */
data class WakePolicy(
    val screenOffListening: Boolean = false,
    val listenWhileCharging: Boolean = true,
    val threshold: Float = 0.5f,
)

/** A spoken command. [fromWakeWord] is true when it was captured after "Hey Atom". */
data class VoiceCommand(val text: String, val fromWakeWord: Boolean)

/**
 * Where the wake-word models come from: the optional on-device AI pack, which the app wires in. The voice module
 * carries no model and no runtime of its own.
 */
interface WakeModelSource {
    /** True while the pack is installed and usable; the switches follow it. */
    val available: StateFlow<Boolean>

    /** Loads the models (on the listener's background thread). */
    fun load(): WakeModelsLoad
}

object Wake {
    internal const val REASON_NO_PERMISSION = "Microphone permission not granted"
    internal const val REASON_NO_MODEL = "Needs the on-device AI pack (Settings > LLM providers > On-device models)"

    private val noSource = MutableStateFlow(false).asStateFlow()

    /** Set by the app before any screen shows. Null: no pack support, "Hey Atom" stays unavailable. */
    @Volatile
    var modelSource: WakeModelSource? = null

    /** Opens the screen where the pack is downloaded; set by the app. */
    @Volatile
    var openPackSettings: (() -> Unit)? = null

    /**
     * Shows the command capture after the wake word on screen (the overlay feature sets it). Null keeps the old
     * behaviour: a haptic tick, then the app opens with the command.
     */
    @Volatile
    var presenter: WakePresenter? = null

    /** Whether the wake-word models can load, kept current (the pack can be downloaded or removed at any time). */
    val modelAvailable: StateFlow<Boolean> get() = modelSource?.available ?: noSource
    internal const val REASON_BACKGROUND_START =
        "Turn on \"Hey Atom\" while Atom is open: Android does not allow microphone listening to start from the background"

    private val mutableState = MutableStateFlow<WakeState>(WakeState.Off)
    val state: StateFlow<WakeState> = mutableState.asStateFlow()

    private val mutableCommands = MutableSharedFlow<VoiceCommand>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Recognised commands. No replay: collect from an application-scoped coroutine so none are missed. */
    val commands: SharedFlow<VoiceCommand> = mutableCommands.asSharedFlow()

    @Volatile
    internal var policy: WakePolicy = WakePolicy()
        private set

    @Volatile
    internal var assistComponent: ComponentName? = null
        private set

    /** The running service, if any. Touched on the main thread only. */
    internal var service: WakeWordService? = null

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Turns the "Hey Atom" listener on or off, or applies a new [policy] to a running listener.
     *
     * Turning it on must happen while Atom is on screen (Android forbids starting a microphone foreground service
     * from the background); otherwise [state] becomes [WakeState.Unavailable] with an explanation. Safe to call
     * from any thread.
     */
    fun setEnabled(context: Context, enabled: Boolean, policy: WakePolicy = WakePolicy()) {
        val app = context.applicationContext
        this.policy = policy
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyEnabled(app, enabled)
        } else {
            mainHandler.post { applyEnabled(app, enabled) }
        }
    }

    /**
     * Stops a command capture that is listening now, without a result, and goes back to listening for the wake word.
     * Does nothing when no capture runs. Safe to call from any thread.
     */
    fun cancelCapture() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            service?.cancelCapture()
        } else {
            mainHandler.post { service?.cancelCapture() }
        }
    }

    /**
     * Starts a command capture again without the wake word (the overlay's mic button), shown by [presenter]. Main
     * thread only. False when "Hey ATOM" is not running or a capture already runs.
     */
    fun listenAgain(): Boolean = service?.listenAgain() == true

    /** The activity the assist gesture opens (with [AssistantRole.EXTRA_FROM_ASSIST] = true). Call it from `Application.onCreate`. */
    fun setAssistLaunchComponent(component: ComponentName) {
        assistComponent = component
    }

    /** True while the on-device AI pack (which carries the wake-word models) is installed. Cheap: no I/O. */
    @Suppress("UNUSED_PARAMETER")
    fun isModelAvailable(context: Context): Boolean = modelAvailable.value

    internal fun loadModels(): WakeModelsLoad = modelSource?.load() ?: WakeModelsLoad.Failed(REASON_NO_MODEL)

    private fun applyEnabled(app: Context, enabled: Boolean) {
        val running = service
        if (!enabled) {
            if (running != null) running.shutdown() else app.stopService(Intent(app, WakeWordService::class.java))
            publish(WakeState.Off)
            return
        }
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            running?.shutdown()
            publish(WakeState.Unavailable(REASON_NO_PERMISSION))
            return
        }
        if (!isModelAvailable(app)) {
            running?.shutdown()
            publish(WakeState.Unavailable(REASON_NO_MODEL))
            return
        }
        if (running != null) {
            running.onPolicyChanged()
            return
        }
        if (!isAppVisible()) {
            publish(WakeState.Unavailable(REASON_BACKGROUND_START))
            return
        }
        try {
            app.startForegroundService(Intent(app, WakeWordService::class.java))
        } catch (e: IllegalStateException) { // ForegroundServiceStartNotAllowedException on API 31+
            publish(WakeState.Unavailable(REASON_BACKGROUND_START))
        } catch (e: SecurityException) {
            publish(WakeState.Unavailable(REASON_BACKGROUND_START))
        }
    }

    private fun isAppVisible(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    internal fun publish(state: WakeState) {
        mutableState.value = state
    }

    internal fun emit(command: VoiceCommand) {
        mutableCommands.tryEmit(command)
    }
}
