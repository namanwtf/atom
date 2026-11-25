package atom.voice.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import atom.ui.feature.AtomFeature
import atom.ui.feature.FeatureEntry
import atom.ui.feature.FeatureNav
import atom.ui.feature.FeatureSection
import atom.ui.theme.AtomIcons
import atom.voice.R
import atom.voice.speech.Speech

/** Routes owned by the voice feature. */
object VoiceRoutes {
    const val SETTINGS = "voice/settings"
    const val AUDIO_CHAT = "voice/chat"

    /** Audio chat continuing [conversationId] (a new conversation when null). */
    fun audioChat(conversationId: String? = null): String =
        if (conversationId == null) AUDIO_CHAT else "$AUDIO_CHAT?c=${Uri.encode(conversationId)}"
}

/**
 * Speech: recognition (on-device + cloud), spoken answers, read-aloud, Hey Atom settings and the hands-free
 * audio chat. Idle cost is zero: nothing listens or plays unless the user starts it (Hey Atom is its own opt-in
 * service with its own policy).
 */
object VoiceFeature : AtomFeature {
    override val id = "voice"

    override fun onAppCreate(context: Context) {
        Speech.init(context)
        // Creates "Assistant voice" and removes the old "Reading aloud" channel at start, not at first use.
        runCatching { atom.voice.notify.AssistantVoiceNotice.ensureChannel(context.applicationContext) }
    }

    override val entries: List<FeatureEntry> = listOf(
        FeatureEntry(FeatureSection.General, R.string.speech_title, R.string.speech_subtitle, AtomIcons.Mic, VoiceRoutes.SETTINGS),
        FeatureEntry(FeatureSection.General, R.string.speech_audio_chat_title, R.string.speech_audio_chat_subtitle, AtomIcons.Mic, VoiceRoutes.AUDIO_CHAT),
    )

    @Composable
    override fun Screen(route: String, nav: FeatureNav) {
        when {
            route == VoiceRoutes.SETTINGS -> SpeechSettingsScreen(nav)
            route.startsWith(VoiceRoutes.AUDIO_CHAT) -> {
                val id = route.substringAfter("?c=", "").takeIf { it.isNotEmpty() }?.let { Uri.decode(it) }
                AudioChatScreen(nav, id)
            }
            else -> SpeechSettingsScreen(nav)
        }
    }
}
