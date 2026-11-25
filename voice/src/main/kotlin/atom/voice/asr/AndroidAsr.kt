package atom.voice.asr

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import atom.voice.service.RecognizerPicker
import atom.voice.speech.AsrServiceId
import atom.voice.speech.SpeechError
import atom.voice.speech.SpeechException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Android's SpeechRecognizer. [onDevice] uses `createOnDeviceSpeechRecognizer` (no network, no cost, lowest
 * power) and falls back once to the device's default recognizer if the on-device one fails before hearing speech
 * (e.g. no language pack). The recognizer owns the microphone and ends on its own after a pause.
 */
class AndroidAsr(private val context: Context, private val onDevice: Boolean) : AsrEngine {
    override val id = if (onDevice) AsrServiceId.AndroidOnDevice else AsrServiceId.AndroidDefault
    override val ownsMicrophone = true

    private class Fallback : Exception()

    override suspend fun recognize(request: AsrRequest): String = withContext(Dispatchers.Main) {
        val first = if (onDevice) createOnDevice() else createDefault()
        if (first == null) {
            if (onDevice) createDefault()?.let { return@withContext listen(it, request, canFallBack = false) }
            throw SpeechException(SpeechError.Unsupported, "No speech recognizer on this device")
        }
        try {
            listen(first, request, canFallBack = onDevice)
        } catch (e: Fallback) {
            val second = createDefault() ?: throw SpeechException(SpeechError.Unsupported, "Language not available on device")
            listen(second, request, canFallBack = false)
        }
    }

    private fun createOnDevice(): SpeechRecognizer? =
        if (Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)) {
            runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
        } else {
            null
        }

    /** A real recognizer, skipping Atom's own pass-through service. */
    private fun createDefault(): SpeechRecognizer? = runCatching {
        RecognizerPicker.pick(context)?.let { SpeechRecognizer.createSpeechRecognizer(context, it) }
            ?: if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null
    }.getOrNull()

    private suspend fun listen(sr: SpeechRecognizer, request: AsrRequest, canFallBack: Boolean): String =
        suspendCancellableCoroutine { cont ->
            var heardSpeech = false
            var lastPartial = ""
            fun done(block: () -> Unit) {
                if (!cont.isActive) return
                runCatching { sr.destroy() }
                block()
            }
            cont.invokeOnCancellation {
                // Called on an arbitrary thread; SpeechRecognizer wants the main thread.
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    runCatching { sr.cancel() }
                    runCatching { sr.destroy() }
                }
            }
            request.onStopRequested { runCatching { sr.stopListening() } }
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = request.onPhase(AsrPhase.Listening)
                override fun onBeginningOfSpeech() {
                    heardSpeech = true
                }
                override fun onRmsChanged(rmsdB: Float) = request.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    request.onLevel(0f)
                    request.onPhase(AsrPhase.Recognizing)
                }
                override fun onError(error: Int) {
                    val fallback = canFallBack && !heardSpeech && error in FALLBACK_ERRORS
                    done {
                        when {
                            fallback -> cont.resumeWithException(Fallback())
                            error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                cont.resume(lastPartial)
                            else -> cont.resumeWithException(SpeechException(kind(error), "recognizer error $error"))
                        }
                    }
                }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                    done { cont.resume(text?.takeIf { it.isNotEmpty() } ?: lastPartial) }
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                    if (!text.isNullOrEmpty()) {
                        heardSpeech = true
                        lastPartial = text
                        request.onPartial(text)
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (onDevice) intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            request.config.language.trim().takeIf { it.isNotEmpty() }?.let { intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
            // Honoured by some recognizers: how long a pause ends the utterance.
            val silence = request.pauseMs ?: 3_000L
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silence)
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silence)
            request.onPhase(AsrPhase.Connecting)
            try {
                sr.startListening(intent)
            } catch (e: Exception) {
                done { cont.resumeWithException(SpeechException(SpeechError.Busy, "recognizer", e)) }
            }
        }

    private companion object {
        /** Client/server/language errors that mean "this recognizer cannot do it", worth one fallback. */
        val FALLBACK_ERRORS = setOf(
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT,
        )

        fun kind(error: Int): SpeechError = when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechError.NoPermission
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> SpeechError.Network
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> SpeechError.Timeout
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_AUDIO -> SpeechError.Busy
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> SpeechError.Quota
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> SpeechError.Unsupported
            else -> SpeechError.Server
        }
    }
}
