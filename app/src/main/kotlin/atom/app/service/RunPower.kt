package atom.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Pure policy for what the runtime holds while runs execute. */
object RunPowerPolicy {
    /** A partial wake lock is only worth holding while work runs and the screen is off (the CPU is awake otherwise). */
    fun wantsWakeLock(executing: Int, interactive: Boolean): Boolean = executing > 0 && !interactive

    fun keepsScreenOn(setting: Boolean, executing: Int, timedOut: Boolean): Boolean = setting && executing > 0 && !timedOut
}

/**
 * Wake lock and keep-screen-on for running tasks, driven only by events: run start/stop/pause, step progress and
 * screen on/off broadcasts (registered only while something runs). The wake lock is partial, so it never turns the
 * screen on for a phone the user locked; it lets the CPU finish the task. Both have safety timeouts.
 */
class RunPower(private val context: Context, private val scope: CoroutineScope) {
    private val power = context.getSystemService(PowerManager::class.java)
    private var wakeLock: PowerManager.WakeLock? = null
    private var receiverRegistered = false
    private var executing = 0
    private var keepSetting = true
    private var screenTimedOut = false
    private var screenTimer: Job? = null

    private val _keepScreenOn = MutableStateFlow(false)

    /** True while windows of Atom (activity, overlays) should carry FLAG_KEEP_SCREEN_ON. */
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            synchronized(this@RunPower) { applyWakeLockLocked() }
        }
    }

    /** [executingRuns] counts active runs that are not paused. */
    @Synchronized
    fun update(executingRuns: Int, keepScreenOnSetting: Boolean) {
        val was = executing
        executing = executingRuns
        keepSetting = keepScreenOnSetting
        if (executing > 0 && !receiverRegistered) {
            ContextCompat.registerReceiver(
                context,
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        } else if (executing == 0 && receiverRegistered) {
            runCatching { context.unregisterReceiver(screenReceiver) }
            receiverRegistered = false
        }
        if (was == 0 && executing > 0) {
            screenTimedOut = false
            armScreenTimerLocked()
        }
        if (executing == 0) {
            screenTimer?.cancel()
            screenTimer = null
        }
        applyWakeLockLocked()
        publishLocked()
    }

    /** A step finished or text streamed: the task is alive, so both safety timeouts start over. */
    @Synchronized
    fun progress() {
        if (executing == 0) return
        if (screenTimedOut) {
            screenTimedOut = false
            publishLocked()
        }
        armScreenTimerLocked()
        wakeLock?.takeIf { it.isHeld }?.acquire(WAKE_TIMEOUT_MS)
    }

    private fun armScreenTimerLocked() {
        screenTimer?.cancel()
        screenTimer = scope.launch {
            delay(SCREEN_SAFETY_MS)
            synchronized(this@RunPower) {
                screenTimedOut = true
                publishLocked()
            }
        }
    }

    private fun publishLocked() {
        _keepScreenOn.value = RunPowerPolicy.keepsScreenOn(keepSetting, executing, screenTimedOut)
    }

    private fun applyWakeLockLocked() {
        val interactive = power?.isInteractive != false
        if (RunPowerPolicy.wantsWakeLock(executing, interactive)) {
            if (wakeLock?.isHeld != true) {
                wakeLock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)?.apply {
                    setReferenceCounted(false)
                    acquire(WAKE_TIMEOUT_MS)
                }
            }
        } else {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
    }

    private companion object {
        const val WAKE_TAG = "atom:task"

        /** Re-armed on every step, so only a run that stopped making progress lets it lapse. */
        const val WAKE_TIMEOUT_MS = 10 * 60 * 1000L

        /** A run that has shown no progress for this long no longer holds the screen on. */
        const val SCREEN_SAFETY_MS = 10 * 60 * 1000L
    }
}
