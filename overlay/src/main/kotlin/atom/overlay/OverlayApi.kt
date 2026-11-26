package atom.overlay

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import atom.overlay.run.ScreenUse
import atom.ui.feature.HostRun
import atom.ui.feature.RunSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One message in the mini chat or the assistant reply card. */
data class OverlayMessage(val fromUser: Boolean, val text: String)

/**
 * The mini chat's model chip: [label] is the model name when [ready], else what home shows ("Not selected",
 * "Sign in to …"). While not ready a send is blocked with [blocked] (the home chat's "configure a model" line).
 */
data class ModelChipState(val label: String, val ready: Boolean, val blocked: String? = null)

/** What the overlays need from the app. Every member has a working default. */
interface OverlayHooks {
    /** Opens the full app on [conversationId] (the chat list or a new chat when null). */
    fun appIntent(context: Context, conversationId: String?): Intent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
        return launch
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .apply { if (conversationId != null) putExtra(EXTRA_CONVERSATION_ID, conversationId) }
    }

    /** Short label for the model chip, e.g. "DeepSeek Flash". Null shows "Default model". */
    fun modelLabel(source: RunSource): String? = null

    /**
     * The model chip for runs from [source] with the same readiness as the home chat, kept current as the model,
     * keys or sign-ins change. Null falls back to [modelLabel] with no readiness check.
     */
    fun modelChip(source: RunSource): StateFlow<ModelChipState>? = null

    /** Where tapping the model chip goes; null makes the chip read-only. */
    fun modelPickerIntent(context: Context, source: RunSource): Intent? = null

    /** The link shown next to a voice-recognition error. */
    fun voiceSettingsIntent(context: Context): Intent? = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)

    /**
     * True while [run] is using the phone's screen. The default guesses from step tool names; wire it to the real
     * phone-control lease when that exists.
     */
    fun drivesScreen(run: HostRun): Boolean = ScreenUse.looksLikeScreenUse(run)

    /** Earlier messages of [conversationId] for the mini chat. Null uses the overlay's own short log. */
    suspend fun transcript(conversationId: String): List<OverlayMessage>? = null

    /** Settings route for the assistant model and reasoning level. Null hides that row. */
    val assistantModelRoute: String? get() = null

    companion object {
        /** String extra on [appIntent]: the conversation to open. */
        const val EXTRA_CONVERSATION_ID = "atom.overlay.EXTRA_CONVERSATION_ID"

        val Default: OverlayHooks = object : OverlayHooks {}
    }
}

/** Speech to text for the bubble's Talk action, the composer mic and the assistant overlay. */
interface VoiceBridge {
    val available: Boolean

    /** Starts one utterance. Callbacks arrive on the main thread and end with exactly one [Listener.onFinal] or [Listener.onError]. */
    fun listen(context: Context, listener: Listener): Session

    interface Session {
        /** Stops listening and delivers what was heard. */
        fun stop()

        /** Stops listening and delivers nothing. */
        fun cancel()
    }

    interface Listener {
        /** Input level, 0 (silence) to 1 (loud). */
        fun onLevel(level: Float) {}
        fun onPartial(text: String) {}
        fun onFinal(text: String)
        fun onError(message: String)
    }

    object None : VoiceBridge {
        override val available: Boolean get() = false

        override fun listen(context: Context, listener: Listener): Session {
            listener.onError(context.getString(R.string.overlay_voice_not_set_up))
            return NoSession
        }
    }

    object NoSession : Session {
        override fun stop() {}
        override fun cancel() {}
    }
}

/** Text to speech for the assistant's read-aloud button and auto-speak. */
interface ReadAloudBridge {
    val available: Boolean
    val speaking: StateFlow<Boolean>
    fun speak(text: String)
    fun stop()

    object None : ReadAloudBridge {
        override val available: Boolean get() = false
        override val speaking: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
        override fun speak(text: String) {}
        override fun stop() {}
    }
}

/** Slot for the background virtual display's live preview inside the run capsule. */
interface ViewfinderSlot {
    fun shows(run: HostRun): Boolean

    @Composable
    fun Viewfinder(run: HostRun, modifier: Modifier)

    /**
     * "Open screen" on the result card of a run that used the background display: shows what it worked on.
     * Returns false when there is nothing to show.
     */
    fun openScreen(context: Context, conversationId: String): Boolean = false
}
