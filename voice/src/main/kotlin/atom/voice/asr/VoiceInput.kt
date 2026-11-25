package atom.voice.asr

import android.content.Context
import android.os.SystemClock
import atom.voice.audio.MicCapture
import atom.voice.cloud.DeepSeekAsr
import atom.voice.cloud.DoubaoStreamingAsr
import atom.voice.cloud.MimoAsr
import atom.voice.cloud.OssCleanup
import atom.voice.cloud.QwenFileAsr
import atom.voice.cloud.QwenRealtimeAsr
import atom.voice.cloud.QwenShortAsr
import atom.voice.speech.AsrServiceId
import atom.voice.speech.EndpointDetector
import atom.voice.speech.SecretResolver
import atom.voice.speech.Speech
import atom.voice.speech.SpeechConfig
import atom.voice.speech.SpeechError
import atom.voice.speech.SpeechException
import atom.voice.tts.ReadAloud
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What a voice input is doing right now. */
sealed interface VoiceInputState {
    data object Idle : VoiceInputState

    data class Active(
        val phase: AsrPhase,
        /** Live text (streaming services only). */
        val partial: String = "",
        /** 0..1 input level; 0 when not listening. */
        val level: Float = 0f,
        val streaming: Boolean,
        val service: AsrServiceId,
    ) : VoiceInputState

    data class Failed(val error: SpeechError, val detail: String? = null) : VoiceInputState
}

/** Why a recording ended. */
enum class VoiceEnd { User, Pause, NoSpeech, MaxDuration }

data class VoiceResult(val text: String, val listenedMs: Long, val end: VoiceEnd)

/**
 * Options for one recognition.
 * @param pauseMs end by itself after this pause in speech (auto-send); null = only when [VoiceInput.finish] is called.
 * @param noSpeechTimeoutMs give up when nothing is said for this long; null = wait for the user.
 * @param echoCancel record through the echo canceller (for talking over Atom's own voice).
 * @param config / [secrets] override the saved settings (Test recognition uses the unsaved form).
 */
data class VoiceInputOptions(
    val pauseMs: Long? = null,
    val noSpeechTimeoutMs: Long? = 10_000,
    val echoCancel: Boolean = false,
    val config: SpeechConfig? = null,
    val secrets: SecretResolver? = null,
    val proofread: Boolean = true,
    /** Starting a recording stops any read-aloud playback (interruption rule); set false only for barge-in monitors. */
    val stopsPlayback: Boolean = true,
) {
    companion object {
        /**
         * Assistant overlay: streaming services auto-send after the configured pause (1 / 1.5 / 2 / 3 s or never);
         * upload services wait for the user (they show only progress states).
         */
        fun assistant(context: Context, config: SpeechConfig = Speech.config.value): VoiceInputOptions =
            VoiceInputOptions(
                pauseMs = if (Speech.effectiveAsr(context, config).streaming) EndpointDetector.pauseFor(config.autoSendPause, handsFree = false) else null,
                noSpeechTimeoutMs = 8_000,
            )

        /** Hold-to-talk: no silence timeout, the release ends it. */
        val holdToTalk = VoiceInputOptions(noSpeechTimeoutMs = null)
    }
}

/**
 * One reusable voice input for a screen (chat composer, overlay, audio chat, settings test). The microphone is
 * open only between [start] and the result; [cancel] or [release] frees it immediately. Main-thread API.
 */
class VoiceInput(private val context: Context, private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) {
    private val mutableState = MutableStateFlow<VoiceInputState>(VoiceInputState.Idle)
    val state: StateFlow<VoiceInputState> = mutableState.asStateFlow()

    private var job: Job? = null
    @Volatile private var stopRequested = false
    private var stopHandlers = mutableListOf<() -> Unit>()
    @Volatile private var endReason = VoiceEnd.User

    val isActive: Boolean get() = job?.isActive == true

    /**
     * Starts listening; [onResult] gets the final text ("" when nothing was said) unless cancelled or failed.
     * Returns false (with [state] = Failed) when recognition is disabled or not permitted.
     */
    fun start(options: VoiceInputOptions = VoiceInputOptions(), onResult: (VoiceResult) -> Unit): Boolean {
        cancel()
        val config = options.config ?: Speech.config.value
        if (!config.recognitionEnabled && options.config == null) {
            mutableState.value = VoiceInputState.Failed(SpeechError.Disabled)
            return false
        }
        if (!MicCapture.hasPermission(context)) {
            mutableState.value = VoiceInputState.Failed(SpeechError.NoPermission)
            return false
        }
        if (options.stopsPlayback) ReadAloud.onRecordingStarted()
        val service = Speech.effectiveAsr(context, config)
        val engine = AsrEngines.create(context, service)
        val secrets = options.secrets ?: SecretResolver(Speech.credentials)
        stopRequested = false
        stopHandlers = mutableListOf()
        endReason = VoiceEnd.User
        val startedAt = SystemClock.elapsedRealtime()
        mutableState.value = VoiceInputState.Active(AsrPhase.Connecting, streaming = service.streaming, service = service)
        job = scope.launch {
            try {
                val text = recognize(engine, service, config, secrets, options)
                val listened = SystemClock.elapsedRealtime() - startedAt
                val end = if (text.isBlank() && endReason == VoiceEnd.User && !stopRequested) VoiceEnd.NoSpeech else endReason
                val final = if (text.isNotBlank() && options.proofread && config.smartCorrection) proofread(text) else text
                mutableState.value = VoiceInputState.Idle
                onResult(VoiceResult(final.trim(), listened, end))
            } catch (e: CancellationException) {
                throw e
            } catch (e: SpeechException) {
                mutableState.value = VoiceInputState.Failed(e.kind, e.detail)
            } catch (e: SecurityException) {
                mutableState.value = VoiceInputState.Failed(SpeechError.NoPermission)
            } catch (e: Exception) {
                mutableState.value = VoiceInputState.Failed(SpeechError.Server, e.javaClass.simpleName)
            }
        }
        return true
    }

    /** Stop recording and deliver what was said (tap again / "Done" / release). */
    fun finish() = finish(VoiceEnd.User)

    private fun finish(reason: VoiceEnd) {
        if (stopRequested) return
        stopRequested = true
        endReason = reason
        stopHandlers.toList().forEach { runCatching { it() } }
    }

    /** Discard the recording; the mic is released at once. */
    fun cancel() {
        job?.cancel()
        job = null
        if (mutableState.value is VoiceInputState.Active) mutableState.value = VoiceInputState.Idle
    }

    fun clearError() {
        if (mutableState.value is VoiceInputState.Failed) mutableState.value = VoiceInputState.Idle
    }

    fun release() = cancel()

    private fun setPhase(phase: AsrPhase) = mutableState.update { s ->
        if (s is VoiceInputState.Active) s.copy(phase = phase, level = if (phase == AsrPhase.Listening) s.level else 0f) else s
    }

    private suspend fun recognize(
        engine: AsrEngine,
        service: AsrServiceId,
        config: SpeechConfig,
        secrets: SecretResolver,
        options: VoiceInputOptions,
    ): String = kotlinx.coroutines.coroutineScope {
        val detector = EndpointDetector(options.pauseMs, options.noSpeechTimeoutMs, service.maxRecordingMs)
        val lock = Any()
        val onPartial: (String) -> Unit = { text ->
            synchronized(lock) { detector.onText(SystemClock.elapsedRealtime()) }
            mutableState.update { s -> if (s is VoiceInputState.Active) s.copy(partial = text) else s }
        }
        val onPhase: (AsrPhase) -> Unit = { phase -> scope.launch { setPhase(phase) } }
        val onLevel: (Float) -> Unit = { level ->
            mutableState.update { s -> if (s is VoiceInputState.Active && s.phase == AsrPhase.Listening) s.copy(level = level) else s }
        }
        if (engine.ownsMicrophone) {
            // Android's recognizer endpoints itself; we only enforce the maximum length.
            val maxTimer = launch {
                kotlinx.coroutines.delay(service.maxRecordingMs)
                finish(VoiceEnd.MaxDuration)
            }
            try {
                engine.recognize(
                    AsrRequest(
                        config, secrets, audio = null, onPhase = onPhase, onPartial = onPartial, onLevel = onLevel,
                        stopRequested = { stopRequested }, onStopRequested = { stopHandlers += it },
                        pauseMs = options.pauseMs, noSpeechTimeoutMs = options.noSpeechTimeoutMs,
                    ),
                )
            } finally {
                maxTimer.cancel()
            }
        } else {
            val audio = Channel<ShortArray>(capacity = 256)
            stopHandlers += { audio.close() }
            var silentGiveUp = false
            val result = async {
                engine.recognize(
                    AsrRequest(
                        config, secrets, audio = audio, onPhase = onPhase, onPartial = onPartial, onLevel = {},
                        stopRequested = { stopRequested }, onStopRequested = { stopHandlers += it },
                        pauseMs = options.pauseMs, noSpeechTimeoutMs = options.noSpeechTimeoutMs,
                    ),
                )
            }
            val recorder = launch {
                try {
                    MicCapture.capture(context, options.echoCancel, onStarted = { onPhase(AsrPhase.Listening) }) { frame, voiced, level ->
                        if (stopRequested) return@capture false
                        audio.trySend(frame)
                        onLevel(level)
                        val decision = synchronized(lock) {
                            detector.onFrame(voiced, SystemClock.elapsedRealtime(), MicCapture.FRAME_MS)
                        }
                        when (decision) {
                            EndpointDetector.Decision.Continue -> true
                            EndpointDetector.Decision.EndOfSpeech -> { endReason = VoiceEnd.Pause; false }
                            EndpointDetector.Decision.NoSpeech -> {
                                endReason = VoiceEnd.NoSpeech
                                // Nothing was said: do not send silence to a (possibly paid) service.
                                if (!detector.heardSpeech) {
                                    silentGiveUp = true
                                    result.cancel()
                                }
                                false
                            }
                            EndpointDetector.Decision.MaxDuration -> { endReason = VoiceEnd.MaxDuration; false }
                        }
                    }
                } finally {
                    audio.close()
                    onLevel(0f)
                }
            }
            try {
                try {
                    result.await()
                } catch (e: CancellationException) {
                    if (silentGiveUp && isActive) "" else throw e
                }
            } finally {
                recorder.cancel()
            }
        }
    }

    private suspend fun proofread(text: String): String {
        val reader = Speech.proofreader ?: return text
        setPhase(AsrPhase.Proofreading)
        val fixed = withTimeoutOrNull(PROOFREAD_TIMEOUT_MS) {
            runCatching { withContext(Dispatchers.Default) { reader.proofread(Proofreading.INSTRUCTIONS, text) } }.getOrNull()
        }
        return Proofreading.accept(text, fixed)
    }

    companion object {
        const val PROOFREAD_TIMEOUT_MS = 8_000L
    }
}

/** Smart correction: the prompt and the safety net that keeps the raw text when the model wanders off. */
object Proofreading {
    const val INSTRUCTIONS =
        "You fix speech-recognition errors. Correct only obvious mis-hearings: homophones, split or merged words, " +
            "and mixed-language technical terms or names. Do not rephrase, translate, summarise, answer, or add " +
            "punctuation beyond what is needed. If nothing is clearly wrong, return the text unchanged. " +
            "Reply with the corrected text only."

    /** Keeps [raw] unless [fixed] is a plausible light edit of it. */
    fun accept(raw: String, fixed: String?): String {
        val candidate = fixed?.trim()?.removeSurrounding("\"")?.removeSurrounding("“", "”")?.trim()
        if (candidate.isNullOrEmpty()) return raw
        val ratio = candidate.length.toDouble() / raw.length.coerceAtLeast(1)
        if (ratio < 0.6 || ratio > 1.5) return raw
        if (candidate.contains('\n') && !raw.contains('\n')) return raw
        return candidate
    }
}

internal object AsrEngines {
    fun create(context: Context, id: AsrServiceId): AsrEngine {
        val http = Speech.http
        return when (id) {
            AsrServiceId.AndroidOnDevice -> AndroidAsr(context, onDevice = true)
            AsrServiceId.AndroidDefault -> AndroidAsr(context, onDevice = false)
            AsrServiceId.QwenRealtime -> QwenRealtimeAsr(http)
            AsrServiceId.QwenShort -> QwenShortAsr(http)
            AsrServiceId.QwenFile -> QwenFileAsr(http, OssCleanup { Speech.ossCleanupPrefs() })
            AsrServiceId.DoubaoStreaming -> DoubaoStreamingAsr(http, installId())
            AsrServiceId.Mimo -> MimoAsr(http)
            AsrServiceId.DeepSeek -> DeepSeekAsr(http)
        }
    }

    /** A random per-install id some services require as "user"; never tied to the person. */
    fun installId(): String {
        val prefs = Speech.ossCleanupPrefs() ?: return "atom"
        return prefs.getString("install.id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("install.id", it).apply() }
    }
}
