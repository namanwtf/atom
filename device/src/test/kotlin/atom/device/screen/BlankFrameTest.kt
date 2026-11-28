package atom.device.screen

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlankFrameTest {
    @Test
    fun aProtectedFrameIsBlackEverywhere() {
        assertTrue(BlankFrame.allDark(IntArray(64) { 0xFF000000.toInt() }))
        assertTrue(BlankFrame.allDark(IntArray(64) { 0xFF050608.toInt() }))
    }

    @Test
    fun anyContentIsNotBlank() {
        val samples = IntArray(64) { 0xFF000000.toInt() }
        samples[40] = 0xFFFFFFFF.toInt()
        assertFalse(BlankFrame.allDark(samples))
        assertFalse(BlankFrame.allDark(IntArray(64) { 0xFF1E1E1E.toInt() }))
    }
}
