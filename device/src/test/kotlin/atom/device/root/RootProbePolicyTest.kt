package atom.device.root

import atom.device.root.ProbeTrigger.AppStart
import atom.device.root.ProbeTrigger.Foreground
import atom.device.root.ProbeTrigger.Manual
import atom.device.root.ProbeTrigger.ToolUse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RootProbePolicyTest {
    private fun should(
        trigger: ProbeTrigger,
        memory: RootMemory,
        probing: Boolean = false,
        verified: Boolean = false,
        manager: Boolean = true,
        since: Long? = null,
    ) = RootProbePolicy.shouldProbe(trigger, memory, probing, verified, manager, since)

    private val fresh = RootMemory()
    private val granted = RootMemory(RootStatus.Granted, chosen = true)

    @Test
    fun appStartChecksAGrantAndAnInstalledRootManager() {
        assertTrue(should(AppStart, granted, manager = false))
        assertTrue(should(AppStart, fresh, manager = true))
        assertTrue(should(AppStart, RootMemory(RootStatus.NotDetected, chosen = true), manager = false))
        assertFalse(should(AppStart, fresh, manager = false), "no root on this phone: nothing is spawned")
        assertFalse(should(AppStart, granted, verified = true), "once per process")
    }

    @Test
    fun foregroundRechecksOnlyWhileRootIsNotGranted() {
        val refused = RootMemory(RootStatus.NotGranted, chosen = true)
        assertTrue(should(Foreground, refused))
        assertTrue(should(Foreground, RootMemory(RootStatus.NotDetected), manager = true))
        assertFalse(should(Foreground, granted))
        assertFalse(should(Foreground, RootMemory(RootStatus.NotDetected), manager = false))
    }

    @Test
    fun nothingStartsWhileAProbeRuns() {
        for (t in ProbeTrigger.entries) assertFalse(should(t, fresh, probing = true), "$t while probing")
    }

    @Test
    fun foregroundIsDebounced() {
        val m = RootMemory(RootStatus.NotGranted, chosen = true)
        assertFalse(should(Foreground, m, since = 1_000))
        assertFalse(should(Foreground, m, since = RootProbePolicy.FOREGROUND_GAP_MS - 1))
        assertTrue(should(Foreground, m, since = RootProbePolicy.FOREGROUND_GAP_MS))
    }

    @Test
    fun aRefusedPromptIsNeverShownAgainAutomatically() {
        // The user saw the prompt (a slow answer) and refused it.
        val after = RootProbePolicy.afterProbe(fresh, AppStart, RootStatus.NotGranted, durationMs = 6_000)
        assertTrue(RootProbePolicy.blocked(after))
        assertFalse(should(AppStart, after))
        assertFalse(should(Foreground, after))
        assertFalse(should(ToolUse, after))
        // Check again still works, and a quick refusal there (remembered by the root manager) unblocks.
        assertTrue(should(Manual, after))
        val manual = RootProbePolicy.afterProbe(after, Manual, RootStatus.NotGranted, durationMs = 80)
        assertFalse(RootProbePolicy.blocked(manual))
        assertTrue(should(Foreground, manual))
    }

    @Test
    fun aSilentRefusalKeepsBeingRechecked() {
        // KernelSU (and a remembered refusal) answer at once without a prompt: checking again is harmless.
        var m = fresh
        repeat(5) { m = RootProbePolicy.afterProbe(m, Foreground, RootStatus.NotGranted, durationMs = 50) }
        assertEquals(0, m.strikes)
        assertTrue(should(Foreground, m))
        // Then the user allows Atom in the root manager and comes back.
        m = RootProbePolicy.afterProbe(m, Foreground, RootStatus.Granted, durationMs = 60)
        assertEquals(RootMemory(RootStatus.Granted, chosen = true, strikes = 0), m)
        assertFalse(should(Foreground, m))
    }

    @Test
    fun anUnansweredPromptIsRetriedOnceThenLeftAlone() {
        val once = RootProbePolicy.afterProbe(fresh, AppStart, RootStatus.TimedOut, durationMs = RootAccess.PROBE_TIMEOUT_MS)
        assertFalse(RootProbePolicy.blocked(once))
        assertTrue(should(Foreground, once))
        val twice = RootProbePolicy.afterProbe(once, Foreground, RootStatus.TimedOut, durationMs = RootAccess.PROBE_TIMEOUT_MS)
        assertTrue(RootProbePolicy.blocked(twice))
        assertFalse(should(Foreground, twice))
        assertFalse(should(AppStart, twice))
    }

    @Test
    fun aGrantAlwaysClearsStrikesAndMarksRootAsChosen() {
        val blocked = RootMemory(RootStatus.NotGranted, strikes = RootProbePolicy.MAX_STRIKES)
        val m = RootProbePolicy.afterProbe(blocked, Manual, RootStatus.Granted, durationMs = 4_000)
        assertEquals(RootMemory(RootStatus.Granted, chosen = true, strikes = 0), m)
        // A grant lost later (root manager revoked Atom) is rechecked at start and on return.
        val lost = RootProbePolicy.afterProbe(m, AppStart, RootStatus.NotGranted, durationMs = 40)
        assertTrue(should(Foreground, lost, manager = false))
    }

    @Test
    fun toolsNeverCauseAPromptTheUserTurnedDown() {
        assertTrue(should(ToolUse, fresh, manager = false))
        assertTrue(should(ToolUse, granted))
        assertFalse(should(ToolUse, granted, verified = true))
        assertFalse(should(ToolUse, RootMemory(RootStatus.NotGranted)))
        assertFalse(should(ToolUse, RootMemory(RootStatus.TimedOut)))
        assertTrue(should(ToolUse, RootMemory(RootStatus.NotDetected), manager = true))
        assertFalse(should(ToolUse, RootMemory(RootStatus.NotDetected), manager = false))
    }

    @Test
    fun manualAlwaysProbesUnlessOneIsRunning() {
        val blocked = RootMemory(RootStatus.TimedOut, strikes = RootProbePolicy.MAX_STRIKES)
        assertTrue(should(Manual, blocked, verified = true, manager = false, since = 10))
        assertFalse(should(Manual, blocked, probing = true))
    }
}
