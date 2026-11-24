package atom.voice.assist

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import atom.voice.assist.AssistLaunch.intent

/**
 * Atom as the system digital assistant. The system binds this only while Atom holds ROLE_ASSISTANT; it does no
 * work of its own: no microphone, no hotword, no background jobs.
 */
class AtomVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        // Do not make the system capture the screen contents/screenshot on every assist gesture: Atom does not
        // use them, and skipping them saves CPU and keeps the screen private.
        setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
    }
}

class AtomVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AtomVoiceInteractionSession(this)
}

/** Has no UI of its own: on the assist gesture it opens Atom's activity and hides itself. */
internal class AtomVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val launch = intent(context, fromAssist = true)
        if (launch != null) {
            try {
                startAssistantActivity(launch)
            } catch (e: RuntimeException) {
                runCatching { context.startActivity(launch) }
            }
        }
        hide()
    }
}
