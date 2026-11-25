package atom.overlay.assistant

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import atom.overlay.window.WindowOwner
import atom.voice.assist.AssistSessionHandle
import atom.voice.assist.AssistUi

/** Bridges one assistant session window to [AssistantController] and the Compose overlay. */
internal class AssistantUi : AssistUi {
    private val owner = WindowOwner()
    private var session: AssistSessionHandle? = null

    override fun createView(session: AssistSessionHandle): View {
        this.session = session
        return owner.root(session.sessionContext()) { AssistantScreen() }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        val s = session ?: return
        owner.resume()
        AssistantController.onShow(s, args, showFlags)
    }

    override fun onAssist(structure: AssistStructure?, content: AssistContent?) {
        val s = session ?: return
        AssistantController.onAssist(s.sessionContext(), structure, content)
    }

    override fun onScreenshot(screenshot: Bitmap?) = AssistantController.onScreenshot(screenshot)

    override fun onHide() {
        session?.let { AssistantController.onHide(it) }
        owner.pause()
    }

    override fun onDestroy() {
        session?.let { AssistantController.onDestroy(it) }
        session = null
        owner.destroy()
    }
}
