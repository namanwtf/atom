package atom.voice.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import atom.voice.speech.SpeechError
import atom.voice.speech.SpeechException
import kotlin.math.log10
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One microphone capture: 16 kHz mono PCM16 in 80 ms frames, read on an IO thread and released the moment the
 * capture ends (normally, by [onFrame] returning false, or by coroutine cancellation, which stops the recorder
 * to unblock the read). With [echoCancel] it records from the voice-communication path and enables the
 * platform echo canceller / noise suppressor when the device has them (needed to hear the user over Atom's own
 * voice).
 */
internal object MicCapture {
    const val SAMPLE_RATE = 16_000
    const val FRAME_SAMPLES = 1_280
    const val FRAME_MS = 80L

    fun hasPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** True when the device offers a platform echo canceller (voice barge-in is only offered then). */
    fun echoCancellerAvailable(): Boolean = runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false)

    /** 0..1 loudness for UI from a frame RMS (−60 dBFS → 0, 0 dBFS → 1). */
    fun level(rms: Float): Float {
        if (rms <= 1f) return 0f
        val db = 20 * log10(rms / 32_768f)
        return ((db + 60f) / 60f).coerceIn(0f, 1f)
    }

    fun rms(frame: ShortArray, count: Int): Float {
        var sum = 0.0
        for (i in 0 until count) {
            val s = frame[i].toDouble()
            sum += s * s
        }
        return if (count == 0) 0f else sqrt(sum / count).toFloat()
    }

    /**
     * Captures until [onFrame] returns false. [onFrame] gets a fresh frame (the caller may keep it), whether the
     * speech gate considers it voiced, and its UI level.
     */
    suspend fun capture(
        context: Context,
        echoCancel: Boolean,
        onStarted: () -> Unit = {},
        onFrame: (frame: ShortArray, voiced: Boolean, level: Float) -> Boolean,
    ) {
        if (!hasPermission(context)) throw SpeechException(SpeechError.NoPermission)
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) throw SpeechException(SpeechError.Unsupported, "audio format")
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(if (echoCancel) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
                )
                .setBufferSizeInBytes(maxOf(minBuf, FRAME_SAMPLES * 2 * 4))
                .build()
        } catch (e: SecurityException) {
            throw SpeechException(SpeechError.NoPermission, cause = e)
        } catch (e: Exception) {
            throw SpeechException(SpeechError.Busy, "microphone", e)
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw SpeechException(SpeechError.Busy, "microphone")
        }
        val effects = if (echoCancel) attachEffects(record.audioSessionId) else emptyList()
        try {
            coroutineScope {
                val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        runCatching { record.stop() } // unblocks a pending read
                    }
                }
                try {
                    withContext(Dispatchers.IO) {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                        try {
                            record.startRecording()
                            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                                throw SpeechException(SpeechError.Busy, "microphone")
                            }
                            onStarted()
                            val gate = SpeechGate()
                            while (true) {
                                val frame = ShortArray(FRAME_SAMPLES)
                                var filled = 0
                                while (filled < FRAME_SAMPLES) {
                                    val n = record.read(frame, filled, FRAME_SAMPLES - filled, AudioRecord.READ_BLOCKING)
                                    if (n < 0) throw SpeechException(SpeechError.Busy, "microphone lost")
                                    if (n == 0 && record.recordingState != AudioRecord.RECORDSTATE_RECORDING) break
                                    filled += n
                                }
                                if (filled < FRAME_SAMPLES) break
                                val voiced = gate.process(frame)
                                if (!onFrame(frame, voiced, level(gate.lastRms))) break
                            }
                        } finally {
                            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
                        }
                    }
                } finally {
                    watcher.cancel()
                }
            }
        } finally {
            runCatching { record.stop() }
            effects.forEach { runCatching { it.release() } }
            record.release()
        }
    }

    private fun attachEffects(session: Int): List<android.media.audiofx.AudioEffect> = buildList {
        if (runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false)) {
            AcousticEchoCanceler.create(session)?.let { runCatching { it.enabled = true }; add(it) }
        }
        if (runCatching { NoiseSuppressor.isAvailable() }.getOrDefault(false)) {
            NoiseSuppressor.create(session)?.let { runCatching { it.enabled = true }; add(it) }
        }
        if (runCatching { AutomaticGainControl.isAvailable() }.getOrDefault(false)) {
            AutomaticGainControl.create(session)?.let { runCatching { it.enabled = true }; add(it) }
        }
    }
}
