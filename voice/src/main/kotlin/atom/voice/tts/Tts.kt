package atom.voice.tts

import atom.voice.speech.SecretResolver
import atom.voice.speech.SpeechConfig
import atom.voice.speech.TtsServiceId

/** Receives synthesized 16-bit PCM as it arrives. */
interface PcmSink {
    /** Called before the first [write] and whenever the format changes. */
    suspend fun format(sampleRate: Int, channels: Int = 1)
    suspend fun write(pcm: ByteArray)
}

/** One cloud spoken-answer service. Throws [atom.voice.speech.SpeechException] on failure (the caller falls back). */
interface TtsEngine {
    val id: TtsServiceId

    /** Longest text accepted per request; longer text is split at sentence boundaries. */
    val maxChars: Int get() = 400

    suspend fun synthesize(text: String, config: SpeechConfig, secrets: SecretResolver, sink: PcmSink)
}
