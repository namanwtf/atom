package atom.context

import atom.context.root.SavedWifi
import atom.context.root.WifiConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class WifiConfigTest {
    private val xml = """
        <WifiConfigStoreData>
        <NetworkList>
        <Network>
        <WifiConfiguration>
        <string name="ConfigKey">&quot;Home &amp; Co&quot;WPA_PSK</string>
        <string name="SSID">&quot;Home &amp; Co&quot;</string>
        <string name="PreSharedKey">&quot;s3cret&quot;pass&quot;</string>
        </WifiConfiguration>
        </Network>
        <Network>
        <WifiConfiguration>
        <string name="ConfigKey">&quot;Cafe&quot;NONE</string>
        <string name="SSID">&quot;Cafe&quot;</string>
        <null name="PreSharedKey" />
        </WifiConfiguration>
        </Network>
        <Network>
        <WifiConfiguration>
        <string name="ConfigKey">&quot;Office&quot;SAE</string>
        <string name="SSID">&quot;Office&quot;</string>
        <string name="PreSharedKey">&quot;w3pa3&quot;</string>
        </WifiConfiguration>
        </Network>
        </NetworkList>
        </WifiConfigStoreData>
    """.trimIndent()

    @Test
    fun parsesNetworks() {
        assertEquals(
            listOf(
                SavedWifi("Home & Co", "s3cret\"pass", "WPA/WPA2"),
                SavedWifi("Cafe", null, "Open"),
                SavedWifi("Office", "w3pa3", "WPA3"),
            ),
            WifiConfig.parse(xml),
        )
    }

    @Test
    fun garbageGivesNothing() = assertEquals(emptyList(), WifiConfig.parse("not xml"))
}
