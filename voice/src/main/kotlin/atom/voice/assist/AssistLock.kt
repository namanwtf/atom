package atom.voice.assist

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * What the assistant session does when it is shown. Assist may be launched from the lock screen, but nothing of
 * Atom is shown or run there: no tools, no conversation history, no memory, no resumed result. A locked phone
 * first asks the user to unlock (the system bouncer); once unlocked the very same request continues. Pure, so it
 * is unit-tested.
 */
object AssistLockPolicy {
    enum class Decision {
        /** Locked: show nothing, ask to unlock, then continue with the same request. */
        UnlockFirst,

        /** Unlocked, with the overlay UI installed: show it. */
        Overlay,

        /** Unlocked, no overlay UI: open Atom's activity. */
        LaunchApp,

        /**
         * Locked, and Atom itself asked to re-show a finished result: show nothing and do not prompt (the user is
         * not there); the result stays in the conversation and its notification.
         */
        Drop,
    }

    /** [resumingResult] is true when Atom re-shows itself to present a result, not when the user invoked it. */
    fun decide(locked: Boolean, hasOverlay: Boolean, resumingResult: Boolean = false): Decision = when {
        locked && resumingResult -> Decision.Drop
        locked -> Decision.UnlockFirst
        hasOverlay -> Decision.Overlay
        else -> Decision.LaunchApp
    }

    /** The session window must stay invisible while locked, so nothing (not even an empty chat) draws over the keyguard. */
    fun uiEnabled(locked: Boolean): Boolean = !locked
}

/**
 * Started by the assistant session on a locked phone. It draws nothing, asks the system to dismiss the keyguard
 * (the user unlocks with their PIN, pattern or biometrics) and, once unlocked, shows the assistant again with the
 * original request (a fresh invocation or a result to present). Cancelling the unlock just closes it.
 */
class AssistUnlockActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        val args = intent.getBundleExtra(EXTRA_ARGS) ?: Bundle()
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isKeyguardLocked) {
            proceed(args)
            return
        }
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = proceed(args)

                override fun onDismissCancelled() = finish()

                override fun onDismissError() = finish()
            },
        )
    }

    private fun proceed(args: Bundle) {
        val app = applicationContext
        finish()
        // The keyguard reports itself locked for a moment after a successful dismissal; wait for it to go away so
        // the session does not ask again.
        android.os.Handler(mainLooper).postDelayed({
            if (!AssistOverlay.reshow(args)) {
                AssistLaunch.intent(app, fromAssist = true)?.let { runCatching { app.startActivity(it) } }
            }
        }, RESHOW_DELAY_MS)
    }

    internal companion object {
        const val EXTRA_ARGS = "atom.voice.assist.EXTRA_UNLOCK_ARGS"
        private const val RESHOW_DELAY_MS = 450L

        fun intent(context: Context, args: Bundle?): Intent = Intent(context, AssistUnlockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(EXTRA_ARGS, args ?: Bundle())
    }
}
