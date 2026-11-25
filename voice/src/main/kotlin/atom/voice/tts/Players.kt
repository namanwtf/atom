package atom.voice.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import atom.voice.audio.MicCapture
import atom.voice.speech.SpeechError
import atom.voice.speech.SpeechException
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal val SpeechAttributes: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

/**
 * Streams 16-bit PCM to one AudioTrack. Created per spoken answer and released right after; writes block on an
 * IO thread, and the end of playback is detected with a position marker (no polling).
 */
internal class PcmPlayer(private val onLevel: (Float) -> Unit) {
    private var track: AudioTrack? = null
    private var rate = 0
    private var channels = 1
    private var framesWritten = 0L
    private var carry: Byte? = null
    @Volatile private var volume = 1f

    suspend fun play(sampleRate: Int, channelCount: Int, pcm: ByteArray) {
        if (track == null || sampleRate != rate || channelCount != channels) {
            drain()
            open(sampleRate, channelCount)
        }
        var data = carry?.let { byteArrayOf(it) + pcm } ?: pcm
        carry = null
        if (data.size % 2 == 1) {
            carry = data.last()
            data = data.copyOf(data.size - 1)
        }
        if (data.isEmpty()) return
        val t = track ?: return
        onLevel(levelOf(data))
        withContext(Dispatchers.IO) {
            var off = 0
            while (off < data.size) {
                val n = t.write(data, off, data.size - off, AudioTrack.WRITE_BLOCKING)
                if (n <= 0) break
                off += n
            }
        }
        framesWritten += data.size / (2 * channels)
    }

    fun duck(ducked: Boolean) {
        volume = if (ducked) 0.25f else 1f
        runCatching { track?.setVolume(volume) }
    }

    /** Waits until everything written so far has been heard. */
    suspend fun drain() {
        val t = track ?: return
        val target = framesWritten
        if (target > 0) {
            val done = CompletableDeferred<Unit>()
            t.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(track: AudioTrack?) {
                    done.complete(Unit)
                }

                override fun onPeriodicNotification(track: AudioTrack?) {}
            }, android.os.Handler(android.os.Looper.getMainLooper()))
            t.notificationMarkerPosition = target.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            // Short tails can finish before the marker is armed; the head position check covers that.
            if (t.playbackHeadPosition.toLong() < target) {
                withTimeoutOrNull(((target - t.playbackHeadPosition) * 1000 / rate.coerceAtLeast(1)) + 1_500) { done.await() }
            }
        }
        release()
    }

    fun release() {
        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            runCatching { it.release() }
        }
        track = null
        framesWritten = 0
        carry = null
        onLevel(0f)
    }

    private fun open(sampleRate: Int, channelCount: Int) {
        val mask = if (channelCount == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val min = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) throw SpeechException(SpeechError.Unsupported, "audio output")
        val t = AudioTrack.Builder()
            .setAudioAttributes(SpeechAttributes)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(min * 4)
            .build()
        t.setVolume(volume)
        t.play()
        track = t
        rate = sampleRate
        channels = channelCount
        framesWritten = 0
    }

    private fun levelOf(bytes: ByteArray): Float {
        val n = (bytes.size / 2).coerceAtMost(2_048)
        if (n == 0) return 0f
        val shorts = ShortArray(n) { i -> ((bytes[2 * i + 1].toInt() shl 8) or (bytes[2 * i].toInt() and 0xFF)).toShort() }
        return MicCapture.level(MicCapture.rms(shorts, n))
    }
}

/** The Android system voice: always available, used directly or as the silent fallback for cloud failures. */
internal class SystemVoice(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null

    private suspend fun engine(): TextToSpeech? {
        tts?.let { if (ready?.await() == true) return it }
        val deferred = CompletableDeferred<Boolean>()
        ready = deferred
        val created = withContext(Dispatchers.Main) {
            TextToSpeech(context.applicationContext) { status -> deferred.complete(status == TextToSpeech.SUCCESS) }
        }
        tts = created
        val ok = withTimeoutOrNull(5_000) { deferred.await() } ?: false
        if (!ok) {
            shutdown()
            return null
        }
        created.setAudioAttributes(SpeechAttributes)
        return created
    }

    /** Speaks [text] and returns when done (false on failure or interruption). */
    suspend fun speak(text: String): Boolean {
        val engine = engine() ?: return false
        pickLanguage(engine, text)
        val id = UUID.randomUUID().toString()
        return suspendCancellableCoroutine { cont ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(true)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(false)
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId == id && cont.isActive) cont.resume(false)
                }
                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    if (utteranceId == id && cont.isActive) cont.resume(false)
                }
            })
            cont.invokeOnCancellation { runCatching { engine.stop() } }
            val result = engine.speak(text.take(TextToSpeech.getMaxSpeechInputLength() - 1), TextToSpeech.QUEUE_FLUSH, Bundle(), id)
            if (result != TextToSpeech.SUCCESS && cont.isActive) cont.resume(false)
        }
    }

    fun stop() {
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ready = null
    }

    private fun pickLanguage(engine: TextToSpeech, text: String) {
        val cjk = text.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        val target = if (cjk > text.length / 4) Locale.CHINESE else Locale.getDefault()
        if (engine.voice?.locale?.language != target.language) {
            val ok = runCatching { engine.isLanguageAvailable(target) >= TextToSpeech.LANG_AVAILABLE }.getOrDefault(false)
            if (ok) runCatching { engine.language = target }
        }
    }
}
