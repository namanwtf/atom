package atom.device.caps

import atom.device.root.RootStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PermissionRowsTest {
    @Test
    fun twelveClassicRowsInOrder() {
        assertEquals(12, PermissionRows.CLASSIC.size)
        assertEquals(
            listOf(
                "Background running permission", "Floating window permissions", "Application list reading", "Local network access",
                "Calendar access", "Do Not Disturb access", "Location permissions", "Notification access", "Usage access",
                "Accessibility permissions", "Background task notifications", "Root and system enhancements",
            ),
            PermissionRows.CLASSIC.map { it.second },
        )
    }

    @Test
    fun classicRowsMissingOnThisBuildAreSkippedButRootStays() {
        val rows = PermissionRows.classic(setOf("overlay", "accessibility"))
        assertEquals(listOf("overlay", "accessibility", PermissionRows.ROOT), rows.map { it.first })
    }

    @Test
    fun everythingElseGoesBelow() {
        val others = PermissionRows.others(listOf("accessibility", "microphone", "overlay", "contacts", "all_files"))
        assertEquals(listOf("microphone", "contacts", "all_files"), others)
        assertTrue(PermissionRows.isClassic("battery"))
        assertFalse(PermissionRows.isClassic("exact_alarms"))
    }

    @Test
    fun statusTones() {
        assertEquals(StatusTone.Good, PermissionRows.tone(CapStatus.Available))
        assertEquals(StatusTone.Warning, PermissionRows.tone(CapStatus.Issue))
        assertEquals(StatusTone.Warning, PermissionRows.tone(CapStatus.Checking))
        assertEquals(StatusTone.Bad, PermissionRows.tone(CapStatus.Missing))
        assertEquals(StatusTone.Bad, PermissionRows.tone(CapStatus.NotEnabled))
        assertEquals(StatusTone.Muted, PermissionRows.tone(CapStatus.Unsupported))
        assertEquals(StatusTone.Muted, PermissionRows.tone(CapStatus.NotEnabled, optional = true))
        assertEquals(StatusTone.Good, PermissionRows.tone(CapStatus.Available, optional = true))
        assertEquals(listOf("Available", "Missing", "Issue", "Not enabled"), listOf(CapStatus.Available, CapStatus.Missing, CapStatus.Issue, CapStatus.NotEnabled).map { it.label })
    }

    @Test
    fun rootRowStatus() {
        assertEquals(CapStatus.Available, PermissionRows.rootStatus(RootStatus.Granted))
        assertEquals(CapStatus.Checking, PermissionRows.rootStatus(RootStatus.Checking))
        assertEquals(CapStatus.NotEnabled, PermissionRows.rootStatus(RootStatus.NotDetected))
        assertEquals(CapStatus.NotEnabled, PermissionRows.rootStatus(RootStatus.Unknown))
    }
}
