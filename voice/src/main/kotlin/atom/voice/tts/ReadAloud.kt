package atom.voice.tts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.PowerManager
import android.os.SystemClock
import atom.voice.cloud.DeepSeekTts
import atom.voice.cloud.DoubaoTts
import atom.voice.cloud.GeminiTts
import atom.voice.cloud.MiniMaxTts
import atom.voice.cloud.MimoTts
import atom.voice.cloud.QwenTts
import atom.voice.speech.MarkdownSpeech
import atom.voice.speech.SecretResolver
import atom.voice.speech.Speech
import atom.voice.speech.SpeechChunker
import atom.voice.speech.SpeechConfig
import atom.voice.speech.SpeechException
import atom.voice.speech.TtsServiceId
import atom.voice.asr.AsrEngines
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ReadAloudState {
    data object Idle : ReadAloudState

    /** Waiting for the first audio. [id] is the caller's tag (e.g. a message id) to show a stop button on it. */
    data class Preparing(val source: ReadAloudSource, val id: String?) : ReadAloudState

    data class Speaking(val source: ReadAloudSource, val id: String?, val systemVoice: Boolean) : ReadAloudState
}

/**
 * Read-aloud for any text (chat answers, overlay answers, audio chat, previews). One answer plays at a time;
 * a new [speak] replaces the old one. Holds audio focus, a noisy-audio receiver and (for chat answers in the
 * background) a media-playback foreground service only while speaking; everything is released the moment it ends.
 *
 * If the chosen cloud voice fails (bad key, timeout, refusal), the rest is spoken with the system voice instead
 * of showing an error.
 */
object ReadAloud {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow<ReadAloudState>(ReadAloudState.Idle)
    val state: StateFlow<ReadAloudState> = mutableState.asStateFlow()

    private val mutableLevel = MutableStateFlow(0f)
    /** 0..1 output loudness while speaking (for a level-reactive visual); 0 otherwise. */
    val level: StateFlow<Float> = mutableLevel.asStateFlow()

    private var job: Job? = null
    private var generation = 0L
    private var player: PcmPlayer? = null
    private var playingSource: ReadAloudSource? = null

    val isSpeaking: Boolean get() = mutableState.value != ReadAloudState.Idle

    /**
     * Speaks [text] (Markdown filtered per settings). With TTS "Off" the system voice is used, so "Read aloud"
     * always works. [id] tags the request so a UI can show which item is playing.
     */
    fun speak(
        text: String,
        source: ReadAloudSource,
        id: String? = null,
        config: SpeechConfig? = null,
        secrets: SecretResolver? = null,
    ) {
        launchSpeak(text, source, id, config, secrets, null)
    }

    /** Like [speak] but suspends until finished; returns true when it played to the end. */
    suspend fun speakAndWait(text: String, source: ReadAloudSource, id: String? = null): Boolean {
        val done = CompletableDeferred<Boolean>()
        launchSpeak(text, source, id, null, null, done)
        return try {
            done.await()
        } catch (e: CancellationException) {
            stop()
            throw e
        }
    }

    fun stop() {
        generation++
        job?.cancel()
        job = null
        if (mutableState.value != ReadAloudState.Idle) mutableState.value = ReadAloudState.Idle
        mutableLevel.value = 0f
    }

    /** Interruption rule: a recording starting anywhere stops playback. */
    fun onRecordingStarted() = onEvent(PlaybackEvent.RecordingStarted)

    /** The page that owns [source] audio was closed or hidden (chat answers keep playing in the background). */
    fun onOwnerHidden(source: ReadAloudSource) = onEvent(PlaybackEvent.OwnerHidden(source))

    /** Notification Stop button. */
    internal fun onStopPressed() = onEvent(PlaybackEvent.StopPressed)

    private fun onEvent(event: PlaybackEvent) {
        val source = playingSource ?: return
        when (PlaybackRules.decide(source, event)) {
            PlaybackAction.Stop -> stop()
            PlaybackAction.Duck -> player?.duck(true)
            PlaybackAction.Unduck -> player?.duck(false)
            PlaybackAction.Continue -> Unit
        }
    }

    private fun launchSpeak(
        text: String,
        source: ReadAloudSource,
        id: String?,
        configOverride: SpeechConfig?,
        secretsOverride: SecretResolver?,
        done: CompletableDeferred<Boolean>?,
    ) {
        stop()
        if (!Speech.isInitialized) {
            done?.complete(false)
            return
        }
        val myGeneration = ++generation
        val requestedAt = SystemClock.elapsedRealtime()
        val config = configOverride ?: Speech.config.value
        val secrets = secretsOverride ?: SecretResolver(Speech.credentials)
        mutableState.value = ReadAloudState.Preparing(source, id)
        playingSource = source
        job = scope.launch {
            var completed = false
            try {
                completed = play(text, source, id, config, secrets, requestedAt, myGeneration)
            } finally {
                if (generation == myGeneration) {
                    mutableState.value = ReadAloudState.Idle
                    playingSource = null
                    mutableLevel.value = 0f
                }
                done?.complete(completed)
            }
        }
    }

    private sealed interface Piece {
        class Pcm(val rate: Int, val channels: Int, val bytes: ByteArray) : Piece
        class System(val text: String) : Piece
    }

    private suspend fun play(
        text: String,
        source: ReadAloudSource,
        id: String?,
        config: SpeechConfig,
        secrets: SecretResolver,
        requestedAt: Long,
        myGeneration: Long,
    ): Boolean {
        val context = Speech.context
        val speech = MarkdownSpeech.toSpeech(text, config.filterMarkdown)
        if (speech.isBlank()) return true
        val engine = TtsEngines.create(config.tts)
        val chunks = SpeechChunker.split(speech, engine?.maxChars ?: 1_000)
        val session = AudioSession(context, source)
        val system = SystemVoice(context)
        val pcm = PcmPlayer { mutableLevel.value = it }
        player = pcm
        if (!session.acquire()) return false
        try {
            return coroutineScope {
                val pieces = Channel<Piece>(capacity = 32)
                launch(Dispatchers.Default) {
                    var fallback = engine == null
                    try {
                        for (chunk in chunks) {
                            if (!fallback) {
                                var wrote = false
                                var format = 24_000 to 1
                                try {
                                    engine!!.synthesize(chunk, config, secrets, object : PcmSink {
                                        override suspend fun format(sampleRate: Int, channels: Int) {
                                            format = sampleRate to channels
                                        }

                                        override suspend fun write(pcm: ByteArray) {
                                            wrote = true
                                            pieces.send(Piece.Pcm(format.first, format.second, pcm))
                                        }
                                    })
                                    continue
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: SpeechException) {
                                    fallback = true
                                } catch (e: IOException) {
                                    fallback = true
                                } catch (e: RuntimeException) {
                                    fallback = true
                                }
                                if (wrote) continue // part of this chunk was heard; go on with the system voice
                            }
                            pieces.send(Piece.System(chunk))
                        }
                    } finally {
                        pieces.close()
                    }
                }
                var started = false
                var finished = true
                for (piece in pieces) {
                    if (!started) {
                        // Nothing is spoken late: a stale or superseded request never starts.
                        if (!PlaybackRules.mayStart(requestedAt, SystemClock.elapsedRealtime(), myGeneration, generation)) {
                            finished = false
                            break
                        }
                        started = true
                        mutableState.value = ReadAloudState.Speaking(source, id, systemVoice = piece is Piece.System)
                    }
                    when (piece) {
                        is Piece.Pcm -> pcm.play(piece.rate, piece.channels, piece.bytes)
                        is Piece.System -> {
                            pcm.drain()
                            mutableState.value = ReadAloudState.Speaking(source, id, systemVoice = true)
                            mutableLevel.value = 0.35f
                            if (!system.speak(piece.text)) {
                                finished = false
                                break
                            }
                            mutableLevel.value = 0f
                        }
                    }
                }
                if (finished) pcm.drain()
                coroutineContext[Job]?.children?.forEach { it.cancel() }
                finished
            }
        } finally {
            pcm.release()
            system.shutdown()
            if (player === pcm) player = null
            session.release()
        }
    }

    // ------------------------------------------------------------------ audio session

    /** Audio focus, headphone-unplug receiver, background service and wake lock for one spoken answer. */
    private class AudioSession(private val context: Context, private val source: ReadAloudSource) {
        private val audio = context.getSystemService(AudioManager::class.java)
        private var focus: AudioFocusRequest? = null
        private var wakeLock: PowerManager.WakeLock? = null
        private val noisy = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) onEvent(PlaybackEvent.BecomingNoisy)
            }
        }
        private var receiverRegistered = false

        fun acquire(): Boolean {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(SpeechAttributes)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener { change ->
                    scope.launch {
                        when (change) {
                            AudioManager.AUDIOFOCUS_LOSS -> onEvent(PlaybackEvent.FocusLost(transient = false, canDuck = false))
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onEvent(PlaybackEvent.FocusLost(transient = true, canDuck = false))
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onEvent(PlaybackEvent.FocusLost(transient = true, canDuck = true))
                            AudioManager.AUDIOFOCUS_GAIN -> onEvent(PlaybackEvent.FocusGained)
                        }
                    }
                }
                .build()
            if (audio?.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
            focus = request
            context.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
            if (PlaybackRules.needsForegroundService(source)) {
                ReadAloudService.start(context)
                // Keeps synthesis of the next sentence going with the screen off; bounded, released at the end.
                wakeLock = context.getSystemService(PowerManager::class.java)
                    ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "atom:read-aloud")
                    ?.apply {
                        setReferenceCounted(false)
                        acquire(10 * 60 * 1000L)
                    }
            }
            return true
        }

        fun release() {
            focus?.let { runCatching { audio?.abandonAudioFocusRequest(it) } }
            focus = null
            if (receiverRegistered) runCatching { context.unregisterReceiver(noisy) }
            receiverRegistered = false
            wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
            wakeLock = null
            if (PlaybackRules.needsForegroundService(source)) ReadAloudService.stop()
        }
    }
}

internal object TtsEngines {
    fun create(id: TtsServiceId): TtsEngine? {
        val http = Speech.http
        return when (id) {
            TtsServiceId.Off, TtsServiceId.System -> null
            TtsServiceId.Qwen -> QwenTts(http)
            TtsServiceId.Doubao -> DoubaoTts(http, AsrEngines.installId())
            TtsServiceId.Gemini -> GeminiTts(http)
            TtsServiceId.MiniMax -> MiniMaxTts(http)
            TtsServiceId.Mimo -> MimoTts(http)
            TtsServiceId.DeepSeek -> DeepSeekTts(http)
        }
    }
}
