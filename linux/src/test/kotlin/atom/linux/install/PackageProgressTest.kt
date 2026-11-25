package atom.linux.install

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PackageProgressTest {
    @Test
    fun apkCountsPackages() {
        val p = PackageProgress()
        assertNull(p.line("fetch https://dl-cdn.alpinelinux.org/alpine/v3.24/main/aarch64/APKINDEX.tar.gz"))
        assertEquals(0.25f, p.line("(1/4) Installing ca-certificates (20250911-r0)"))
        assertEquals(1f, p.line("(4/4) Installing git (2.49.1-r0)"))
        // never goes backwards (a second apk run in the same script restarts at 1)
        assertEquals(1f, p.line("(1/2) Installing jq (1.8-r0)"))
    }

    @Test
    fun aptUsesTheSummaryAndUnpackSetupLines() {
        val p = PackageProgress()
        assertNull(p.line("Setting up nothing-yet (1.0)")) // no total known yet
        assertNull(p.line("1 upgraded, 3 newly installed, 0 to remove and 0 not upgraded."))
        assertEquals(1f / 8, p.line("Unpacking git (1:2.47.3-0+deb13u1) ..."))
        repeat(6) { p.line("Setting up x ...") }
        assertEquals(1f, p.line("Setting up last ..."))
    }

    @Test
    fun stepsRoundTripThroughTheirKeys() {
        val steps = listOf(InstallStep.Base, InstallStep.Tools, InstallStep.Upgrade, InstallStep.Repair) +
            Profile.entries.map { InstallStep.ProfileStep(it) }
        for (s in steps) assertEquals(s, InstallStep.parse(s.key))
        assertNull(InstallStep.parse("profile:Nope"))
    }

    @Test
    fun scriptsVerifyWhatTheyDownload() {
        for (d in atom.linux.Distro.entries) {
            for (p in Profile.entries) {
                val s = InstallScripts.profile(p, d)
                if ("curl -fL" in s) {
                    kotlin.test.assertTrue("sha256sum -c" in s, "$p on $d downloads without a checksum check")
                }
            }
        }
        kotlin.test.assertTrue("ripgrep" in InstallScripts.tools(atom.linux.Distro.Alpine))
        kotlin.test.assertTrue("fd-find" in InstallScripts.tools(atom.linux.Distro.Debian))
        kotlin.test.assertTrue("dpkg --configure -a" in InstallScripts.repair(atom.linux.Distro.Debian))
    }
}
