package atom.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HealthCheckTest {
    private val gb = 2_000_000_000L
    private fun level(r: HealthReport, key: String) = r.items.first { it.key == key }.level

    @Test
    fun healthySystem() {
        val out = "WORKSPACE=ok\nSDCARD=ok\nRESOLV=ok\nAPK_OK=0\nDONE=1\n"
        val r = HealthCheck.evaluate(out, 0, gb, toolsInstalled = true, sharedFolders = emptyList(), unreadableFolders = emptyList())
        assertEquals(HealthReport.Level.Ok, r.overall)
        assertFalse(r.repairable)
    }

    @Test
    fun problemsAreReportedAndRepairable() {
        val out = "MISSING_CORE=tar\nMISSING_TOOL=rg\nMISSING_TOOL=jq\nWORKSPACE=bad\nSDCARD=bad\nRESOLV=bad\nDPKG_AUDIT=3\nDONE=1\n"
        val folder = SharedFolder("dl", "/storage/emulated/0/Download")
        val r = HealthCheck.evaluate(out, 0, 50_000_000, true, listOf(folder), listOf(folder))
        assertEquals(HealthReport.Level.Problem, level(r, "core"))
        assertTrue(r.items.first { it.key == "tools" }.detail.contains("rg, jq"))
        assertEquals(HealthReport.Level.Problem, level(r, "workspace"))
        assertEquals(HealthReport.Level.Warning, level(r, "sdcard"))
        assertEquals(HealthReport.Level.Warning, level(r, "folders"))
        assertEquals(HealthReport.Level.Problem, level(r, "dns"))
        assertEquals(HealthReport.Level.Problem, level(r, "packages"))
        assertEquals(HealthReport.Level.Problem, level(r, "space"))
        assertTrue(r.repairable)
    }

    @Test
    fun notStartingAtAll() {
        val r = HealthCheck.evaluate("proot error: ...", 1, gb, true, emptyList(), emptyList())
        assertEquals(HealthReport.Level.Problem, level(r, "start"))
        assertEquals(2, r.items.size)
    }

    @Test
    fun toolsNotInstalledIsOnlyAWarningAndSpaceThresholds() {
        val r = HealthCheck.evaluate("WORKSPACE=ok\nSDCARD=ok\nRESOLV=ok\nDONE=1", 0, 300_000_000, false, emptyList(), emptyList())
        assertEquals(HealthReport.Level.Warning, level(r, "tools"))
        assertEquals(HealthReport.Level.Warning, level(r, "space"))
    }

    @Test
    fun probeScriptCoversTheAreas() {
        val s = HealthCheck.script(Distro.Debian)
        for (k in listOf("MISSING_CORE", "MISSING_TOOL", "WORKSPACE", "SDCARD", "DPKG_AUDIT", "DONE")) assertTrue(k in s, k)
        assertTrue("APK_OK" in HealthCheck.script(Distro.Alpine))
    }
}
