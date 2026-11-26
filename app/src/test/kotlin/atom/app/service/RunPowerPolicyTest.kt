package atom.app.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunPowerPolicyTest {
    /** The window flag: a run executing (setting on, no timeout) AND the UI in front. Every other cell is off. */
    @Test fun screenStaysOnOnlyWhileRunningAndVisible() {
        for (setting in listOf(true, false)) {
            for (executing in listOf(0, 1, 3)) {
                for (timedOut in listOf(true, false)) {
                    for (inFront in listOf(true, false)) {
                        val keep = RunPowerPolicy.keepsScreenOn(setting, executing, timedOut)
                        val expected = setting && executing > 0 && !timedOut && inFront
                        assertEquals(
                            expected,
                            RunPowerPolicy.windowKeepsScreenOn(keep, inFront),
                            "setting=$setting executing=$executing timedOut=$timedOut inFront=$inFront",
                        )
                    }
                }
            }
        }
    }

    @Test fun theFlagGoesTheMomentTheRunEnds() {
        assertTrue(RunPowerPolicy.windowKeepsScreenOn(RunPowerPolicy.keepsScreenOn(true, 1, false), true))
        // Success, failure and stop all bring the executing count to 0.
        assertFalse(RunPowerPolicy.windowKeepsScreenOn(RunPowerPolicy.keepsScreenOn(true, 0, false), true))
        // The safety timeout lets go of a run that stopped making progress.
        assertFalse(RunPowerPolicy.windowKeepsScreenOn(RunPowerPolicy.keepsScreenOn(true, 1, true), true))
    }

    @Test fun aWakeLockOnlyWithTheScreenOffAndWorkRunning() {
        assertTrue(RunPowerPolicy.wantsWakeLock(executing = 1, interactive = false))
        assertFalse(RunPowerPolicy.wantsWakeLock(executing = 1, interactive = true))
        assertFalse(RunPowerPolicy.wantsWakeLock(executing = 0, interactive = false))
    }
}
