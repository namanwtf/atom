package atom.overlay

import atom.overlay.assistant.AutoSpeak
import atom.overlay.assistant.HoldToTalkMath
import atom.overlay.panel.KeyboardFit
import atom.overlay.panel.KeyboardSource
import atom.overlay.panel.PanelFit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeyboardFitTest {
    private fun fit(
        focused: Boolean = true,
        measuredAvailable: Boolean = false,
        measuredTop: Int? = null,
        imeInset: Int = 0,
        windowTop: Int = 0,
        windowHeight: Int = 2400,
        displayHeight: Int = 2400,
    ) = KeyboardFit.keyboard(focused, measuredAvailable, measuredTop, imeInset, windowTop, windowHeight, displayHeight)

    @Test
    fun measuredKeyboardTopComesFirst() {
        val r = fit(measuredAvailable = true, measuredTop = 1500, imeInset = 700)
        assertEquals(KeyboardSource.Measured, r.source)
        assertEquals(900, r.height)
    }

    @Test
    fun measuredTopIsRelativeToWhereTheWindowSits() {
        val r = fit(measuredAvailable = true, measuredTop = 1500, windowTop = 100, windowHeight = 2200)
        assertEquals(800, r.height)
    }

    @Test
    fun imeInsetIsUsedWhenNothingWasMeasured() {
        val r = fit(measuredAvailable = false, imeInset = 840)
        assertEquals(KeyboardSource.Insets, r.source)
        assertEquals(840, r.height)
        // Also when the service is connected but has not seen the keyboard window yet.
        assertEquals(KeyboardSource.Insets, fit(measuredAvailable = true, measuredTop = null, imeInset = 840).source)
    }

    @Test
    fun estimateIsTheLastResortAndNeedsFocus() {
        val r = fit(focused = true)
        assertEquals(KeyboardSource.Estimate, r.source)
        assertEquals((2400 * 0.32f).toInt(), r.height)
        assertEquals(0, fit(focused = false).height)
    }

    @Test
    fun aConnectedServiceThatSeesNoKeyboardMeansNoKeyboard() {
        val r = fit(focused = true, measuredAvailable = true, measuredTop = null)
        assertEquals(KeyboardSource.None, r.source)
        assertEquals(0, r.height)
    }

    @Test
    fun aWindowTheSystemAlreadyShrankNeedsNoEstimate() {
        assertEquals(0, fit(focused = true, windowHeight = 1500, displayHeight = 2400).height)
    }

    @Test
    fun panelStaysTopAnchoredAndShrinksOnTheKeyboard() {
        val open = PanelFit.fit(2400, 100, 120, 0, margin = 16, minHeight = 735)
        val typing = PanelFit.fit(2400, 100, 120, fit(imeInset = 900).height, margin = 16, minHeight = 735)
        assertEquals(open.top, typing.top)
        assertEquals(2400 - 900 - 16, typing.top + typing.height)
        assertTrue(typing.height < open.height)
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
    fun holdToTalkCancelsOnlyWhenSlidAway() {
        assertFalse(HoldToTalkMath.cancelled(10f, 10f, cancelDistance = 100f))
        assertTrue(HoldToTalkMath.cancelled(0f, -120f, cancelDistance = 100f))
        assertEquals(HoldToTalkMath.Release.Send, HoldToTalkMath.onRelease(cancelled = false))
        assertEquals(HoldToTalkMath.Release.Cancel, HoldToTalkMath.onRelease(cancelled = true))
    }
}
