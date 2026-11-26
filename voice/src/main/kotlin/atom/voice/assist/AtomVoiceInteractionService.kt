package atom.voice.assist

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.View
import android.view.WindowManager
import atom.voice.assist.AssistLaunch.intent

/**
 * Atom as the system digital assistant. The system binds this only while Atom holds ROLE_ASSISTANT; it does no
 * work of its own: no microphone, no hotword, no background jobs.
 */
class AtomVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        active = this
        // Screen context is only worth its capture cost when the overlay can use it; the plain activity launch cannot.
        val disabled = if (AssistOverlay.factory != null) {
            0
        } else {
            VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT
        }
        setDisabledShowContext(disabled)
    }

    /**
     * The lock screen's voice-assist button. The session it opens shows nothing while locked: it asks the user to
     * unlock and then continues (see [AssistLockPolicy]).
     */
    override fun onLaunchVoiceAssistFromKeyguard() {
        runCatching { showSession(Bundle(), 0) }
    }

    override fun onShutdown() {
        if (active === this) active = null
        super.onShutdown()
    }

    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }

    internal companion object {
        @Volatile
        var active: AtomVoiceInteractionService? = null
    }
}

class AtomVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AtomVoiceInteractionSession(this)
}

/**
 * Draws the overlay UI from [AssistOverlay.factory] edge to edge over the current app. Without a UI it opens
 * Atom's activity and hides itself. On a locked phone it draws nothing at all (the window stays disabled, so no
 * chat, history or result appears over the keyguard and nothing runs): it asks the user to unlock first and the
 * same request continues afterwards ([AssistUnlockActivity]).
 */
internal class AtomVoiceInteractionSession(context: Context) : VoiceInteractionSession(context), AssistSessionHandle {
    private val ui: AssistUi? = runCatching { AssistOverlay.factory?.invoke() }.getOrNull()

    /** The overlay's view exists; it is created lazily so a session first shown on the lock screen builds none. */
    private var viewCreated = false

    /** This show happened on the lock screen: every UI callback is ignored until an unlocked show. */
    private var lockedShow = false

    override fun onCreate() {
        super.onCreate()
        if (ui == null) return
        runCatching {
            window.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setDecorFitsSystemWindows(false)
                @Suppress("DEPRECATION")
                statusBarColor = Color.TRANSPARENT
                @Suppress("DEPRECATION")
                navigationBarColor = Color.TRANSPARENT
                isNavigationBarContrastEnforced = false
                isStatusBarContrastEnforced = false
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                attributes = attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            }
        }
    }

    override fun onCreateContentView(): View? {
        val overlay = ui ?: return super.onCreateContentView()
        if (isLocked()) return super.onCreateContentView()
        viewCreated = true
        return overlay.createView(this)
    }

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        // Decided before the window is shown, so a locked show never draws a frame.
        setUiEnabled(AssistLockPolicy.uiEnabled(isLocked()))
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val resuming = args?.containsKey(AssistOverlay.EXTRA_RESUME_CONVERSATION) == true
        when (AssistLockPolicy.decide(locked = isLocked(), hasOverlay = ui != null, resumingResult = resuming)) {
            AssistLockPolicy.Decision.Drop -> {
                lockedShow = true
                hide()
            }
            AssistLockPolicy.Decision.UnlockFirst -> {
                lockedShow = true
                runCatching { startAssistantActivity(AssistUnlockActivity.intent(context, args)) }
                    .onFailure { runCatching { context.startActivity(AssistUnlockActivity.intent(context, args)) } }
                hide()
            }
            AssistLockPolicy.Decision.Overlay -> {
                lockedShow = false
                val overlay = ui ?: return
                if (!viewCreated) {
                    setContentView(overlay.createView(this))
                    viewCreated = true
                }
                overlay.onShow(args, showFlags)
            }
            AssistLockPolicy.Decision.LaunchApp -> {
                lockedShow = false
                intent(context, fromAssist = true)?.let { launch(it) }
                hide()
            }
        }
    }

    private val uiLive: Boolean get() = viewCreated && !lockedShow

    override fun onHandleAssist(state: AssistState) {
        if (uiLive && state.isFocused) ui?.onAssist(state.assistStructure, state.assistContent)
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        if (uiLive) ui?.onScreenshot(screenshot)
    }

    override fun onBackPressed() {
        if (!uiLive || ui?.onBack() != true) super.onBackPressed()
    }

    override fun onHide() {
        if (viewCreated) ui?.onHide()
        super.onHide()
    }

    override fun onDestroy() {
        if (viewCreated) ui?.onDestroy()
        super.onDestroy()
    }

    override fun sessionContext(): Context = context

    override fun isLocked(): Boolean =
        runCatching { context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true }.getOrDefault(false)

    override fun dismiss() {
        hide()
    }

    override fun launch(intent: Intent) {
        // Never start Atom's UI over the keyguard: unlock first, then the same request continues.
        if (isLocked()) {
            runCatching { startAssistantActivity(AssistUnlockActivity.intent(context, null)) }
            hide()
            return
        }
        try {
            startAssistantActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: RuntimeException) {
            runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        if (ui != null) hide()
    }
}
