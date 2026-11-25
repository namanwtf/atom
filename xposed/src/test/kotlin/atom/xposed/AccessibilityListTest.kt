package atom.xposed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccessibilityListTest {
    private val me = "io.atom/atom.device.AtomAccessibilityService"

    @Test fun matchesShortAndLongForms() {
        assertTrue(AccessibilityList.contains("com.x/.S:io.atom/atom.device.AtomAccessibilityService", me))
        assertTrue(AccessibilityList.contains("atom.device/.AtomAccessibilityService", "atom.device/atom.device.AtomAccessibilityService"))
        assertFalse(AccessibilityList.contains(null, me))
        assertFalse(AccessibilityList.contains("", me))
        assertFalse(AccessibilityList.contains("io.atom/atom.device.Other", me))
    }

    @Test fun appendsOnce() {
        assertEquals(me, AccessibilityList.withComponent(null, me))
        assertEquals("com.x/.S:$me", AccessibilityList.withComponent("com.x/.S", me))
        assertEquals("com.x/.S:$me", AccessibilityList.withComponent("com.x/.S:$me", me))
        assertEquals("com.x/.S:$me", AccessibilityList.withComponent("com.x/.S::", me))
    }
}
