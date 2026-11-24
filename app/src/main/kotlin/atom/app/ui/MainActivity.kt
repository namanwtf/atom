package atom.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import atom.voice.Wake
import atom.voice.WakePolicy
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import atom.app.data.AppSettings
import atom.app.graph
import atom.app.ui.theme.AtomTheme
import atom.app.ui.theme.isDark

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handlePrompt(intent)
        setContent {
            val settings by graph.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            val loaded: AppSettings? = settings
            val dark = isDark(loaded?.theme ?: AppSettings().theme, isSystemInDarkTheme())
            DisposableEffect(dark) {
                val bars = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                onDispose { }
            }
            AtomTheme(mode = loaded?.theme ?: AppSettings().theme) {
                if (loaded == null) {
                    // DataStore answers within a frame or two; avoid flashing the wrong first screen.
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                } else {
                    AtomRoot(loaded)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePrompt(intent)
    }

    override fun onStart() {
        super.onStart()
        graph.uiVisible = true
        graph.notifications.cancelApproval()
        // The microphone service may only be started from the foreground, so the saved choice is applied here.
        lifecycleScope.launch {
            val s = graph.settings.current()
            Wake.setEnabled(this@MainActivity, s.heyAtom, WakePolicy(screenOffListening = s.heyAtomScreenOff))
        }
    }

    private fun handlePrompt(intent: Intent?) {
        val prompt = intent?.getStringExtra(EXTRA_PROMPT)?.takeIf { it.isNotBlank() } ?: return
        intent.removeExtra(EXTRA_PROMPT)
        graph.tasks.start(base = null, prompt = prompt)
    }

    companion object {
        private const val EXTRA_PROMPT = "atom.app.EXTRA_PROMPT"

        fun promptIntent(context: Context, prompt: String): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_PROMPT, prompt)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    override fun onStop() {
        graph.uiVisible = false
        // Re-post the approval prompt as a notification if one is still pending once we leave.
        graph.tasks.live.value?.pending?.let { graph.notifications.showApproval(it) }
        super.onStop()
    }
}
