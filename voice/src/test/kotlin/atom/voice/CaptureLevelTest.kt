package atom.voice

import atom.voice.service.CaptureLevel
import kotlin.test.Test
import kotlin.test.assertEquals

class CaptureLevelTest {
    @Test
    fun rmsMapsToZeroToOne() {
        assertEquals(0f, CaptureLevel.fromRms(-2f))
        assertEquals(0f, CaptureLevel.fromRms(-10f))
        assertEquals(0.5f, CaptureLevel.fromRms(4f))
        assertEquals(1f, CaptureLevel.fromRms(10f))
        assertEquals(1f, CaptureLevel.fromRms(30f))
        assertEquals(0f, CaptureLevel.fromRms(Float.NaN))
    }
}
