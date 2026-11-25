package atom.app.integration

import android.content.Context
import android.os.Looper
import atom.app.AppGraph
import atom.app.data.EngineSelection
import atom.core.llm.ChatMessage
import atom.core.llm.LlmEvent
import atom.core.llm.LlmRequest
import atom.overlay.ReadAloudBridge
import atom.overlay.VoiceBridge
import atom.overlay.voice.SystemVoiceBridge
import atom.voice.R as VoiceR
import atom.voice.asr.VoiceInput
import atom.voice.asr.VoiceInputOptions
import atom.voice.asr.VoiceInputState
import atom.voice.notify.AssistantVoiceNotice
import atom.voice.speech.Speech
import atom.voice.speech.SpeechError
import atom.voice.speech.SpeechProofreader
import atom.voice.speech.WakePreferences
import atom.voice.speech.WakePrefs
import atom.voice.tts.ReadAloud
import atom.voice.tts.ReadAloudSource
import atom.voice.tts.ReadAloudState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The overlay's dictation through the voice module's [VoiceInput]: the recognizer the user picked in Speech
 * settings (on-device, Android or a cloud service), with smart correction. Falls back to the plain system
 * recognizer when called off the main thread. The microphone is open only while a session is live.
 */
class ModuleVoiceBridge : VoiceBridge {
    private val fallback = SystemVoiceBridge()

    override val available: Boolean get() = Speech.config.value.recognitionEnabled

    override fun listen(context: Context, listener: VoiceBridge.Listener): VoiceBridge.Session {
        if (Looper.myLooper() != Looper.getMainLooper()) return fallback.listen(context, listener)
        val app = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val input = VoiceInput(app, scope)
        var done = false
        fun end() {
            done = true
            scope.cancel()
            AssistantVoiceNotice.hide(app, AssistantVoiceNotice.Mode.Listening)
        }
        val started = input.start(VoiceInputOptions.assistant(app)) { result ->
            if (done) return@start
            end()
            if (result.text.isBlank()) listener.onError(app.getString(errorText(SpeechError.NoSpeech))) else listener.onFinal(result.text)
        }
        if (!started) {
            val error = (input.state.value as? VoiceInputState.Failed)?.error ?: SpeechError.Server
            end()
            listener.onError(app.getString(errorText(error)))
            return VoiceBridge.NoSession
        }
        scope.launch {
            input.state.collect { s ->
                if (done) return@collect
                when (s) {
                    is VoiceInputState.Active -> {
                        listener.onLevel(s.level)
                        if (s.partial.isNotEmpty()) listener.onPartial(s.partial)
                    }
                    is VoiceInputState.Failed -> {
                        end()
                        listener.onError(app.getString(errorText(s.error)))
                    }
                    VoiceInputState.Idle -> Unit
                }
            }
        }
        // Outside Atom's screens: "Atom is listening" with Stop, which ends capture and delivers what was heard.
        AssistantVoiceNotice.showListening(app) { if (!done) input.finish() }
        return object : VoiceBridge.Session {
            override fun stop() {
                if (!done) input.finish()
            }

            override fun cancel() {
                if (done) return
                input.cancel()
                end()
            }
        }
    }

    private fun errorText(error: SpeechError): Int = when (error) {
        SpeechError.NoPermission -> VoiceR.string.speech_err_no_permission
        SpeechError.Disabled -> VoiceR.string.speech_err_disabled
        SpeechError.MissingKey -> VoiceR.string.speech_err_missing_key
        SpeechError.CredentialUnreadable -> VoiceR.string.speech_err_unreadable_key
        SpeechError.MissingStorage -> VoiceR.string.speech_err_missing_storage
        SpeechError.Auth -> VoiceR.string.speech_err_auth
        SpeechError.Quota -> VoiceR.string.speech_err_quota
        SpeechError.Network -> VoiceR.string.speech_err_network
        SpeechError.Timeout -> VoiceR.string.speech_err_timeout
        SpeechError.NoSpeech -> VoiceR.string.speech_err_no_speech
        SpeechError.TooLong -> VoiceR.string.speech_err_too_long
        SpeechError.Busy -> VoiceR.string.speech_err_busy
        SpeechError.Unsupported -> VoiceR.string.speech_err_unsupported
        SpeechError.Server -> VoiceR.string.speech_err_server
    }
}

/** The assistant overlay's read-aloud through the voice module (the user's chosen voice, system voice fallback). */
class ModuleReadAloudBridge(scope: CoroutineScope) : ReadAloudBridge {
    override val available: Boolean get() = true

    override val speaking: StateFlow<Boolean> = ReadAloud.state
        .map { it != ReadAloudState.Idle }
        .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override fun speak(text: String) = ReadAloud.speak(text, ReadAloudSource.Overlay)

    override fun stop() = ReadAloud.stop()
}

/**
 * "Hey Atom" switches stored in the app settings (Setup and the start-up re-apply read them there); the
 * while-charging choice has no app setting, so it stays in the speech config.
 */
class AppWakePreferences(private val graph: AppGraph) : WakePreferences {
    override val current: Flow<WakePrefs> = combine(graph.settings.settings, Speech.config) { s, c ->
        WakePrefs(enabled = s.heyAtom, screenOff = s.heyAtomScreenOff, whileCharging = c.wakeWhileCharging)
    }

    override suspend fun set(prefs: WakePrefs) {
        graph.settings.setHeyAtom(prefs.enabled)
        graph.settings.setHeyAtomScreenOff(prefs.screenOff)
        if (Speech.config.value.wakeWhileCharging != prefs.whileCharging) Speech.update { it.copy(wakeWhileCharging = prefs.whileCharging) }
    }
}

/**
 * Smart correction on the current chat's model (light reasoning): the conversation the chat shows, else the
 * conversation running most recently, else the global default model a new chat would use. The raw text is kept
 * when that model fails or takes too long (VoiceInput caps the call).
 */
class ChatProofreader(private val graph: AppGraph) : SpeechProofreader {
    override suspend fun proofread(instructions: String, text: String): String? {
        val selection = currentChatModel() ?: return null
        val routed = graph.router.acquire(selection)
        try {
            val reasoning = if (routed.reasoning.supported) routed.reasoning.levels.firstOrNull() else null
            val request = LlmRequest(
                model = routed.model,
                messages = listOf(ChatMessage.system(instructions), ChatMessage.user(text)),
                maxOutputTokens = (text.length * 2).coerceIn(256, 2048),
                temperature = 0.0,
                reasoning = reasoning,
            )
            val out = StringBuilder()
            routed.engine.stream(request).collect { if (it is LlmEvent.TextDelta) out.append(it.text) }
            return out.toString().trim().ifEmpty { null }
        } finally {
            graph.router.release(routed)
        }
    }

    private suspend fun currentChatModel(): EngineSelection? {
        val global = graph.settings.current().selection
        val runs = graph.tasks.liveRuns.value
        val focused = graph.focusedConversationId
        if (focused != null) {
            runs[focused]?.takeIf { it.active }?.let { run -> graph.tasks.selectionOf(run.runId)?.let { return it } }
            val conversation = runCatching { graph.conversations.load(focused) }.getOrNull()
            return conversation?.selection ?: global
        }
        val latest = runs.values.filter { it.active }.maxByOrNull { it.startedAt }
        latest?.let { run -> graph.tasks.selectionOf(run.runId) ?: run.conversation.selection }?.let { return it }
        return global
    }
}
