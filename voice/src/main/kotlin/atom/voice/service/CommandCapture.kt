package atom.voice.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Captures one spoken command after the wake word. Prefers the on-device recognizer (no network, less power);
 * if it fails before any speech was heard (e.g. language pack missing) it falls back once to a system
 * recognizer. Stops right after end-of-speech and always finishes within [TIMEOUT_MS], so the microphone is
 * never held longer than needed. Main thread only.
 */
internal class CommandCapture(private val context: Context, private val handler: Handler) {
    private companion object {
        const val TIMEOUT_MS = 10_000L
    }

    private var recognizer: SpeechRecognizer? = null
    private var onFinished: ((String?) -> Unit)? = null
    private var heardSpeech = false
    private val timeout = Runnable { finish(null) }

    val isActive: Boolean get() = onFinished != null

    fun start(onFinished: (String?) -> Unit) {
        cancel()
        this.onFinished = onFinished
        heardSpeech = false
        handler.postDelayed(timeout, TIMEOUT_MS)
        val onDevice = Build.VERSION.SDK_INT >= 31 && runCatching {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        }.getOrDefault(false)
        if (onDevice) listen(runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull(), canFallBack = true)
        else listen(createFallback(), canFallBack = false)
    }

    fun cancel() {
        val sr = recognizer
        onFinished = null
        if (sr != null) runCatching { sr.cancel() }
        release()
    }

    private fun listen(sr: SpeechRecognizer?, canFallBack: Boolean) {
        if (sr == null) {
            if (canFallBack) listen(createFallback(), canFallBack = false) else finish(null)
            return
        }
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {
                heardSpeech = true
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                if (recognizer === sr) runCatching { sr.stopListening() }
            }
            override fun onError(error: Int) {
                if (recognizer !== sr) return
                if (canFallBack && !heardSpeech && onFinished != null) {
                    destroyRecognizer()
                    listen(createFallback(), canFallBack = false)
                } else {
                    finish(null)
                }
            }
            override fun onResults(results: Bundle?) {
                if (recognizer !== sr) return
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                finish(text?.trim()?.takeIf { it.isNotEmpty() })
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        try {
            sr.startListening(intent)
        } catch (e: Exception) {
            finish(null)
        }
    }

    /** A real recognizer, skipping Atom's own pass-through (which would only add a hop). */
    private fun createFallback(): SpeechRecognizer? = try {
        RecognizerPicker.pick(context)?.let { SpeechRecognizer.createSpeechRecognizer(context, it) }
            ?: if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null
    } catch (e: Exception) {
        null
    }

    private fun finish(text: String?) {
        val callback = onFinished ?: return
        onFinished = null
        release()
        callback(text)
    }

    private fun release() {
        handler.removeCallbacks(timeout)
        destroyRecognizer()
    }

    private fun destroyRecognizer() {
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = null
    }
}
