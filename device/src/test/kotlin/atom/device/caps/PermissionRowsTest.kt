package atom.device.caps

import atom.device.root.RootStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PermissionRowsTest {
    @Test
    fun onlyAccessibilityAndOverlayAreCore() {
        assertEquals(listOf("accessibility", "overlay"), PermissionRows.CORE.map { it.first })
        assertTrue(PermissionRows.isCore("accessibility"))
        assertTrue(PermissionRows.isCore("overlay"))
        assertFalse(PermissionRows.isCore("notifications_post"))
        assertFalse(PermissionRows.isCore("battery"))
        assertFalse(PermissionRows.isCore("microphone"))
    }

    @Test
    fun optionalRowsMissingOnThisBuildAreSkippedButRootStays() {
        val rows = PermissionRows.optional(setOf("overlay", "accessibility", "location"))
        assertEquals(listOf("location", PermissionRows.ROOT), rows.map { it.first })
        assertEquals(listOf("overlay"), PermissionRows.core(setOf("overlay")).map { it.first })
    }

    @Test
    fun unlistedRowsFollowTheOptionalOnes() {
        val others = PermissionRows.others(listOf("accessibility", "microphone", "overlay", "contacts", "all_files"))
        assertEquals(listOf("contacts", "all_files"), others)
    }

    @Test
    fun optionalRowsThatAreNotSetUpReadWhenNeeded() {
        assertEquals("When needed", PermissionRows.label(CapStatus.Missing, optional = true))
        assertEquals("When needed", PermissionRows.label(CapStatus.NotEnabled, optional = true))
        assertEquals("Missing", PermissionRows.label(CapStatus.Missing))
        assertEquals("Available", PermissionRows.label(CapStatus.Available, optional = true))
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
