package atom.device.power

import android.os.PowerManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PowerPolicyTest {
    private fun s(pct: Int = 80, charging: Boolean = false, thermal: Thermal = Thermal.Normal) =
        PowerState(pct, charging, powerSave = false, thermal = thermal)

    @Test
    fun allowedWhenCoolAndCharged() = assertTrue(PowerPolicy.localInferenceAllowed(s()))

    @Test
    fun blockedWhenHotOrCritical() {
        assertFalse(PowerPolicy.localInferenceAllowed(s(thermal = Thermal.Hot)))
        assertFalse(PowerPolicy.localInferenceAllowed(s(charging = true, thermal = Thermal.Critical)))
        assertTrue(PowerPolicy.localInferenceAllowed(s(thermal = Thermal.Warm)))
    }

    @Test
    fun lowBatteryBlocksUnlessCharging() {
        assertFalse(PowerPolicy.localInferenceAllowed(s(pct = 14)))
        assertTrue(PowerPolicy.localInferenceAllowed(s(pct = 14, charging = true)))
        assertTrue(PowerPolicy.localInferenceAllowed(s(pct = 15)))
        assertTrue(PowerPolicy.localInferenceAllowed(s(pct = -1))) // unknown battery: do not block
    }

    @Test
    fun thermalMapping() {
        assertEquals(Thermal.Normal, PowerPolicy.thermalOf(PowerManager.THERMAL_STATUS_NONE))
        assertEquals(Thermal.Warm, PowerPolicy.thermalOf(PowerManager.THERMAL_STATUS_LIGHT))
        assertEquals(Thermal.Warm, PowerPolicy.thermalOf(PowerManager.THERMAL_STATUS_MODERATE))
        assertEquals(Thermal.Hot, PowerPolicy.thermalOf(PowerManager.THERMAL_STATUS_SEVERE))
        assertEquals(Thermal.Critical, PowerPolicy.thermalOf(PowerManager.THERMAL_STATUS_CRITICAL))
        assertEquals(Thermal.Critical, PowerPolicy.thermalOf(PowerManager.THERMAL_STATUS_SHUTDOWN))
        assertEquals(Thermal.Critical, PowerPolicy.thermalOf(99))
    }
}
