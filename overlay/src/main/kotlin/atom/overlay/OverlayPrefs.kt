package atom.overlay

import android.content.Context
import android.content.SharedPreferences
import atom.overlay.bubble.Docked
import atom.overlay.bubble.Side
import atom.voice.speech.Speech
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The overlay's few settings and remembered state. Tiny and synchronous, so SharedPreferences. */
internal class OverlayPrefs private constructor(private val sp: SharedPreferences) {
    // On for new installs, as before; it only shows once "display over other apps" is allowed.
    private val _bubble = MutableStateFlow(sp.getBoolean(K_BUBBLE, true))
    private val _chips = MutableStateFlow(sp.getBoolean(K_CHIPS, true))
    private val _glow = MutableStateFlow(sp.getBoolean(K_GLOW, true))

    val bubbleEnabled: StateFlow<Boolean> = _bubble.asStateFlow()

    /**
     * "Speak answers automatically" for the assistant overlay. One setting with Settings → Speech: it is stored in
     * the speech config, and both screens read and write it there.
     */
    val autoSpeak: StateFlow<Boolean> get() = speakFlow
    val suggestionChips: StateFlow<Boolean> = _chips.asStateFlow()
    val edgeGlow: StateFlow<Boolean> = _glow.asStateFlow()

    fun setBubbleEnabled(on: Boolean) = put(K_BUBBLE, on, _bubble)
    fun setAutoSpeak(on: Boolean) {
        Speech.update { it.copy(overlaySpeakAnswers = on) }
        if (sp.contains(K_AUTO_SPEAK)) sp.edit().remove(K_AUTO_SPEAK).apply()
    }

    /** Moves an older overlay-only "read answers aloud" choice into the shared speech setting, once. */
    fun migrateAutoSpeak() {
        if (!sp.contains(K_AUTO_SPEAK)) return
        val legacy = sp.getBoolean(K_AUTO_SPEAK, false)
        sp.edit().remove(K_AUTO_SPEAK).apply()
        if (legacy && !Speech.config.value.overlaySpeakAnswers) Speech.update { it.copy(overlaySpeakAnswers = true) }
    }

    /** The bubble's status line keeps its "hold the bubble for actions" hint until the hold menu was opened once. */
    var menuDiscovered: Boolean
        get() = sp.getBoolean(K_MENU_SEEN, false)
        set(value) {
            if (value != menuDiscovered) sp.edit().putBoolean(K_MENU_SEEN, value).apply()
        }

    fun setSuggestionChips(on: Boolean) = put(K_CHIPS, on, _chips)
    fun setEdgeGlow(on: Boolean) = put(K_GLOW, on, _glow)

    /** Where the bubble rests; null until it was first placed (or after a reset). */
    var dock: Docked?
        get() {
            if (!sp.contains(K_SIDE)) return null
            val side = if (sp.getString(K_SIDE, null) == Side.Left.name) Side.Left else Side.Right
            return Docked(side, sp.getFloat(K_FRACTION, DEFAULT_FRACTION))
        }
        set(value) {
            sp.edit().apply {
                if (value == null) {
                    remove(K_SIDE)
                    remove(K_FRACTION)
                } else {
                    putString(K_SIDE, value.side.name)
                    putFloat(K_FRACTION, value.fraction)
                }
            }.apply()
        }

    /** The mini chat's one ongoing conversation. */
    var bubbleConversation: String?
        get() = sp.getString(K_CONVERSATION, null)
        set(value) = sp.edit().putString(K_CONVERSATION, value).apply()

    var bubbleLog: String?
        get() = sp.getString(K_LOG, null)
        set(value) = sp.edit().putString(K_LOG, value).apply()

    private fun put(key: String, value: Boolean, flow: MutableStateFlow<Boolean>) {
        flow.value = value
        sp.edit().putBoolean(key, value).apply()
    }

    companion object {
        /** Default rest: the right edge, about 78% of the way down. */
        const val DEFAULT_FRACTION = 0.78f
        private val speakFlow: StateFlow<Boolean> by lazy {
            val flow = MutableStateFlow(Speech.config.value.overlaySpeakAnswers)
            OverlayRuntime.scope.launch { Speech.config.collect { flow.value = it.overlaySpeakAnswers } }
            flow.asStateFlow()
        }
        private const val K_BUBBLE = "bubble_enabled"
        private const val K_AUTO_SPEAK = "assistant_auto_speak"
        private const val K_CHIPS = "assistant_chips"
        private const val K_GLOW = "edge_glow"
        private const val K_SIDE = "bubble_side"
        private const val K_FRACTION = "bubble_fraction"
        private const val K_CONVERSATION = "bubble_conversation"
        private const val K_LOG = "bubble_log"
        private const val K_MENU_SEEN = "bubble_menu_seen"

        @Volatile
        private var instance: OverlayPrefs? = null

        fun get(context: Context): OverlayPrefs = instance ?: synchronized(this) {
            instance ?: OverlayPrefs(
                context.applicationContext.getSharedPreferences("atom4_overlay", Context.MODE_PRIVATE),
            ).also { instance = it }
        }
    }
}
