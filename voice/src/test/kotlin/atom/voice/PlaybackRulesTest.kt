package atom.voice

import atom.voice.tts.PlaybackAction
import atom.voice.tts.PlaybackEvent
import atom.voice.tts.PlaybackRules
import atom.voice.tts.ReadAloudSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackRulesTest {
    @Test
    fun recordingAlwaysStopsPlayback() {
        ReadAloudSource.entries.forEach { assertEquals(PlaybackAction.Stop, PlaybackRules.decide(it, PlaybackEvent.RecordingStarted)) }
    }

    @Test
    fun focusLossStopsButADingOnlyDucks() {
        val s = ReadAloudSource.Chat
        assertEquals(PlaybackAction.Stop, PlaybackRules.decide(s, PlaybackEvent.FocusLost(transient = false, canDuck = false)))
        assertEquals(PlaybackAction.Stop, PlaybackRules.decide(s, PlaybackEvent.FocusLost(transient = true, canDuck = false)))
        assertEquals(PlaybackAction.Duck, PlaybackRules.decide(s, PlaybackEvent.FocusLost(transient = true, canDuck = true)))
        assertEquals(PlaybackAction.Unduck, PlaybackRules.decide(s, PlaybackEvent.FocusGained))
    }

    @Test
    fun unpluggingHeadphonesStops() {
        ReadAloudSource.entries.forEach { assertEquals(PlaybackAction.Stop, PlaybackRules.decide(it, PlaybackEvent.BecomingNoisy)) }
    }

    @Test
    fun hidingTheOwnerStopsExceptChatAnswers() {
        assertEquals(PlaybackAction.Stop, PlaybackRules.decide(ReadAloudSource.Overlay, PlaybackEvent.OwnerHidden(ReadAloudSource.Overlay)))
        assertEquals(PlaybackAction.Stop, PlaybackRules.decide(ReadAloudSource.AudioChat, PlaybackEvent.OwnerHidden(ReadAloudSource.AudioChat)))
        assertEquals(PlaybackAction.Stop, PlaybackRules.decide(ReadAloudSource.Preview, PlaybackEvent.OwnerHidden(ReadAloudSource.Preview)))
        assertEquals(PlaybackAction.Continue, PlaybackRules.decide(ReadAloudSource.Chat, PlaybackEvent.OwnerHidden(ReadAloudSource.Chat)))
        // Another page hiding does not touch this audio.
        assertEquals(PlaybackAction.Continue, PlaybackRules.decide(ReadAloudSource.AudioChat, PlaybackEvent.OwnerHidden(ReadAloudSource.Overlay)))
    }

    @Test
    fun nothingIsSpokenLate() {
        assertTrue(PlaybackRules.mayStart(requestedAtMs = 0, nowMs = 3_000, generationAtRequest = 4, currentGeneration = 4))
        assertFalse(PlaybackRules.mayStart(0, PlaybackRules.STALE_REQUEST_MS + 1, 4, 4))
        assertFalse(PlaybackRules.mayStart(0, 100, generationAtRequest = 4, currentGeneration = 5))
    }

    @Test
    fun onlyChatAnswersUseTheBackgroundService() {
        assertTrue(PlaybackRules.needsForegroundService(ReadAloudSource.Chat))
        assertFalse(PlaybackRules.needsForegroundService(ReadAloudSource.Overlay))
        assertFalse(PlaybackRules.needsForegroundService(ReadAloudSource.AudioChat))
        assertFalse(PlaybackRules.needsForegroundService(ReadAloudSource.Preview))
    }

    @Test
    fun onlyTheAssistantOverlayPostsTheAssistantVoiceNotice() {
        assertTrue(PlaybackRules.showsAssistantVoiceNotice(ReadAloudSource.Overlay))
        assertFalse(PlaybackRules.showsAssistantVoiceNotice(ReadAloudSource.Chat))
        assertFalse(PlaybackRules.showsAssistantVoiceNotice(ReadAloudSource.AudioChat))
        assertFalse(PlaybackRules.showsAssistantVoiceNotice(ReadAloudSource.Preview))
    }
}
