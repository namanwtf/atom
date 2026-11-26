package atom.app.debug

import android.content.Intent
import androidx.activity.ComponentActivity

/** Release builds: no debug launch extras. The debug build's version (src/debug) opens a sample conversation. */
internal object DebugLaunch {
    @Suppress("UNUSED_PARAMETER")
    fun handle(activity: ComponentActivity, intent: Intent) = Unit
}
