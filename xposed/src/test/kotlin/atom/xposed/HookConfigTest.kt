package atom.xposed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HookConfigTest {
    @Test fun roundTrip() {
        val c = HookConfig(PowerKeyTarget.Gemini, autoDefaultAssistant = true, gestureBarCircleToSearch = true, twoFingerCircleToSearch = false, enforceAccessibility = true)
        assertEquals(c, HookConfig.fromMap(c.toMap()))
        assertEquals("gemini", c.toMap()[HookConfig.KEY_POWER_TARGET])
        assertEquals(HookConfig.VERSION, c.toMap()[HookConfig.KEY_VERSION])
    }

    @Test fun missingOrBrokenMeansEverythingOff() {
        assertEquals(HookConfig(), HookConfig.fromMap(null))
        assertEquals(HookConfig(), HookConfig.fromMap(emptyMap<String, Any>()))
        val broken = mapOf(HookConfig.KEY_POWER_TARGET to "nonsense", HookConfig.KEY_CTS_BAR to 3, HookConfig.KEY_ENFORCE_A11Y to "yes")
        assertEquals(HookConfig(), HookConfig.fromMap(broken))
    }

    @Test fun stringBooleansAreAccepted() =
        assertTrue(HookConfig.fromMap(mapOf(HookConfig.KEY_CTS_TWO_FINGER to "true")).twoFingerCircleToSearch)

    @Test fun onlyPrimitivesAreStored() =
        assertTrue(HookConfig().toMap().values.all { it is String || it is Boolean || it is Int })

    @Test fun assistantFollowsPowerTarget() {
        assertEquals("io.atom", HookConfig(PowerKeyTarget.Atom).assistantPackage("io.atom"))
        assertEquals("io.atom", HookConfig().assistantPackage("io.atom"))
        assertEquals(HookConfig.GOOGLE_APP, HookConfig(PowerKeyTarget.Gemini).assistantPackage("io.atom"))
    }

    @Test fun anyCircleToSearch() {
        assertFalse(HookConfig().anyCircleToSearch)
        assertTrue(HookConfig(twoFingerCircleToSearch = true).anyCircleToSearch)
    }
}
