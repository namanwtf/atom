package atom.overlay.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import atom.overlay.R
import atom.overlay.ReadAloudBridge
import atom.overlay.VoiceBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Dictation through the device's own speech recognizer. A working fallback until the voice feature's dictation is
 * wired into [atom.overlay.Overlay.voice]. Opens the microphone only while a session is live.
 */
class SystemVoiceBridge : VoiceBridge {
    override val available: Boolean get() = true

    override fun listen(context: Context, listener: VoiceBridge.Listener): VoiceBridge.Session {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listener.onError(context.getString(R.string.overlay_voice_no_permission))
            return VoiceBridge.NoSession
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            listener.onError(context.getString(R.string.overlay_voice_no_recognizer))
            return VoiceBridge.NoSession
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        var done = false
        fun finish() {
            done = true
            runCatching { recognizer.destroy() }
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                if (!done) listener.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (done) return
                finish()
                val message = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.overlay_voice_no_match
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.overlay_voice_no_permission
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> R.string.overlay_voice_network
                    else -> R.string.overlay_voice_failed
                }
                listener.onError(context.getString(message))
            }
            override fun onResults(results: Bundle?) {
                if (done) return
                finish()
                listener.onFinal(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
            }
            override fun onPartialResults(partialResults: Bundle?) {
                if (done) return
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(listener::onPartial)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true),
        )
        return object : VoiceBridge.Session {
            override fun stop() {
                if (!done) runCatching { recognizer.stopListening() }
            }

            override fun cancel() {
                if (!done) finish()
            }
        }
    }
}

/** Read-aloud through the system text-to-speech engine, bound on first use. */
class SystemReadAloud(context: Context) : ReadAloudBridge {
    private val app = context.applicationContext
    private val _speaking = MutableStateFlow(false)
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    override val available: Boolean get() = true
    override val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    override fun speak(text: String) {
        if (text.isBlank()) return
        val engine = tts
        if (engine != null && ready) {
            say(engine, text)
            return
        }
        pending = text
        if (engine == null) {
            tts = TextToSpeech(app) { status ->
                ready = status == TextToSpeech.SUCCESS
                val t = tts
                val p = pending
                pending = null
                if (ready && t != null && p != null) say(t, p) else if (!ready) release()
            }.apply {
                setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _speaking.value = true
                    }

                    override fun onDone(utteranceId: String?) {
                        _speaking.value = false
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        _speaking.value = false
                    }
                })
            }
        }
    }

    override fun stop() {
        pending = null
        tts?.stop()
        _speaking.value = false
    }

    /** Frees the engine binding; call when the app is trimmed. */
    fun release() {
        tts?.shutdown()
        tts = null
        ready = false
        _speaking.value = false
    }

    private fun say(engine: TextToSpeech, text: String) {
        _speaking.value = true
        engine.speak(text.take(TextToSpeech.getMaxSpeechInputLength() - 1), TextToSpeech.QUEUE_FLUSH, null, "atom-overlay")
    }
}
