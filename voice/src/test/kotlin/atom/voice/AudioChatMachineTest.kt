package atom.voice

import atom.voice.chat.AudioChatMachine
import atom.voice.chat.AudioChatMachine.Effect
import atom.voice.chat.AudioChatMachine.EndReason
import atom.voice.chat.AudioChatMachine.Event
import atom.voice.chat.AudioChatMachine.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AudioChatMachineTest {
    private fun handsFree() = AudioChatMachine(handsFree = true, speakAnswers = true)

    @Test
    fun fullHandsFreeLoop() {
        val m = handsFree()
        assertEquals(listOf<Effect>(Effect.Listen(20_000)), m.handle(Event.Start))
        assertEquals(State.Listening, m.state)
        assertEquals(listOf<Effect>(Effect.Send("turn on the flashlight")), m.handle(Event.Heard("  turn on the flashlight ")))
        assertEquals(State.Thinking("turn on the flashlight"), m.state)
        assertEquals(listOf<Effect>(Effect.Speak("Done.")), m.handle(Event.AnswerReady("Done.")))
        assertIs<State.Speaking>(m.state)
        assertEquals(listOf<Effect>(Effect.Listen(20_000)), m.handle(Event.SpeakingDone))
        assertEquals(State.Listening, m.state)
    }

    @Test
    fun silenceEndsTheSessionAfterAboutTwentySeconds() {
        val m = handsFree()
        m.handle(Event.Start)
        assertEquals(listOf<Effect>(Effect.Listen(12_000)), m.handle(Event.NoSpeech(8_000)))
        assertEquals(listOf<Effect>(Effect.Listen(4_000)), m.handle(Event.NoSpeech(8_000)))
        val end = m.handle(Event.NoSpeech(4_000))
        assertEquals(State.Ended(EndReason.Silence), m.state)
        assertTrue(Effect.ReleaseAll in end && Effect.CancelListening in end)
    }

    @Test
    fun speechResetsTheSilenceBudget() {
        val m = handsFree()
        m.handle(Event.Start)
        m.handle(Event.NoSpeech(15_000))
        m.handle(Event.Heard("hello"))
        m.handle(Event.AnswerReady("hi"))
        assertEquals(listOf<Effect>(Effect.Listen(20_000)), m.handle(Event.SpeakingDone))
        assertEquals(0, m.silentMs)
    }

    @Test
    fun bargeInStopsSpeakingAndListens() {
        val m = handsFree()
        m.handle(Event.Start)
        m.handle(Event.Heard("tell me a story"))
        m.handle(Event.AnswerReady("Once upon a time..."))
        assertEquals(listOf(Effect.StopSpeaking, Effect.Listen(20_000)), m.handle(Event.Interrupt))
        assertEquals(State.Listening, m.state)
        // A late "done" from the interrupted answer is ignored.
        assertEquals(emptyList(), m.handle(Event.SpeakingDone))
    }

    @Test
    fun tapWhileThinkingStopsTheRun() {
        val m = handsFree()
        m.handle(Event.Start)
        m.handle(Event.Heard("do something long"))
        assertEquals(listOf(Effect.StopRun, Effect.Listen(20_000)), m.handle(Event.Interrupt))
        // The stopped run's answer arriving late is never spoken.
        assertEquals(emptyList(), m.handle(Event.AnswerReady("late")))
    }

    @Test
    fun screenOffAndLeaveReleaseEverything() {
        for (event in listOf(Event.ScreenOff, Event.Leave)) {
            val m = handsFree()
            m.handle(Event.Start)
            m.handle(Event.Heard("x"))
            m.handle(Event.AnswerReady("y"))
            val effects = m.handle(event)
            assertEquals(listOf(Effect.CancelListening, Effect.StopSpeaking, Effect.ReleaseAll), effects)
            assertIs<State.Ended>(m.state)
            // Nothing happens after the end.
            assertEquals(emptyList(), m.handle(Event.Start))
            assertEquals(emptyList(), m.handle(Event.Heard("again")))
        }
    }

    @Test
    fun pushToTalk() {
        val m = AudioChatMachine(handsFree = false, speakAnswers = true)
        assertEquals(emptyList(), m.handle(Event.Start)) // waits for the user
        assertEquals(listOf<Effect>(Effect.Listen(null)), m.handle(Event.Press))
        assertEquals(listOf<Effect>(Effect.FinishListening), m.handle(Event.Release))
        m.handle(Event.Heard("what time is it"))
        m.handle(Event.AnswerReady("Noon."))
        assertEquals(emptyList(), m.handle(Event.SpeakingDone))
        assertEquals(State.Ready, m.state)
        // Empty turn in push-to-talk just returns to ready, no silence accounting.
        m.handle(Event.Press)
        m.handle(Event.NoSpeech(30_000))
        assertEquals(State.Ready, m.state)
    }

    @Test
    fun pressWhileSpeakingInterruptsInPushToTalk() {
        val m = AudioChatMachine(handsFree = false, speakAnswers = true)
        m.handle(Event.Press)
        m.handle(Event.Heard("q"))
        m.handle(Event.AnswerReady("a"))
        assertEquals(listOf(Effect.StopSpeaking, Effect.Listen(null)), m.handle(Event.Press))
    }

    @Test
    fun answersAreNotSpokenWhenSpeakingIsOff() {
        val m = AudioChatMachine(handsFree = true, speakAnswers = false)
        m.handle(Event.Start)
        m.handle(Event.Heard("q"))
        assertEquals(listOf<Effect>(Effect.Listen(20_000)), m.handle(Event.AnswerReady("a")))
    }

    @Test
    fun errorsReturnToReady() {
        val m = handsFree()
        m.handle(Event.Start)
        assertEquals(listOf<Effect>(Effect.ShowError("Network")), m.handle(Event.RecognitionFailed("Network")))
        assertEquals(State.Ready, m.state)
        assertEquals(listOf<Effect>(Effect.Listen(20_000)), m.handle(Event.Interrupt)) // tap to retry
        m.handle(Event.Heard("q"))
        m.handle(Event.RunFailed("boom"))
        assertEquals(State.Ready, m.state)
    }

    @Test
    fun switchingToPushToTalkCancelsListening() {
        val m = handsFree()
        m.handle(Event.Start)
        assertEquals(listOf<Effect>(Effect.CancelListening), m.handle(Event.ModeChanged(handsFree = false)))
        assertEquals(State.Ready, m.state)
    }
}
