package atom.voice

import atom.voice.assist.AssistLockPolicy
import atom.voice.assist.AssistLockPolicy.Decision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssistLockPolicyTest {
    @Test
    fun lockedNeverShowsOrRunsAnything() {
        // With or without the overlay, a locked phone asks to unlock first.
        assertEquals(Decision.UnlockFirst, AssistLockPolicy.decide(locked = true, hasOverlay = true))
        assertEquals(Decision.UnlockFirst, AssistLockPolicy.decide(locked = true, hasOverlay = false))
        // A result Atom wants to re-present is not shown over the keyguard and does not prompt.
        assertEquals(Decision.Drop, AssistLockPolicy.decide(locked = true, hasOverlay = true, resumingResult = true))
        assertFalse(AssistLockPolicy.uiEnabled(locked = true))
    }

    @Test
    fun unlockedShowsTheAssistant() {
        assertEquals(Decision.Overlay, AssistLockPolicy.decide(locked = false, hasOverlay = true))
        assertEquals(Decision.Overlay, AssistLockPolicy.decide(locked = false, hasOverlay = true, resumingResult = true))
        assertEquals(Decision.LaunchApp, AssistLockPolicy.decide(locked = false, hasOverlay = false))
        assertTrue(AssistLockPolicy.uiEnabled(locked = false))
    }
}
