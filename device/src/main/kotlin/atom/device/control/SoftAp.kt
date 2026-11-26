package atom.device.control

/**
 * The hotspot the user saved in Settings, read from the Wi-Fi module's soft-AP store (root only), and the
 * `cmd wifi start-softap` line that brings exactly that hotspot up. Pure, so it is unit-tested.
 */
internal data class SoftApConfig(val ssid: String, val security: Int, val passphrase: String?, val band: Int?) {
    val isOpen: Boolean get() = security == SECURITY_OPEN || security == SECURITY_OWE

    companion object {
        const val SECURITY_OPEN = 0
        const val SECURITY_WPA2 = 1
        const val SECURITY_WPA3_TRANSITION = 2
        const val SECURITY_WPA3 = 3
        const val SECURITY_OWE_TRANSITION = 4
        const val SECURITY_OWE = 5
    }
}

internal object SoftAp {
    /** Where Android 11+ keeps the saved hotspot (the APEX path first, the pre-APEX one as a fallback). */
    val STORE_PATHS = listOf(
        "/data/misc/apexdata/com.android.wifi/WifiConfigStoreSoftAp.xml",
        "/data/misc/wifi/WifiConfigStoreSoftAp.xml",
    )

    const val STOP = "cmd wifi stop-softap"

    private val wifiInterface = Regex("""^(wlan|swlan|ap|softap|wigig)\d*$""")

    /** True when one of the tethered interfaces ([active], the tether-state broadcast's list) is a Wi-Fi one. */
    fun isWifiTethered(active: List<String>): Boolean = active.any { wifiInterface.matches(it.trim()) }

    private val stringEntry = Regex("""<string\s+name="([^"]+)"\s*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
    private val intEntry = Regex("""<int\s+name="([^"]+)"\s+value="(-?\d+)"\s*/>""")

    /** Parses the soft-AP store XML; null when it has no usable name. */
    fun parse(xml: String): SoftApConfig? {
        val section = xml.substringAfter("<SoftAp>", xml).substringBefore("</SoftAp>")
        val strings = stringEntry.findAll(section).associate { it.groupValues[1] to unescape(it.groupValues[2]) }
        val ints = intEntry.findAll(section).associate { it.groupValues[1] to it.groupValues[2].toInt() }
        val ssid = strings["WifiSsid"]?.let(::decodeSsid) ?: strings["SSID"] ?: return null
        if (ssid.isEmpty()) return null
        val security = ints["SecurityType"] ?: SoftApConfig.SECURITY_WPA2
        val passphrase = strings["Passphrase"]?.takeIf { it.isNotEmpty() }
        return SoftApConfig(ssid, security, passphrase, ints["ApBand"])
    }

    /**
     * The start command for [config], or null when it cannot be reproduced exactly (a secured hotspot whose
     * password is stored encrypted, or a security type the shell command does not take).
     */
    fun startCommand(config: SoftApConfig, quote: (String) -> String): String? {
        val security = when (config.security) {
            SoftApConfig.SECURITY_OPEN -> "open"
            SoftApConfig.SECURITY_WPA2 -> "wpa2"
            SoftApConfig.SECURITY_WPA3_TRANSITION -> "wpa3_transition"
            SoftApConfig.SECURITY_WPA3 -> "wpa3"
            SoftApConfig.SECURITY_OWE_TRANSITION -> "owe_transition"
            SoftApConfig.SECURITY_OWE -> "owe"
            else -> return null
        }
        val parts = mutableListOf("cmd", "wifi", "start-softap", quote(config.ssid), security)
        if (!config.isOpen) parts += quote(config.passphrase ?: return null)
        bandArg(config.band)?.let { parts += listOf("-b", it) }
        return parts.joinToString(" ")
    }

    /** The saved band as start-softap's -b value; several bands (or none saved) let the system choose. */
    fun bandArg(band: Int?): String? = when (band) {
        1 -> "2"
        2 -> "5"
        4 -> "6"
        else -> null
    }

    /** Android 13+ stores the name quoted ("\"Name\"") or as hex bytes for non-UTF-8 names. */
    fun decodeSsid(raw: String): String? {
        val s = raw.trim()
        if (s.length >= 2 && s.startsWith('"') && s.endsWith('"')) return s.substring(1, s.length - 1)
        if (s.isNotEmpty() && s.length % 2 == 0 && s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            val bytes = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            return String(bytes, Charsets.UTF_8)
        }
        return s.ifEmpty { null }
    }

    private fun unescape(s: String): String = s
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
}
