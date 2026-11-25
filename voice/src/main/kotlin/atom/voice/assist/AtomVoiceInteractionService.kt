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
 * Atom's activity and hides itself.
 */
internal class AtomVoiceInteractionSession(context: Context) : VoiceInteractionSession(context), AssistSessionHandle {
    private val ui: AssistUi? = runCatching { AssistOverlay.factory?.invoke() }.getOrNull()

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

    override fun onCreateContentView(): View? = ui?.createView(this) ?: super.onCreateContentView()

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val overlay = ui
        if (overlay != null) {
            overlay.onShow(args, showFlags)
            return
        }
        // From the lock screen the activity is started behind the keyguard and shows right after the user unlocks.
        intent(context, fromAssist = true)?.let { launch(it) }
        hide()
    }

    override fun onHandleAssist(state: AssistState) {
        if (state.isFocused) ui?.onAssist(state.assistStructure, state.assistContent)
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        ui?.onScreenshot(screenshot)
    }

    override fun onBackPressed() {
        if (ui?.onBack() != true) super.onBackPressed()
    }

    override fun onHide() {
        ui?.onHide()
        super.onHide()
    }

    override fun onDestroy() {
        ui?.onDestroy()
        super.onDestroy()
    }

    override fun sessionContext(): Context = context

    override fun isLocked(): Boolean =
        runCatching { context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true }.getOrDefault(false)

    override fun dismiss() {
        hide()
    }

    override fun launch(intent: Intent) {
        try {
            startAssistantActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: RuntimeException) {
            runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        if (ui != null) hide()
    }
}
