package atom.context.root

data class SavedWifi(val ssid: String, val password: String?, val security: String)

/** Reads saved networks out of Android's WifiConfigStore.xml (the format since Android 8). */
object WifiConfig {
    const val PATHS = "/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml /data/misc/wifi/WifiConfigStore.xml"

    private val block = Regex("<WifiConfiguration>(.*?)</WifiConfiguration>", RegexOption.DOT_MATCHES_ALL)

    private fun field(xml: String, name: String): String? =
        Regex("<string name=\"$name\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.let(::unescape)

    fun parse(xml: String): List<SavedWifi> = block.findAll(xml).mapNotNull { m ->
        val b = m.groupValues[1]
        val ssid = field(b, "SSID")?.trim()?.removeSurrounding("\"") ?: return@mapNotNull null
        val psk = field(b, "PreSharedKey")?.trim()
        val password = psk?.let { if (it.startsWith("\"")) it.removeSurrounding("\"") else it }
        val wep = Regex("<string-array name=\"WEPKeys\"[^>]*>(.*?)</string-array>", RegexOption.DOT_MATCHES_ALL).find(b)?.groupValues?.get(1)
            ?.let { Regex("<item value=\"(.*?)\"").find(it)?.groupValues?.get(1) }?.let(::unescape)?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() }
        val key = field(b, "ConfigKey").orEmpty()
        val security = when {
            key.endsWith("SAE") -> "WPA3"
            key.endsWith("WPA_PSK") -> "WPA/WPA2"
            key.endsWith("WEP") -> "WEP"
            key.endsWith("WPA_EAP") || key.contains("EAP") -> "Enterprise"
            key.endsWith("OWE") -> "Enhanced open"
            key.endsWith("NONE") -> "Open"
            else -> if (password != null) "WPA/WPA2" else "Open"
        }
        SavedWifi(ssid, password ?: wep, security)
    }.distinctBy { it.ssid to it.security }.toList()

    private fun unescape(s: String) = s.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
}
