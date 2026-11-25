package atom.voice.assist

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View

/**
 * The UI drawn inside Atom's assistant session window, over whatever app is in front. The overlay feature
 * installs it through [AssistOverlay.factory]; without one the session just opens Atom's activity.
 *
 * All callbacks arrive on the main thread.
 */
interface AssistUi {
    fun createView(session: AssistSessionHandle): View

    /** [args] carries [AssistOverlay.EXTRA_RESUME_CONVERSATION] when Atom re-shows itself to present a result. */
    fun onShow(args: Bundle?, showFlags: Int)

    /** Structured content of the focused app, captured at wake time. Null when the user turned screen context off. */
    fun onAssist(structure: AssistStructure?, content: AssistContent?)

    /** Screenshot taken at wake time. Null when the user turned screenshots off or capture failed. */
    fun onScreenshot(screenshot: Bitmap?)

    fun onHide()

    fun onDestroy()

    /** True when the UI handled Back itself; otherwise the session hides. */
    fun onBack(): Boolean = false
}

/** What the session lets its UI do. */
interface AssistSessionHandle {
    fun sessionContext(): Context

    /** Removes the session window; the app below gets the screen back. */
    fun dismiss()

    /** Starts an activity the way an assistant should (also from the lock screen), then the session hides. */
    fun launch(intent: Intent)

    /**
     * True while the phone is locked. The session never shows its UI then (it asks the user to unlock first), so a
     * UI only sees this turn true if the phone locks while it is open; it should then stop and not reveal anything.
     */
    fun isLocked(): Boolean = false
}

object AssistOverlay {
    /** Set to the conversation id when [reshow] presents a result instead of a fresh invocation. */
    const val EXTRA_RESUME_CONVERSATION = "atom.voice.assist.EXTRA_RESUME_CONVERSATION"

    /** Creates the overlay UI for each new session. Set once from Application.onCreate. */
    @Volatile
    var factory: (() -> AssistUi)? = null

    /**
     * Shows the assistant session again without capturing new screen context, e.g. to present a result after the
     * overlay hid itself so the agent could use the screen. False when Atom is not the active assistant.
     */
    fun reshow(args: Bundle): Boolean {
        val service = AtomVoiceInteractionService.active ?: return false
        return runCatching { service.showSession(args, 0) }.isSuccess
    }

    /** True while Atom is the bound system assistant (it can then show and re-show its overlay). */
    val isActive: Boolean get() = AtomVoiceInteractionService.active != null
}
