package atom.overlay

import atom.overlay.hey.HeyContext
import atom.overlay.hey.HeyScreenResult
import atom.overlay.hey.HeyScreenSkip
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeyContextTest {
    private val own = "io.github.namanwtf.atom"
    private val jpeg = byteArrayOf(1, 2, 3)

    private fun capture(
        text: String = "n1 Text \"Wi-Fi\"\nn2 Switch \"Bluetooth\" click",
        secure: Boolean = false,
        password: Boolean = false,
        pkg: String? = "com.android.settings",
        shot: ByteArray? = jpeg,
    ) = ScreenCapture("Settings", pkg, text, password, secure, shot)

    private fun build(c: ScreenCapture?, enabled: Boolean = true, sees: Boolean = false) =
        HeyContext.build(c, enabled, sees, own, capturedAt = 0L, note = { app -> "used $app" }, zone = ZoneOffset.UTC)

    @Test
    fun textOnlyModelGetsTheOutlineAndNoPicture() {
        val used = assertIs<HeyScreenResult.Used>(build(capture(), sees = false)).screen
        assertNull(used.imageJpeg)
        assertTrue(used.text.startsWith("[What was on screen when the user said \"Hey ATOM\" (captured at 00:00)"))
        assertTrue("App: Settings (com.android.settings)" in used.text)
        assertTrue("Bluetooth" in used.text)
        assertEquals("Settings", used.app)
        assertEquals("used Settings", used.note)
    }

    @Test
    fun visionModelAlsoGetsTheScreenshot() {
        val used = assertIs<HeyScreenResult.Used>(build(capture(), sees = true)).screen
        assertNotNull(used.imageJpeg)
    }

    @Test
    fun protectedWindowsAreNeverUsed() {
        assertEquals(HeyScreenSkip.Secure, assertIs<HeyScreenResult.Skipped>(build(capture(secure = true), sees = true)).reason)
    }

    @Test
    fun aVisiblePasswordFieldMeansNothingIsUsed() {
        assertEquals(HeyScreenSkip.Password, assertIs<HeyScreenResult.Skipped>(build(capture(password = true), sees = true)).reason)
    }

    @Test
    fun offOrNothingOrAtomItself() {
        assertEquals(HeyScreenSkip.Off, assertIs<HeyScreenResult.Skipped>(build(capture(), enabled = false)).reason)
        assertEquals(HeyScreenSkip.NothingCaptured, assertIs<HeyScreenResult.Skipped>(build(null)).reason)
        assertEquals(HeyScreenSkip.OwnApp, assertIs<HeyScreenResult.Skipped>(build(capture(pkg = own))).reason)
        assertEquals(HeyScreenSkip.Empty, assertIs<HeyScreenResult.Skipped>(build(capture(text = "  ", shot = null), sees = true)).reason)
        // A drawn-only app (no text) still helps a model that sees the picture.
        assertIs<HeyScreenResult.Used>(build(capture(text = ""), sees = true))
    }

    @Test
    fun longScreensAreCutAtALineToTheBudget() {
        val long = (1..2_000).joinToString("\n") { "n$it Text \"Row $it of a long list\"" }
        val used = assertIs<HeyScreenResult.Used>(build(capture(text = long))).screen
        val body = used.text.substringAfter("Screen:\n")
        assertTrue(body.length <= HeyContext.MAX_TEXT_CHARS, "body ${body.length}")
        assertTrue(body.endsWith("read_screen shows all of it)"))
        assertTrue(body.lines().dropLast(1).all { it.endsWith("of a long list\"") })
        assertEquals("short", HeyContext.truncate("short", 10))
    }
}
