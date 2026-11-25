package atom.core

import atom.core.runtime.LiveUpdate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveUpdateTest {
    @Test
    fun promotesOnlyOnAndroid16WithTheSettingOnAndOneRun() {
        assertTrue(LiveUpdate.promote(sdkInt = 36, enabled = true, singleRun = true))
        assertTrue(LiveUpdate.promote(sdkInt = 37, enabled = true, singleRun = true))
        assertFalse(LiveUpdate.promote(sdkInt = 35, enabled = true, singleRun = true))
        assertFalse(LiveUpdate.promote(sdkInt = 36, enabled = false, singleRun = true))
        assertFalse(LiveUpdate.promote(sdkInt = 36, enabled = true, singleRun = false))
    }

    @Test
    fun chipFollowsTheRun() {
        assertEquals(LiveUpdate.Chip.Working, LiveUpdate.chip(paused = false, waitingForUser = false, usingTool = true, streamingAnswer = false))
        assertEquals(LiveUpdate.Chip.Working, LiveUpdate.chip(paused = false, waitingForUser = false, usingTool = false, streamingAnswer = false))
        assertEquals(LiveUpdate.Chip.Answering, LiveUpdate.chip(paused = false, waitingForUser = false, usingTool = false, streamingAnswer = true))
        assertEquals(LiveUpdate.Chip.Working, LiveUpdate.chip(paused = false, waitingForUser = false, usingTool = true, streamingAnswer = true))
        assertEquals(LiveUpdate.Chip.Paused, LiveUpdate.chip(paused = true, waitingForUser = true, usingTool = true, streamingAnswer = true))
        assertEquals(LiveUpdate.Chip.Waiting, LiveUpdate.chip(paused = false, waitingForUser = true, usingTool = false, streamingAnswer = true))
    }
}
