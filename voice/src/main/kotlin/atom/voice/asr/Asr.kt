package atom.voice.asr

import atom.voice.speech.AsrServiceId
import atom.voice.speech.SecretResolver
import atom.voice.speech.SpeechConfig
import kotlinx.coroutines.channels.ReceiveChannel

/** Progress states shown while recognising (upload services show only these, streaming ones also live text). */
enum class AsrPhase { Connecting, Listening, Recognizing, Uploading, Proofreading }

/** Everything an engine needs for one recognition. */
class AsrRequest(
    val config: SpeechConfig,
    val secrets: SecretResolver,
    /**
     * 16 kHz mono PCM16 frames from the microphone, closed when the recording ends (user finished, pause, max).
     * Null for engines that own the microphone themselves ([AsrEngine.ownsMicrophone]).
     */
    val audio: ReceiveChannel<ShortArray>?,
    val onPhase: (AsrPhase) -> Unit,
    /** Live text for streaming services (the whole current hypothesis, not a delta). */
    val onPartial: (String) -> Unit,
    /** 0..1 input level (only engines that own the mic report it; otherwise the recorder does). */
    val onLevel: (Float) -> Unit,
    /** Engines that own the mic poll this when they can stop early (user tapped Done / released). */
    val stopRequested: () -> Boolean,
    /** Registers a callback fired when the user asks to finish (engines owning the mic use it to stop). */
    val onStopRequested: (() -> Unit) -> Unit,
    /** Pause after which a self-endpointing engine may finish by itself (null = only when told). */
    val pauseMs: Long?,
    val noSpeechTimeoutMs: Long?,
)

/** One speech-recognition service. [recognize] is cancellable and returns the final text ("" when nothing was said). */
interface AsrEngine {
    val id: AsrServiceId

    /** True for Android's SpeechRecognizer, which opens the microphone itself. */
    val ownsMicrophone: Boolean get() = false

    suspend fun recognize(request: AsrRequest): String
}

/** Drains the audio channel into one PCM16 little-endian byte array (for upload-then-transcribe services). */
internal suspend fun ReceiveChannel<ShortArray>.collectPcm(maxBytes: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    for (frame in this) {
        if (out.size() + frame.size * 2 > maxBytes) continue
        out.write(atom.voice.audio.Wav.shortsToBytes(frame))
    }
    return out.toByteArray()
}
