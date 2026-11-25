package atom.voice.chat

/**
 * The audio-chat conversation loop as a pure state machine: listen -> end of speech -> send -> speak the final
 * answer -> listen again. The screen feeds it events and performs the returned effects; nothing here touches
 * Android, so every transition is unit tested.
 *
 * Battery rules encoded here: the microphone is open only in [State.Listening] (and, for voice barge-in, during
 * [State.Speaking]); any [Event.Leave] / [Event.ScreenOff] ends everything; ~20 s without speech ends a hands-free
 * session.
 */
class AudioChatMachine(
    var handsFree: Boolean,
    var speakAnswers: Boolean,
    private val silenceBudgetMs: Long = SILENCE_END_MS,
) {
    sealed interface State {
        /** Waiting for the user (push-to-talk, or hands-free before start / after an error). */
        data object Ready : State
        data object Listening : State
        data class Thinking(val prompt: String) : State
        data class Speaking(val answer: String) : State
        data class Ended(val reason: EndReason) : State
    }

    enum class EndReason { Silence, ScreenOff, Left, User }

    sealed interface Event {
        data object Start : Event
        /** Push-to-talk press. */
        data object Press : Event
        /** Push-to-talk release (finish the recording and send). */
        data object Release : Event
        data class Heard(val text: String) : Event
        /** A listening turn ended without speech after [listenedMs]. */
        data class NoSpeech(val listenedMs: Long) : Event
        data class RecognitionFailed(val message: String) : Event
        data class AnswerReady(val answer: String) : Event
        data class RunFailed(val message: String) : Event
        data object SpeakingDone : Event
        /** Tap on the orb, or the user started talking over the answer. */
        data object Interrupt : Event
        data class ModeChanged(val handsFree: Boolean) : Event
        data object ScreenOff : Event
        data object Leave : Event
        data object EndByUser : Event
    }

    sealed interface Effect {
        /** Open the mic for one turn; stop by itself after [noSpeechTimeoutMs] without speech (null = user ends it). */
        data class Listen(val noSpeechTimeoutMs: Long?) : Effect
        /** Finish the current recording now and deliver its text. */
        data object FinishListening : Effect
        data object CancelListening : Effect
        data class Send(val text: String) : Effect
        data object StopRun : Effect
        data class Speak(val text: String) : Effect
        data object StopSpeaking : Effect
        data class ShowError(val message: String) : Effect
        /** Release every audio resource (mic, player, focus). */
        data object ReleaseAll : Effect
    }

    var state: State = State.Ready
        private set

    /** Silence accumulated across consecutive empty hands-free turns. */
    var silentMs: Long = 0
        private set

    fun handle(event: Event): List<Effect> {
        val s = state
        if (s is State.Ended) return emptyList()
        return when (event) {
            Event.Leave -> end(EndReason.Left)
            Event.ScreenOff -> end(EndReason.ScreenOff)
            Event.EndByUser -> end(EndReason.User)
            Event.Start -> if (s == State.Ready && handsFree) listen() else emptyList()
            Event.Press -> when (s) {
                State.Ready -> listen(timeout = null)
                is State.Speaking -> listOf(Effect.StopSpeaking) + listen(timeout = null)
                else -> emptyList()
            }
            Event.Release -> if (s == State.Listening) listOf(Effect.FinishListening) else emptyList()
            is Event.Heard -> if (s != State.Listening) {
                emptyList()
            } else if (event.text.isBlank()) {
                afterEmptyTurn(0)
            } else {
                silentMs = 0
                state = State.Thinking(event.text.trim())
                listOf(Effect.Send(event.text.trim()))
            }
            is Event.NoSpeech -> if (s == State.Listening) afterEmptyTurn(event.listenedMs) else emptyList()
            is Event.RecognitionFailed -> if (s == State.Listening) {
                state = State.Ready
                listOf(Effect.ShowError(event.message))
            } else {
                emptyList()
            }
            is Event.AnswerReady -> if (s is State.Thinking) {
                if (speakAnswers && event.answer.isNotBlank()) {
                    state = State.Speaking(event.answer)
                    listOf(Effect.Speak(event.answer))
                } else {
                    next()
                }
            } else {
                emptyList()
            }
            is Event.RunFailed -> if (s is State.Thinking) {
                state = State.Ready
                listOf(Effect.ShowError(event.message))
            } else {
                emptyList()
            }
            Event.SpeakingDone -> if (s is State.Speaking) next() else emptyList()
            Event.Interrupt -> when (s) {
                is State.Speaking -> listOf(Effect.StopSpeaking) + listen()
                is State.Thinking -> listOf(Effect.StopRun) + listen()
                State.Listening -> listOf(Effect.FinishListening)
                State.Ready -> listen(timeout = if (handsFree) remainingSilence() else null)
                else -> emptyList()
            }
            is Event.ModeChanged -> {
                handsFree = event.handsFree
                if (!handsFree && s == State.Listening) {
                    state = State.Ready
                    listOf(Effect.CancelListening)
                } else {
                    emptyList()
                }
            }
        }
    }

    private fun remainingSilence(): Long = (silenceBudgetMs - silentMs).coerceAtLeast(MIN_TURN_MS)

    private fun listen(timeout: Long? = remainingSilence()): List<Effect> {
        state = State.Listening
        return listOf(Effect.Listen(if (handsFree) timeout else null))
    }

    /** After an answer (spoken or not): hands-free listens again, push-to-talk waits. */
    private fun next(): List<Effect> = if (handsFree) {
        silentMs = 0
        listen()
    } else {
        state = State.Ready
        emptyList()
    }

    private fun afterEmptyTurn(listenedMs: Long): List<Effect> {
        if (!handsFree) {
            state = State.Ready
            return emptyList()
        }
        silentMs += listenedMs
        return if (silentMs >= silenceBudgetMs - MIN_TURN_MS / 2) end(EndReason.Silence) else listen()
    }

    private fun end(reason: EndReason): List<Effect> {
        state = State.Ended(reason)
        return listOf(Effect.CancelListening, Effect.StopSpeaking, Effect.ReleaseAll)
    }

    companion object {
        /** A hands-free session ends after about this much silence. */
        const val SILENCE_END_MS = 20_000L
        const val MIN_TURN_MS = 2_000L
    }
}
