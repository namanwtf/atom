package atom.voice.chat

import android.content.Context
import atom.ui.feature.Atom
import atom.ui.feature.HostRun
import atom.ui.feature.HostStep
import atom.ui.feature.RunPhase
import atom.ui.feature.RunSource
import atom.voice.asr.VoiceEnd
import atom.voice.asr.VoiceInput
import atom.voice.asr.VoiceInputOptions
import atom.voice.asr.VoiceInputState
import atom.voice.speech.EndpointDetector
import atom.voice.speech.Speech
import atom.voice.speech.SpeechError
import atom.voice.tts.ReadAloud
import atom.voice.tts.ReadAloudSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the audio-chat screen shows. */
data class AudioChatUi(
    val state: AudioChatMachine.State = AudioChatMachine.State.Ready,
    val handsFree: Boolean = true,
    /** Live text while the user talks. */
    val partial: String = "",
    /** Last thing the user said. */
    val heard: String = "",
    /** The answer streaming in (or the last answer). */
    val answer: String = "",
    val steps: List<HostStep> = emptyList(),
    val speechError: SpeechError? = null,
    val runError: String? = null,
    val conversationId: String? = null,
    val bargeInByVoice: Boolean = false,
)

/**
 * Runs the hands-free loop for one visible screen: [AudioChatMachine] decides, this performs its effects with
 * [VoiceInput], [Atom.host] and [ReadAloud]. Effects run strictly in order on one coroutine so the microphone
 * and the player are never fighting. Never outlives the screen: [leave] releases everything.
 */
class AudioChatController(
    private val context: Context,
    private val scope: CoroutineScope,
    conversationId: String?,
) {
    private val config get() = Speech.config.value
    private var machine = AudioChatMachine(handsFree = config.audioChatHandsFree, speakAnswers = config.audioChatSpeakAnswers)
    private val voice = VoiceInput(context, scope)
    private val effects = Channel<List<AudioChatMachine.Effect>>(Channel.UNLIMITED)

    private val mutableUi = MutableStateFlow(
        AudioChatUi(
            handsFree = machine.handsFree,
            conversationId = conversationId,
            bargeInByVoice = config.audioChatBargeIn && BargeInMonitor.supported(),
        ),
    )
    val ui: StateFlow<AudioChatUi> = mutableUi.asStateFlow()

    /** Live input level while listening, output level while speaking (drives the orb). */
    val voiceState: StateFlow<VoiceInputState> get() = voice.state

    private var runWatch: Job? = null
    private var speakJob: Job? = null
    private var monitorJob: Job? = null

    init {
        scope.launch { for (batch in effects) batch.forEach { perform(it) } }
        scope.launch {
            voice.state.collect { s ->
                when (s) {
                    is VoiceInputState.Active -> mutableUi.update { it.copy(partial = s.partial, speechError = null) }
                    is VoiceInputState.Failed -> if (machine.state == AudioChatMachine.State.Listening) {
                        mutableUi.update { it.copy(speechError = s.error) }
                        dispatch(AudioChatMachine.Event.RecognitionFailed(s.error.name))
                    }
                    VoiceInputState.Idle -> Unit
                }
            }
        }
    }

    fun start() = dispatch(AudioChatMachine.Event.Start)
    fun press() = dispatch(AudioChatMachine.Event.Press)
    fun release() = dispatch(AudioChatMachine.Event.Release)
    fun tap() = dispatch(AudioChatMachine.Event.Interrupt)
    fun end() = dispatch(AudioChatMachine.Event.EndByUser)
    fun onScreenOff() = dispatch(AudioChatMachine.Event.ScreenOff)
    fun leave() = dispatch(AudioChatMachine.Event.Leave)

    /** Starts a new session after it ended (silence, screen off). */
    fun restart() {
        if (machine.state is AudioChatMachine.State.Ended) {
            val fresh = AudioChatMachine(machine.handsFree, machine.speakAnswers)
            replaceMachine(fresh)
            dispatch(AudioChatMachine.Event.Start)
        }
    }

    fun setHandsFree(handsFree: Boolean) {
        Speech.update { it.copy(audioChatHandsFree = handsFree) }
        mutableUi.update { it.copy(handsFree = handsFree) }
        dispatch(AudioChatMachine.Event.ModeChanged(handsFree))
        if (handsFree && machine.state == AudioChatMachine.State.Ready) dispatch(AudioChatMachine.Event.Start)
    }

    fun setSpeakAnswers(speak: Boolean) {
        Speech.update { it.copy(audioChatSpeakAnswers = speak) }
        machine.speakAnswers = speak
    }

    private fun replaceMachine(next: AudioChatMachine) {
        machine = next
    }

    private fun dispatch(event: AudioChatMachine.Event) {
        val out = machine.handle(event)
        mutableUi.update { it.copy(state = machine.state) }
        if (out.isNotEmpty()) effects.trySend(out)
    }

    private suspend fun perform(effect: AudioChatMachine.Effect) {
        when (effect) {
            is AudioChatMachine.Effect.Listen -> listen(effect.noSpeechTimeoutMs)
            AudioChatMachine.Effect.FinishListening -> voice.finish()
            AudioChatMachine.Effect.CancelListening -> voice.cancel()
            is AudioChatMachine.Effect.Send -> send(effect.text)
            AudioChatMachine.Effect.StopRun -> {
                runWatch?.cancel()
                ui.value.conversationId?.let { id -> runCatching { Atom.host.stop(id) } }
            }
            is AudioChatMachine.Effect.Speak -> speak(effect.text)
            AudioChatMachine.Effect.StopSpeaking -> stopSpeaking()
            is AudioChatMachine.Effect.ShowError -> Unit // surfaced through ui.speechError / ui.runError
            AudioChatMachine.Effect.ReleaseAll -> {
                voice.release()
                stopSpeaking()
                runWatch?.cancel()
            }
        }
    }

    private fun listen(noSpeechTimeoutMs: Long?) {
        mutableUi.update { it.copy(partial = "", speechError = null, runError = null) }
        val handsFree = machine.handsFree
        val options = VoiceInputOptions(
            pauseMs = if (handsFree) EndpointDetector.pauseFor(config.autoSendPause, handsFree = true) else null,
            noSpeechTimeoutMs = noSpeechTimeoutMs,
        )
        voice.start(options) { result ->
            if (result.text.isBlank()) {
                dispatch(AudioChatMachine.Event.NoSpeech(if (result.end == VoiceEnd.NoSpeech) result.listenedMs else 0))
            } else {
                mutableUi.update { it.copy(heard = result.text, partial = "", answer = "", steps = emptyList()) }
                dispatch(AudioChatMachine.Event.Heard(result.text))
            }
        }
    }

    private fun send(text: String) {
        runWatch?.cancel()
        val host = runCatching { Atom.host }.getOrNull()
        if (host == null) {
            fail("Atom is not ready")
            return
        }
        val existing = ui.value.conversationId
        val before = existing?.let { id -> host.runs.value.firstOrNull { it.conversationId == id } }
        val id = runCatching { host.start(text, existing, RunSource.Voice) }.getOrNull()
            ?: existing?.also { runCatching { host.steer(it, text) } }
        if (id == null) {
            fail("Could not start")
            return
        }
        mutableUi.update { it.copy(conversationId = id) }
        runWatch = scope.launch {
            val runs = host.runs.map { list -> list.firstOrNull { it.conversationId == id } }
            launch {
                runs.collect { run -> if (run != null && run !== before) mutableUi.update { it.copy(answer = run.text, steps = run.steps.takeLast(3)) } }
            }
            val finished: HostRun = runs.first { run -> run != null && run !== before && !run.phase.active }!!
            when (finished.phase) {
                RunPhase.Done -> dispatch(AudioChatMachine.Event.AnswerReady(finished.text))
                else -> {
                    mutableUi.update { it.copy(runError = finished.error ?: finished.phase.name) }
                    dispatch(AudioChatMachine.Event.RunFailed(finished.error ?: finished.phase.name))
                }
            }
            coroutineContext[Job]?.children?.forEach { it.cancel() }
        }
    }

    private fun fail(message: String) {
        mutableUi.update { it.copy(runError = message) }
        dispatch(AudioChatMachine.Event.RunFailed(message))
    }

    private fun speak(text: String) {
        speakJob = scope.launch {
            ReadAloud.speakAndWait(text, ReadAloudSource.AudioChat)
            dispatch(AudioChatMachine.Event.SpeakingDone)
        }
        if (ui.value.bargeInByVoice) {
            monitorJob = scope.launch {
                runCatching { BargeInMonitor.listen(context) { scope.launch { dispatch(AudioChatMachine.Event.Interrupt) } } }
            }
        }
    }

    private suspend fun stopSpeaking() {
        monitorJob?.cancelAndJoin()
        monitorJob = null
        speakJob?.cancel()
        speakJob = null
        if ((ReadAloud.state.value as? atom.voice.tts.ReadAloudState.Speaking)?.source == ReadAloudSource.AudioChat ||
            (ReadAloud.state.value as? atom.voice.tts.ReadAloudState.Preparing)?.source == ReadAloudSource.AudioChat
        ) {
            ReadAloud.stop()
        }
    }
}
