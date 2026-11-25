package atom.overlay

import atom.overlay.assistant.AssistPrompt
import atom.overlay.assistant.ScreenContext
import atom.overlay.assistant.ScreenText
import atom.overlay.bubble.BubbleMood
import atom.overlay.bubble.BubbleReducer
import atom.overlay.panel.PanelFit
import atom.overlay.panel.PanelLog
import atom.overlay.panel.SheetBox
import atom.overlay.run.ScreenUse
import atom.ui.feature.HostApproval
import atom.ui.feature.HostRun
import atom.ui.feature.HostStep
import atom.ui.feature.RunPhase
import atom.ui.feature.RunSource
import atom.ui.feature.StepState
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverlayLogicTest {
    private fun run(
        id: String,
        phase: RunPhase,
        source: RunSource = RunSource.Chat,
        steps: List<HostStep> = emptyList(),
        text: String = "",
        pending: HostApproval? = null,
    ) = HostRun(id, "t", source, phase, text, steps, pending)

    private fun step(tool: String, state: StepState = StepState.Completed) = HostStep(tool, tool, "", state)

    // ------------------------------------------------------------------ Bubble state

    @Test
    fun idleWithoutActiveRuns() {
        assertEquals(BubbleMood.Idle, BubbleReducer.mood(emptyList(), "b"))
        assertEquals(BubbleMood.Idle, BubbleReducer.mood(listOf(run("b", RunPhase.Done, text = "x")), "b"))
    }

    @Test
    fun phasesMapToMoods() {
        assertEquals(BubbleMood.Thinking, BubbleReducer.moodOf(run("a", RunPhase.Starting)))
        assertEquals(BubbleMood.Thinking, BubbleReducer.moodOf(run("a", RunPhase.WaitingForControl)))
        assertEquals(BubbleMood.Thinking, BubbleReducer.moodOf(run("a", RunPhase.Running)))
        assertEquals(BubbleMood.Working, BubbleReducer.moodOf(run("a", RunPhase.Running, steps = listOf(step("tap")))))
        assertEquals(BubbleMood.Working, BubbleReducer.moodOf(run("a", RunPhase.Running, text = "Hi")))
        assertEquals(BubbleMood.Paused, BubbleReducer.moodOf(run("a", RunPhase.Paused)))
        assertEquals(BubbleMood.Waiting, BubbleReducer.moodOf(run("a", RunPhase.WaitingForApproval)))
        assertEquals(BubbleMood.Waiting, BubbleReducer.moodOf(run("a", RunPhase.Running, pending = HostApproval(1, "Send", 0))))
    }

    @Test
    fun ownConversationWinsOverOtherRuns() {
        val runs = listOf(
            run("chat", RunPhase.Paused),
            run("other-bubble", RunPhase.Running, RunSource.Bubble, text = "x"),
            run("mine", RunPhase.WaitingForApproval, RunSource.Bubble),
        )
        assertEquals(BubbleMood.Waiting, BubbleReducer.mood(runs, "mine"))
        assertEquals(BubbleMood.Working, BubbleReducer.mood(runs, null))
        assertEquals(BubbleMood.Paused, BubbleReducer.mood(listOf(run("chat", RunPhase.Paused)), "mine"))
    }

    @Test
    fun onlyLiveMoodsAnimate() {
        assertFalse(BubbleMood.Idle.animates)
        assertFalse(BubbleMood.Paused.animates)
        assertFalse(BubbleMood.Waiting.animates)
        assertTrue(BubbleMood.Working.animates)
        assertTrue(BubbleMood.Thinking.animates)
    }

    // ------------------------------------------------------------------ Keyboard fit

    @Test
    fun sheetSpansBetweenSystemBars() {
        assertEquals(SheetBox(top = 108, height = 2400 - 108 - 128), PanelFit.fit(2400, 100, 120, 0, margin = 8, minHeight = 600))
    }

    @Test
    fun sheetSitsOnTheKeyboard() {
        val box = PanelFit.fit(2400, 100, 120, 1000, margin = 8, minHeight = 600)
        assertEquals(108, box.top)
        assertEquals(2400 - 1008, box.top + box.height)
    }

    @Test
    fun navigationBarWinsWhenTheKeyboardIsShorter() {
        assertEquals(PanelFit.fit(2400, 100, 120, 0, 8, 600), PanelFit.fit(2400, 100, 120, 40, 8, 600))
    }

    @Test
    fun tallKeyboardKeepsAMinimumHeightAndNeverGoesAboveTheWindow() {
        val box = PanelFit.fit(1080, 60, 0, 700, margin = 8, minHeight = 400)
        assertEquals(1080 - 708, box.top + box.height)
        assertEquals(0, box.top)
        val squeezed = PanelFit.fit(1080, 60, 0, 650, margin = 8, minHeight = 400)
        assertEquals(SheetBox(top = 1080 - 658 - 400, height = 400), squeezed)
        assertEquals(SheetBox(top = 68, height = 1080 - 508 - 68), PanelFit.fit(1080, 60, 0, 500, margin = 8, minHeight = 400))
    }

    // ------------------------------------------------------------------ Screen use

    @Test
    fun recognisesScreenTools() {
        listOf("read_screen", "tap", "long_press", "type_text", "scroll", "swipe", "global_action", "open_app", "uiTap", "click_element", "takeScreenshot")
            .forEach { assertTrue(ScreenUse.isScreenTool(it), it) }
        listOf("web_search", "read_url", "shell", "memory_write", "get_clipboard", "calendar_events", "typescript_eval")
            .forEach { assertFalse(ScreenUse.isScreenTool(it), it) }
    }

    @Test
    fun onlyActiveRunsDriveTheScreen() {
        assertTrue(ScreenUse.looksLikeScreenUse(run("a", RunPhase.Running, steps = listOf(step("web_search"), step("tap", StepState.Running)))))
        assertFalse(ScreenUse.looksLikeScreenUse(run("a", RunPhase.Running, steps = listOf(step("web_search")))))
        assertFalse(ScreenUse.looksLikeScreenUse(run("a", RunPhase.Done, steps = listOf(step("tap")))))
    }

    // ------------------------------------------------------------------ Screen context

    @Test
    fun passwordAndCardFieldsAreSensitive() {
        assertTrue(ScreenText.isSensitive(0x81, null, null))
        assertTrue(ScreenText.isSensitive(0x91, null, null))
        assertTrue(ScreenText.isSensitive(0xe1, null, null))
        assertTrue(ScreenText.isSensitive(0x12, null, null))
        assertTrue(ScreenText.isSensitive(0x1, arrayOf("password"), null))
        assertTrue(ScreenText.isSensitive(0x1, arrayOf("creditCardNumber"), null))
        assertTrue(ScreenText.isSensitive(0x1, arrayOf("smsOTPCode"), null))
        assertTrue(ScreenText.isSensitive(0x1, null, "login_password"))
        assertFalse(ScreenText.isSensitive(0x1, arrayOf("emailAddress", "shippingAddress"), "user_name"))
        assertFalse(ScreenText.isSensitive(0x2, null, null))
        assertFalse(ScreenText.isSensitive(0, null, null))
    }

    @Test
    fun collectorDedupesCollapsesAndRespectsTheBudget() {
        val c = ScreenText.Collector(maxChars = 20)
        c.add("  Hello\n  world ")
        c.add("Hello world")
        c.add(null)
        c.add("   ")
        c.add("Second line here")
        assertEquals("Hello world\nSecond…", c.text())
        assertTrue(c.text().length <= 20)
        assertTrue(c.full)
    }

    @Test
    fun firstPromptCarriesTheScreenContext() {
        val ctx = ScreenContext("Chrome", "com.android.chrome", "https://example.com", "Price: 12\nBuy now", capturedAt = 0L)
        val prompt = AssistPrompt.build("What is this?", ctx, firstTurn = true, hasScreenshot = true, now = 0L, zone = ZoneOffset.UTC)
        assertTrue(prompt.startsWith("What is this?\n\n[Screen context captured at 00:00"))
        assertTrue("App: Chrome (com.android.chrome)" in prompt)
        assertTrue("URL: https://example.com" in prompt)
        assertTrue("Visible text:\nPrice: 12\nBuy now" in prompt)
        assertTrue("screenshot" in prompt)
    }

    @Test
    fun followUpsNoteWhenTheSnapshotWasTaken() {
        val ctx = ScreenContext(null, null, null, "x", capturedAt = 0L)
        val prompt = AssistPrompt.build("And this?", ctx, firstTurn = false, hasScreenshot = false, now = 3 * 60_000L, zone = ZoneOffset.UTC)
        assertEquals("And this?\n\n[Same screen context as before, captured at 00:00 (3 minutes ago)]", prompt)
        assertEquals("Plain", AssistPrompt.build("Plain", null, firstTurn = true, hasScreenshot = false, now = 0L))
        assertEquals("just now", AssistPrompt.age(20_000))
        assertEquals("1 minute ago", AssistPrompt.age(61_000))
        assertEquals("2 h ago", AssistPrompt.age(125 * 60_000L))
    }

    // ------------------------------------------------------------------ Mini chat log

    @Test
    fun logKeepsTheNewestWithinLimits() {
        val many = (1..100).map { OverlayMessage(it % 2 == 0, "m$it") }
        val trimmed = PanelLog.trim(many)
        assertEquals(PanelLog.MAX_MESSAGES, trimmed.size)
        assertEquals("m100", trimmed.last().text)
        val huge = listOf(OverlayMessage(true, "a".repeat(PanelLog.MAX_CHARS)), OverlayMessage(false, "b"))
        assertEquals(listOf(OverlayMessage(false, "b")), PanelLog.trim(huge))
    }
}
