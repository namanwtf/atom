package atom.device.godmode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChargeNodesTest {
    @Test
    fun nothingKnownMeansNotSupported() {
        val s = ChargeNodes.detect(setOf("/sys/class/power_supply/battery/capacity"))
        assertFalse(s.supported)
        assertNull(s.threshold)
        assertNull(s.pause)
    }

    @Test
    fun pixelThresholdWithStartLevel() {
        val s = ChargeNodes.detect(
            setOf("/sys/devices/platform/google,charger/charge_stop_level", "/sys/devices/platform/google,charger/charge_start_level"),
        )
        assertEquals("Pixel", s.threshold?.vendor)
        assertEquals("/sys/devices/platform/google,charger/charge_start_level", s.threshold?.startPath)
    }

    @Test
    fun startPathDroppedWhenMissing() {
        val s = ChargeNodes.detect(setOf("/sys/class/power_supply/battery/charge_control_end_threshold"))
        assertEquals("kernel standard", s.threshold?.vendor)
        assertNull(s.threshold?.startPath)
    }

    @Test
    fun pauseOnlyPhone() {
        val s = ChargeNodes.detect(setOf("/sys/class/power_supply/battery/input_suspend"))
        assertTrue(s.supported)
        assertNull(s.threshold)
        assertEquals("1", s.pause?.pauseValue)
        assertEquals("0", s.pause?.resumeValue)
    }

    @Test
    fun priorityOrderPicksFirstKnown() {
        val s = ChargeNodes.detect(
            setOf("/sys/class/power_supply/battery/charging_enabled", "/sys/class/power_supply/battery/input_suspend"),
        )
        assertEquals("/sys/class/power_supply/battery/input_suspend", s.pause?.path)
    }

    @Test
    fun limitWritesStartBelowStopFirst() {
        val node = ChargeNodes.known.first { it.vendor == "Pixel" }
        val cmds = ChargeNodes.limitCommands(node, 80)
        assertEquals(2, cmds.size)
        assertTrue(cmds[0].startsWith("echo 75 > ") && cmds[0].contains("charge_start_level"))
        assertTrue(cmds[1].startsWith("echo 80 > ") && cmds[1].contains("charge_stop_level"))
    }

    @Test
    fun offRaisesStopThenClearsStart() {
        val node = ChargeNodes.known.first { it.vendor == "Pixel" }
        val cmds = ChargeNodes.limitCommands(node, 100)
        assertTrue(cmds[0].startsWith("echo 100 > "))
        assertTrue(cmds[1].startsWith("echo 0 > ") && cmds[1].contains("charge_start_level"))
    }

    @Test
    fun probeScriptChecksEveryPath() {
        val script = ChargeNodes.probeScript()
        ChargeNodes.known.forEach { assertTrue(script.contains(it.path)) }
    }
}
