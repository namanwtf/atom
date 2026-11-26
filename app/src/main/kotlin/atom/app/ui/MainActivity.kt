package atom.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import atom.app.ChatRequest
import atom.app.data.AppSettings
import atom.app.graph
import atom.app.integration.NavRequests
import atom.automation.AutomationFeature
import atom.general.appearance.AppearanceStore
import atom.general.appearance.rememberAppearance
import atom.overlay.OverlayHooks
import atom.ui.theme.AtomTheme
import atom.ui.theme.isDark
import atom.voice.Wake
import atom.voice.WakePolicy
import atom.voice.speech.Speech
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Set once the appearance and settings are loaded; until then the system launch splash stays up. */
    @Volatile
    private var uiReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        holdSplashUntilReady()
        if (savedInstanceState == null) handleIntent(intent)
        // One-time migration: the Appearance screen takes over the old theme setting.
        lifecycleScope.launch { AppearanceStore.get(this@MainActivity).seedModeIfUnset(graph.settings.current().theme) }
        setContent {
            val settings by graph.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            val loaded: AppSettings? = settings
            val appearance = rememberAppearance()
            val dark = isDark(appearance?.mode ?: loaded?.theme ?: AppSettings().theme, isSystemInDarkTheme())
            DisposableEffect(dark) {
                val bars = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                onDispose { }
            }
            if (appearance == null) {
                // DataStore answers within a frame or two; avoid flashing the wrong theme or first screen.
                AtomTheme(mode = loaded?.theme ?: AppSettings().theme) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                }
            } else {
                AtomTheme(appearance) {
                    if (loaded == null) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                    } else {
                        SideEffect { uiReady = true }
                        AtomRoot(loaded)
                    }
                }
            }
        }
    }

    /**
     * Keeps the system launch splash on screen until the first frame can use the saved theme and UI style, so the
     * app never flashes the wrong colours. A slow store gives up after [SPLASH_MAX_MS] and draws anyway.
     */
    private fun holdSplashUntilReady() {
        val content = findViewById<View>(android.R.id.content) ?: return
        val started = SystemClock.uptimeMillis()
        content.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    val go = uiReady || SystemClock.uptimeMillis() - started > SPLASH_MAX_MS
                    if (go) content.viewTreeObserver.removeOnPreDrawListener(this)
                    return go
                }
            },
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        graph.uiVisible = true
        graph.notifications.cancelApproval()
        // The microphone service may only be started from the foreground, so the saved choice is applied here.
        lifecycleScope.launch {
            val s = graph.settings.current()
            val policy = WakePolicy(screenOffListening = s.heyAtomScreenOff, listenWhileCharging = Speech.config.value.wakeWhileCharging)
            Wake.setEnabled(this@MainActivity, s.heyAtom, policy)
        }
    }

    /** Prompts (voice commands), conversation links (overlay, automations), routes (overlay settings links), assist. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_PROMPT)?.takeIf { it.isNotBlank() }?.let { prompt ->
            intent.removeExtra(EXTRA_PROMPT)
            graph.tasks.start(base = null, prompt = prompt)
        }
        val conversationId = intent.getStringExtra(OverlayHooks.EXTRA_CONVERSATION_ID)
            ?: intent.getStringExtra(AutomationFeature.EXTRA_CONVERSATION_ID)
        if (conversationId != null) {
            intent.removeExtra(OverlayHooks.EXTRA_CONVERSATION_ID)
            intent.removeExtra(AutomationFeature.EXTRA_CONVERSATION_ID)
            graph.postChatRequest(ChatRequest.Open(conversationId))
        }
        intent.getStringExtra(EXTRA_ROUTE)?.takeIf { it.isNotBlank() }?.let { route ->
            intent.removeExtra(EXTRA_ROUTE)
            NavRequests.open(route)
        }
        if (intent.action == Intent.ACTION_ASSIST) {
            intent.action = null
            if (conversationId == null) NavRequests.reset()
        }
    }

    companion object {
        private const val SPLASH_MAX_MS = 1_500L
        private const val EXTRA_PROMPT = "atom.app.EXTRA_PROMPT"
        private const val EXTRA_ROUTE = "atom.app.EXTRA_ROUTE"

        fun promptIntent(context: Context, prompt: String): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_PROMPT, prompt)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /** Opens the chat on [conversationId] (just the app when null). */
        fun conversationIntent(context: Context, conversationId: String?): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply { if (conversationId != null) putExtra(OverlayHooks.EXTRA_CONVERSATION_ID, conversationId) }

        /** Opens one screen, e.g. "models" or a feature route such as "voice/settings". */
        fun routeIntent(context: Context, route: String): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_ROUTE, route)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    override fun onStop() {
        graph.uiVisible = false
        // Re-post the approval prompt as a notification if one is still pending once we leave.
        graph.tasks.live.value?.pending?.let { graph.notifications.showApproval(it) }
        super.onStop()
    }
}
