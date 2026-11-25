package atom.linux.jobs

import atom.linux.ShellEnv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JobRulesTest {
    private fun job(id: String, state: JobState = JobState.Running, ended: Long? = null, kind: JobKind = JobKind.Job) = JobRecord(
        id = id, kind = kind, name = id, command = "sleep 1", env = ShellEnv.Linux, startedAtMs = 0, timeoutMs = 600_000,
        state = state, endedAtMs = ended, pid = 100, pidStartTicks = 5, logPath = "/tmp/$id.log",
    )

    @Test
    fun finishMapsReasonsAndExitCodes() {
        assertEquals(JobState.Completed, JobRules.finish(job("a"), 0, EndReason.Exited, 9).state)
        assertEquals(JobState.Failed, JobRules.finish(job("a"), 2, EndReason.Exited, 9).state)
        assertEquals(JobState.TimedOut, JobRules.finish(job("a"), 143, EndReason.TimedOut, 9).state)
        assertEquals(JobState.Stopped, JobRules.finish(job("a"), 143, EndReason.Stopped, 9).state)
        val done = JobRules.finish(job("a"), 2, EndReason.Exited, 9)
        assertEquals(2, done.exitCode)
        assertEquals(9L, done.endedAtMs)
        // A finished job stays as it was (e.g. the exit arrives after a stop already recorded).
        assertEquals(done, JobRules.finish(done, 0, EndReason.Stopped, 20))
    }

    @Test
    fun restartInterruptsAndKillsSurvivors() {
        val killed = mutableListOf<String>()
        val out = JobRules.reconcileAfterRestart(
            listOf(job("alive"), job("dead"), job("old", JobState.Completed, 5)),
            now = 50,
            stillAlive = { it.id == "alive" },
            kill = { killed += it.id },
        )
        assertEquals(listOf("alive"), killed)
        assertEquals(listOf(JobState.Interrupted, JobState.Interrupted, JobState.Completed), out.map { it.state })
        assertEquals(50L, out[0].endedAtMs)
    }

    @Test
    fun pruneKeepsRunningAndNewestFinished() {
        val list = listOf(job("r")) + (1..5).map { job("f$it", JobState.Completed, it.toLong()) }
        val (kept, dropped) = JobRules.prune(list, keep = 2)
        assertEquals(setOf("r", "f5", "f4"), kept.map { it.id }.toSet())
        assertEquals(setOf("f1", "f2", "f3"), dropped.map { it.id }.toSet())
    }

    @Test
    fun timeoutsRespectTheCap() {
        assertEquals(600_000L, JobRules.effectiveTimeoutMs(JobKind.Job, null, 10))
        assertEquals(120_000L, JobRules.effectiveTimeoutMs(JobKind.Job, 2, 10))
        assertEquals(600_000L, JobRules.effectiveTimeoutMs(JobKind.Job, 60, 10)) // never above the cap
        assertEquals(0L, JobRules.effectiveTimeoutMs(JobKind.Job, null, 0)) // cap disabled: no limit
        assertEquals(3_600_000L, JobRules.effectiveTimeoutMs(JobKind.Job, 60, 0))
        assertEquals(0L, JobRules.effectiveTimeoutMs(JobKind.Daemon, 5, 10)) // daemons never time out
    }

    @Test
    fun descriptionsAndNames() {
        assertTrue(JobRules.describe(JobRules.finish(job("x"), 3, EndReason.Exited, 1)).contains("exit 3"))
        assertTrue(JobRules.describe(job("sshd", kind = JobKind.Daemon)).startsWith("Daemon sshd"))
        assertEquals("make -j8", JobRules.titleFor("  make -j8\nmake install"))
        assertTrue(JobRules.validDaemonName("sshd-8022"))
        assertTrue(!JobRules.validDaemonName("bad name"))
    }
}
