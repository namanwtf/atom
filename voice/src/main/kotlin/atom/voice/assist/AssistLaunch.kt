package atom.voice.assist

import android.content.Context
import android.content.Intent
import atom.voice.AssistantRole
import atom.voice.Wake

/** Builds the intent for Atom's UI from the component the app registered (falls back to the launcher activity). */
internal object AssistLaunch {
    fun intent(context: Context, fromAssist: Boolean): Intent? {
        val component = Wake.assistComponent
        val intent = if (component != null) {
            Intent(Intent.ACTION_MAIN).setComponent(component)
        } else {
            context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        }
        return intent
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(AssistantRole.EXTRA_FROM_ASSIST, fromAssist)
    }
}
