package atom.vdisplay.viewfinder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import atom.ui.theme.AtomTheme
import atom.ui.theme.ThemeMode

/** The stage when Atom may not draw over other apps: same content, in a translucent activity. */
class StageActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Stage.open) {
            finish()
            return
        }
        Stage.attachActivity { finish() }
        setContent { AtomTheme(ThemeMode.Dark) { StageContent() } }
    }

    override fun onDestroy() {
        if (isFinishing) Stage.detachActivity()
        super.onDestroy()
    }
}
