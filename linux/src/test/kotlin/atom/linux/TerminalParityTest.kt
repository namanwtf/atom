package atom.linux

import atom.linux.jobs.JobKind
import atom.linux.jobs.JobRecord
import atom.linux.jobs.JobRules
import atom.linux.jobs.JobState
import atom.linux.term.SessionStatus
import atom.linux.term.SessionStatuses
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalParityTest {
    @Test
    fun defaultDistributionIsDebianUnlessOnlyAlpineIsInstalled() {
        assertEquals(Distro.Debian, Distro.preferred(emptyList()))
        assertEquals(Distro.Alpine, Distro.preferred(listOf(Distro.Alpine)))
        assertEquals(Distro.Debian, Distro.preferred(listOf(Distro.Debian)))
        assertEquals(Distro.Debian, Distro.preferred(listOf(Distro.Alpine, Distro.Debian)))
        assertEquals("Debian", Distro.Debian.shortName)
        assertEquals("Alpine", Distro.Alpine.shortName)
    }

    @Test
    fun sessionLabels() {
        assertEquals(listOf(SessionStatus.Current, SessionStatus.Running), SessionStatuses.of(current = true, exited = false, busy = true))
        assertEquals(listOf(SessionStatus.Idle), SessionStatuses.of(current = false, exited = false, busy = false))
        assertEquals(listOf(SessionStatus.Running), SessionStatuses.of(current = false, exited = false, busy = true))
        assertEquals(listOf(SessionStatus.Exited), SessionStatuses.of(current = false, exited = true, busy = true))
        assertEquals(listOf(SessionStatus.Current, SessionStatus.Exited), SessionStatuses.of(current = true, exited = true, busy = false))
    }

    @Test
    fun linuxPageStages() {
        assertEquals(LinuxStage.NotInstalled, LinuxStage.of(LinuxState.NotInstalled))
        assertEquals(LinuxStage.NotInstalled, LinuxStage.of(LinuxState.Failed("x")))
        assertEquals(LinuxStage.Installing, LinuxStage.of(LinuxState.Installing(Distro.Debian, "Downloading", 0.2f)))
        assertEquals(LinuxStage.BaseReady, LinuxStage.of(LinuxState.Ready(Distro.Debian, 1, RunMode.Proot)))
        assertEquals(LinuxStage.ToolsReady, LinuxStage.of(LinuxState.Ready(Distro.Debian, 1, RunMode.Proot, toolsInstalled = true)))
        assertEquals(LinuxStage.RootLost, LinuxStage.of(LinuxState.Ready(Distro.Debian, 1, RunMode.Chroot, toolsInstalled = true, rootLost = true)))
    }

    @Test
    fun folderPickerStopsAtTheStorageRoot() {
        val root = "/storage/emulated/0"
        assertNull(SharedFolderRules.parentWithin(root, root))
        assertEquals(root, SharedFolderRules.parentWithin("$root/Download", root))
        assertEquals("$root/Download", SharedFolderRules.parentWithin("$root/Download/a/", root))
        assertNull(SharedFolderRules.parentWithin("/data", root))
    }

    private fun job(state: JobState, exit: Int?, kind: JobKind = JobKind.Job) = JobRecord(
        id = "j", kind = kind, name = "build", command = "make", env = ShellEnv.Linux, startedAtMs = 0, timeoutMs = 0,
        state = state, exitCode = exit, logPath = "/tmp/j.log",
    )

    @Test
    fun backgroundCommandNotificationCopy() {
        assertEquals("Background command completed", JobRules.notificationTitle(job(JobState.Completed, 0)))
        assertEquals("Background command failed", JobRules.notificationTitle(job(JobState.Failed, 2)))
        assertEquals("Background command timed out", JobRules.notificationTitle(job(JobState.TimedOut, 143)))
        assertEquals("Background command stopped", JobRules.notificationTitle(job(JobState.Stopped, null)))
        assertEquals("Background command interrupted", JobRules.notificationTitle(job(JobState.Interrupted, null)))
        assertEquals("Background service failed", JobRules.notificationTitle(job(JobState.Failed, 1, JobKind.Daemon)))
        val text = JobRules.notificationText(job(JobState.Failed, 2))
        assertTrue(text.startsWith("build · Exit code 2\n"))
        assertTrue(text.endsWith("View status and logs under Background tasks in Terminal"))
    }
}
