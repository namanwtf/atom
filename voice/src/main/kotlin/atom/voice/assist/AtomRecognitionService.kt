package atom.voice.assist

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import atom.voice.service.RecognizerPicker

/**
 * Exists only because Android requires a `recognitionService` in the voice-interaction metadata for the
 * assistant role to be grantable (VoiceInteractionServiceInfo: "No recognitionService specified"). On Android 11
 * the role holder's recognizer can become the system default, so instead of failing it passes requests through
 * to the device's real recognizer. Idle unless a client binds it.
 */
class AtomRecognitionService : RecognitionService() {
    private var delegate: SpeechRecognizer? = null

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        release()
        val target = RecognizerPicker.pick(this)
        if (target == null) {
            runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
            return
        }
        val sr = SpeechRecognizer.createSpeechRecognizer(this, target)
        delegate = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { runCatching { listener.readyForSpeech(params ?: Bundle()) } }
            override fun onBeginningOfSpeech() { runCatching { listener.beginningOfSpeech() } }
            override fun onRmsChanged(rmsdB: Float) { runCatching { listener.rmsChanged(rmsdB) } }
            override fun onBufferReceived(buffer: ByteArray?) { if (buffer != null) runCatching { listener.bufferReceived(buffer) } }
            override fun onEndOfSpeech() { runCatching { listener.endOfSpeech() } }
            override fun onError(error: Int) {
                runCatching { listener.error(error) }
                release()
            }
            override fun onResults(results: Bundle?) {
                runCatching { listener.results(results ?: Bundle()) }
                release()
            }
            override fun onPartialResults(partialResults: Bundle?) { runCatching { listener.partialResults(partialResults ?: Bundle()) } }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        sr.startListening(recognizerIntent)
    }

    override fun onStopListening(listener: Callback) {
        runCatching { delegate?.stopListening() }
    }

    override fun onCancel(listener: Callback) {
        release()
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        delegate?.let { runCatching { it.destroy() } }
        delegate = null
    }
}
