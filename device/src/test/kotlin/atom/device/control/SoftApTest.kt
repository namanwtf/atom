package atom.device.control

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SoftApTest {
    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private val android14 = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<WifiConfigStoreData>
<int name="Version" value="3" />
<SoftAp>
<string name="WifiSsid">&quot;Pixel &amp; Co&quot;</string>
<boolean name="HiddenSSID" value="false" />
<int name="SecurityType" value="1" />
<string name="Passphrase">s3cret pass</string>
<int name="ApBand" value="2" />
</SoftAp>
</WifiConfigStoreData>"""

    @Test
    fun parsesTheSavedHotspot() {
        val c = SoftAp.parse(android14)!!
        assertEquals("Pixel & Co", c.ssid)
        assertEquals(SoftApConfig.SECURITY_WPA2, c.security)
        assertEquals("s3cret pass", c.passphrase)
        assertEquals(2, c.band)
        assertEquals("cmd wifi start-softap 'Pixel & Co' wpa2 's3cret pass' -b 5", SoftAp.startCommand(c, ::q))
    }

    @Test
    fun olderStoreAndHexNames() {
        val older = "<SoftAp><string name=\"SSID\">Home</string><int name=\"SecurityType\" value=\"0\" /></SoftAp>"
        val c = SoftAp.parse(older)!!
        assertEquals("Home", c.ssid)
        assertEquals("cmd wifi start-softap 'Home' open", SoftAp.startCommand(c, ::q))
        assertEquals("Hi", SoftAp.decodeSsid("4869"))
        assertEquals("Hi", SoftAp.decodeSsid("\"Hi\""))
    }

    @Test
    fun refusesWhatItCannotReproduce() {
        // Secured, but the password is stored encrypted (no Passphrase entry): never start with a made-up one.
        val encrypted = "<SoftAp><string name=\"WifiSsid\">&quot;X&quot;</string><int name=\"SecurityType\" value=\"3\" /></SoftAp>"
        assertNull(SoftAp.startCommand(SoftAp.parse(encrypted)!!, ::q))
        assertNull(SoftAp.startCommand(SoftApConfig("X", 9, "p", null), ::q))
        assertNull(SoftAp.parse("<SoftAp></SoftAp>"))
    }

    @Test
    fun bands() {
        assertEquals("2", SoftAp.bandArg(1))
        assertEquals("6", SoftAp.bandArg(4))
        assertNull(SoftAp.bandArg(3))
        assertNull(SoftAp.bandArg(null))
    }
}
