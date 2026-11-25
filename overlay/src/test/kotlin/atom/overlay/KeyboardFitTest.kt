package atom.overlay

import atom.overlay.assistant.AutoSpeak
import atom.overlay.assistant.HoldToTalkMath
import atom.overlay.panel.KeyboardFit
import atom.overlay.panel.KeyboardSignals
import atom.overlay.panel.KeyboardSource
import atom.overlay.panel.PanelFit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyboardFitTest {
    private fun signals(
        focused: Boolean = true,
        serviceTop: Int? = null,
        serviceSawKeyboard: Boolean = false,
        imeInset: Int = 0,
        insetsWork: Boolean = false,
        frameKeyboard: Int? = null,
        windowTop: Int = 0,
        windowHeight: Int = 2400,
        displayHeight: Int = 2400,
    ) = KeyboardSignals(focused, serviceTop, serviceSawKeyboard, imeInset, insetsWork, frameKeyboard, windowTop, windowHeight, displayHeight)

    private fun fit(s: KeyboardSignals) = KeyboardFit.keyboard(s)

    @Test
    fun measuredKeyboardTopComesFirst() {
        val r = fit(signals(serviceTop = 1500, serviceSawKeyboard = true, imeInset = 700))
        assertEquals(KeyboardSource.Measured, r.source)
        assertEquals(900, r.height)
    }

    @Test
    fun measuredTopIsRelativeToWhereTheWindowSits() {
        assertEquals(800, fit(signals(serviceTop = 1500, serviceSawKeyboard = true, windowTop = 100, windowHeight = 2200)).height)
    }

    @Test
    fun imeInsetIsUsedWhenNothingWasMeasured() {
        val r = fit(signals(imeInset = 840, insetsWork = true))
        assertEquals(KeyboardSource.Insets, r.source)
        assertEquals(840, r.height)
    }

    @Test
    fun aConnectedServiceThatNeverShowedAKeyboardIsUnknownNotClosed() {
        // The reference ROM: the service is connected but never lists the keyboard, and the overlay gets no insets.
        val r = fit(signals(focused = true, serviceTop = null, serviceSawKeyboard = false))
        assertEquals(KeyboardSource.Estimate, r.source)
        assertEquals((2400 * 0.32f).toInt(), r.height)
        // With insets it falls through to the insets, not to "closed".
        assertEquals(KeyboardSource.Insets, fit(signals(serviceTop = null, serviceSawKeyboard = false, imeInset = 700)).source)
    }

    @Test
    fun aServiceThatShowedTheKeyboardIsTrustedWhenItGoes() {
        val r = fit(signals(focused = true, serviceTop = null, serviceSawKeyboard = true, imeInset = 840))
        assertEquals(KeyboardSource.None, r.source)
        assertEquals(0, r.height)
    }

    @Test
    fun closedInsetsWinOverALaggingServiceReading() {
        // Insets work here and say closed; the service still lists the keyboard window from a stale cache.
        val r = fit(signals(serviceTop = 1500, serviceSawKeyboard = true, imeInset = 0, insetsWork = true))
        assertEquals(0, r.height)
        assertTrue(KeyboardFit.gone(signals(serviceTop = 1500, serviceSawKeyboard = true, imeInset = 0, insetsWork = true)))
    }

    @Test
    fun visibleFrameMeasuresWhenInsetsAreMissing() {
        val r = fit(signals(frameKeyboard = 900))
        assertEquals(KeyboardSource.Frame, r.source)
        assertEquals(900, r.height)
    }

    @Test
    fun frameShowingNoKeyboardClearsAStaleInsetAndTheEstimate() {
        // Back closed the keyboard: the inset was never re-delivered, focus is still on the field.
        assertEquals(0, fit(signals(focused = true, imeInset = 840, insetsWork = true, frameKeyboard = 0)).height)
        assertEquals(0, fit(signals(focused = true, frameKeyboard = 0)).height)
        assertTrue(KeyboardFit.gone(signals(frameKeyboard = 0)))
        assertFalse(KeyboardFit.gone(signals(frameKeyboard = null)))
    }

    @Test
    fun estimateIsTheLastResortAndNeedsFocus() {
        assertEquals(KeyboardSource.Estimate, fit(signals(focused = true)).source)
        assertEquals(0, fit(signals(focused = false)).height)
        // Insets have worked on this window before, so a zero inset means the keyboard is closed.
        assertEquals(0, fit(signals(focused = true, insetsWork = true)).height)
        // Already shrunk by the system.
        assertEquals(0, fit(signals(focused = true, windowHeight = 1500)).height)
    }

    @Test
    fun frameReadingIsUnknownUntilItHasShownAKeyboard() {
        // Window bottom 2400, navigation bar 63, threshold 210.
        assertNull(KeyboardFit.frameKeyboard(2400, 2337, 63, 210, seen = false))
        assertEquals(900, KeyboardFit.frameKeyboard(2400, 1500, 63, 210, seen = false))
        assertEquals(0, KeyboardFit.frameKeyboard(2400, 2337, 63, 210, seen = true))
    }

    @Test
    fun panelStaysTopAnchoredAndShrinksOnTheKeyboardThenGrowsBack() {
        val open = PanelFit.fit(2400, 100, 120, 0, margin = 16, minHeight = 735)
        val typing = PanelFit.fit(2400, 100, 120, fit(signals(imeInset = 900)).height, margin = 16, minHeight = 735)
        assertEquals(open.top, typing.top)
        assertEquals(2400 - 900 - 16, typing.top + typing.height)
        assertTrue(typing.height < open.height)
        val closed = PanelFit.fit(2400, 100, 120, fit(signals(focused = true, imeInset = 900, frameKeyboard = 0)).height, margin = 16, minHeight = 735)
        assertEquals(open, closed)
    }

    @Test
    fun autoSpeakOnlyForFinishedAnswersToSpokenQuestions() {
        assertTrue(AutoSpeak.should(enabled = true, shown = true, done = true, spokenQuestion = true, answer = "Hi", canSpeak = true))
        assertFalse(AutoSpeak.should(enabled = true, shown = true, done = true, spokenQuestion = false, answer = "Hi", canSpeak = true))
        assertFalse(AutoSpeak.should(enabled = false, shown = true, done = true, spokenQuestion = true, answer = "Hi", canSpeak = true))
        assertFalse(AutoSpeak.should(enabled = true, shown = false, done = true, spokenQuestion = true, answer = "Hi", canSpeak = true))
        assertFalse(AutoSpeak.should(enabled = true, shown = true, done = false, spokenQuestion = true, answer = "Hi", canSpeak = true))
        assertFalse(AutoSpeak.should(enabled = true, shown = true, done = true, spokenQuestion = true, answer = " ", canSpeak = true))
    }

    @Test
    fun holdToTalkCancelsWhenSlidAwayOrInterrupted() {
        assertFalse(HoldToTalkMath.cancelled(10f, 10f, cancelDistance = 100f))
        assertTrue(HoldToTalkMath.cancelled(0f, -120f, cancelDistance = 100f))
        assertEquals(HoldToTalkMath.Release.Send, HoldToTalkMath.onRelease(slidAway = false, interrupted = false))
        assertEquals(HoldToTalkMath.Release.Cancel, HoldToTalkMath.onRelease(slidAway = true, interrupted = false))
        assertEquals(HoldToTalkMath.Release.Cancel, HoldToTalkMath.onRelease(slidAway = false, interrupted = true))
    }
}
