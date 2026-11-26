package atom.voice.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Process
import android.os.SystemClock
import atom.core.ondevice.WakeModels
import atom.core.ondevice.WakeModelsLoad
import atom.voice.Wake
import atom.voice.audio.ShortRingBuffer
import atom.voice.audio.SpeechGate
import atom.voice.audio.TriggerDebouncer
import atom.voice.engine.WakePipeline.Companion.FRAME_SAMPLES

/**
 * One listening session: loads the models, opens the microphone, runs the gate + pipeline on one dedicated
 * background-priority thread (scheduled on the little cores), and releases everything when it ends.
 * A session ends on [stop], on a trigger, or on an error; callbacks are posted to [callbackHandler].
 *
 * Per 80 ms frame with the gate closed the cost is one blocking read, a 2.5 KB ring-buffer copy and the VAD pass;
 * the three models run only while the gate is open.
 */
internal class WakeListener(
    private val context: Context,
    /** Owned by the caller so the 2 s cooldown survives listener restarts. */
    private val debouncer: TriggerDebouncer,
    private val callbackHandler: Handler,
    private val listener: Callback,
) {
    interface Callback {
        fun onWakeWord()
        /** The microphone could not be opened or was lost (busy / dead); retried on the next device event. */
        fun onMicUnavailable(reason: String)
        /** Cannot run at all (bad model, no permission). */
        fun onFatal(reason: String)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        /** 19 x 80 ms = 1.52 s of pre-roll so the start of "hey" is not lost when the gate opens. */
        const val PREROLL_FRAMES = 19
    }

    private val lock = Any()
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    /** Session id of our AudioRecord (0 when not recording), used to tell our capture apart from other apps'. */
    @Volatile
    var audioSessionId: Int = 0
        private set

    fun start() {
        if (thread != null) return
        running = true
        thread = Thread(::loop, "atom-wakeword").apply { start() }
    }

    /** Stops and waits for the mic and interpreters to be released. Safe to call more than once. */
    fun stop() {
        running = false
        synchronized(lock) { runCatching { record?.stop() } } // unblocks a pending read
        thread?.let { if (it !== Thread.currentThread()) it.join(1_000) }
        thread = null
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        var outcome: () -> Unit = {}
        var models: WakeModels? = null
        try {
            when (val result = Wake.loadModels()) {
                is WakeModelsLoad.Failed -> {
                    outcome = { listener.onFatal(result.reason) }
                    return
                }
                is WakeModelsLoad.Ok -> models = result.models
            }
            val rec = openMic() ?: run {
                outcome = { listener.onMicUnavailable("Microphone unavailable") }
                return
            }
            outcome = runLoop(rec, WakePipeline(models))
        } catch (e: SecurityException) {
            outcome = { listener.onFatal("Microphone permission not granted") }
        } catch (e: Exception) {
            val reason = "Microphone error: ${e.message ?: e.javaClass.simpleName}"
            outcome = if (running) ({ listener.onMicUnavailable(reason) }) else ({})
        } finally {
            synchronized(lock) {
                record?.let { runCatching { it.stop() }; it.release() }
                record = null
            }
            audioSessionId = 0
            models?.close()
            val result = outcome
            callbackHandler.post(result)
        }
    }

    private fun openMic(): AudioRecord? {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("RECORD_AUDIO not granted")
        }
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null
        val rec = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuf, FRAME_SAMPLES * 2 * 4))
            .build()
        synchronized(lock) {
            if (!running || rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return null
            }
            record = rec
            audioSessionId = rec.audioSessionId // set before recording starts, so our own config is recognised
            rec.startRecording()
        }
        return if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec else null
    }

    private fun runLoop(rec: AudioRecord, pipeline: WakePipeline): () -> Unit {
        val frame = ShortArray(FRAME_SAMPLES)
        val ring = ShortRingBuffer(PREROLL_FRAMES * FRAME_SAMPLES)
        val replay = ShortArray(ring.capacity)
        val gate = SpeechGate()
        var wasOpen = false
        while (running) {
            var filled = 0
            while (filled < FRAME_SAMPLES && running) {
                val n = rec.read(frame, filled, FRAME_SAMPLES - filled, AudioRecord.READ_BLOCKING)
                if (n < 0) {
                    return if (running) ({ listener.onMicUnavailable("Microphone lost ($n)") }) else ({})
                }
                filled += n
            }
            if (!running) break
            ring.write(frame)
            val open = gate.process(frame)
            var triggered = false
            if (open && !wasOpen) {
                // Gate just opened: rebuild model context from the pre-roll (includes this frame).
                pipeline.reset()
                debouncer.reset()
                val count = ring.copyTo(replay)
                var off = 0
                while (off + FRAME_SAMPLES <= count && !triggered) {
                    triggered = debouncer.onScore(pipeline.process(replay, off), SystemClock.elapsedRealtime())
                    off += FRAME_SAMPLES
                }
            } else if (open) {
                triggered = debouncer.onScore(pipeline.process(frame), SystemClock.elapsedRealtime())
            } else if (wasOpen) {
                debouncer.reset()
            }
            wasOpen = open
            if (triggered) {
                running = false
                return { listener.onWakeWord() }
            }
        }
        return {}
    }
}
