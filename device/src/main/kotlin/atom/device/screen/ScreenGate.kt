package atom.device.screen

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import atom.ui.feature.ScreenWait
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Screen actions need the screen on and unlocked: with the screen off or locked a tap lands on the lock screen
 * and a read sees nothing, so the agent would only spin. When the screen goes off mid-task, the next screen action
 * waits here instead: no polling, just the screen-on and user-present broadcasts while it waits, and
 * [ScreenWait] shows "Waiting for the screen to be unlocked". It goes on as soon as the user unlocks.
 */
internal object ScreenGate {
    /** How long one action waits before telling the agent the screen stayed locked. */
    const val WAIT_MS = 10 * 60 * 1000L

    const val LOCKED_TOO_LONG = atom.core.tool.PhoneScreen.LOCKED_TOO_LONG

    const val LOCKED_NOW = "The phone's screen is off or locked, so the screen cannot be read or used right now. " +
        "Try the screen action again; it waits for the user to unlock."

    /** Installs the gate the agent loop uses before screen tools. */
    fun install(context: Context) {
        val app = context.applicationContext
        atom.core.tool.PhoneScreen.gate = atom.core.tool.PhoneScreen.Gate { taskId -> await(app, taskId) }
    }

    fun usable(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        return power?.isInteractive != false && keyguard?.isKeyguardLocked != true
    }

    /** True at once when the screen is usable; otherwise waits (up to [WAIT_MS]) for it, marking [taskId] as waiting. */
    suspend fun await(context: Context, taskId: String): Boolean {
        if (usable(context)) return true
        val token = ScreenWait.begin(taskId)
        try {
            return withTimeoutOrNull(WAIT_MS) {
                suspendCancellableCoroutine { cont ->
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(c: Context, intent: Intent) {
                            if (usable(context) && cont.isActive) {
                                runCatching { context.unregisterReceiver(this) }
                                cont.resume(true)
                            }
                        }
                    }
                    ContextCompat.registerReceiver(
                        context,
                        receiver,
                        IntentFilter().apply {
                            addAction(Intent.ACTION_SCREEN_ON)
                            addAction(Intent.ACTION_USER_PRESENT)
                        },
                        ContextCompat.RECEIVER_NOT_EXPORTED,
                    )
                    cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
                    // It may have come back between the first check and the registration.
                    if (usable(context) && cont.isActive) {
                        runCatching { context.unregisterReceiver(receiver) }
                        cont.resume(true)
                    }
                }
            } ?: false
        } finally {
            ScreenWait.end(token)
        }
    }
}
