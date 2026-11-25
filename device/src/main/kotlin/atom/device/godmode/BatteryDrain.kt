package atom.device.godmode

/** One consumer from batterystats. [uid] is null for hardware rows such as the screen or cell radio. */
data class DrainItem(val uid: Int?, val label: String, val mah: Double)

/**
 * Parses `dumpsys batterystats` power estimates (since the last full charge). Prefers the compact checkin "pwi"
 * rows; falls back to the human "Estimated power use" section.
 */
object BatteryDrain {
    private val hardware = mapOf(
        "scrn" to "Screen", "cell" to "Mobile radio", "wifi" to "Wi-Fi", "blue" to "Bluetooth", "idle" to "Phone idle",
        "phone" to "Calls", "flashlight" to "Flashlight", "camera" to "Camera", "memory" to "Memory",
        "over" to "Over-counted", "unacc" to "Unaccounted",
    )

    const val CHECKIN_COMMAND = "dumpsys batterystats --checkin | grep -E '^[0-9]+,[0-9]+,l,pwi,'"
    const val TEXT_COMMAND =
        "dumpsys batterystats | grep -E '^ *(UID [ua0-9si]+:|Capacity:|Screen:|Cell standby:|Idle:|Wifi:|Bluetooth:)'"

    fun parseCheckin(out: String): List<DrainItem> {
        val byUid = LinkedHashMap<Int, Double>()
        val other = LinkedHashMap<String, Double>()
        for (line in out.lineSequence()) {
            val p = line.trim().split(',')
            if (p.size < 6 || p[2] != "l" || p[3] != "pwi") continue
            val mah = p[5].toDoubleOrNull() ?: continue
            if (p[4] == "uid") {
                val uid = p[1].toIntOrNull() ?: continue
                byUid[uid] = (byUid[uid] ?: 0.0) + mah
            } else {
                val name = hardware[p[4]] ?: p[4]
                other[name] = (other[name] ?: 0.0) + mah
            }
        }
        return (byUid.map { DrainItem(it.key, "uid ${it.key}", it.value) } + other.map { DrainItem(null, it.key, it.value) })
            .filter { it.mah > 0.0 }
            .sortedByDescending { it.mah }
    }

    private val textUid = Regex("UID (u(\\d+)([asi])(\\d+)|\\d+):\\s*([\\d.]+)")
    private val textHw = Regex("^\\s*(Screen|Cell standby|Idle|Wifi|Bluetooth):\\s*([\\d.]+)")

    fun parseText(out: String): List<DrainItem> {
        val items = mutableListOf<DrainItem>()
        for (line in out.lineSequence()) {
            textUid.find(line)?.let { m ->
                val uid = if (m.groupValues[2].isEmpty()) {
                    m.groupValues[1].toIntOrNull()
                } else {
                    val user = m.groupValues[2].toInt()
                    val id = m.groupValues[4].toInt()
                    when (m.groupValues[3]) {
                        "a" -> user * 100_000 + 10_000 + id
                        "i" -> user * 100_000 + 99_000 + id
                        else -> user * 100_000 + id
                    }
                }
                val mah = m.groupValues[5].toDoubleOrNull()
                if (uid != null && mah != null && mah > 0) items += DrainItem(uid, "uid $uid", mah)
                return@let
            } ?: textHw.find(line)?.let { m ->
                m.groupValues[2].toDoubleOrNull()?.takeIf { it > 0 }?.let { items += DrainItem(null, m.groupValues[1], it) }
            }
        }
        return items.groupBy { it.uid to (if (it.uid == null) it.label else "") }
            .map { (_, v) -> v.first().copy(mah = v.sumOf { it.mah }) }
            .sortedByDescending { it.mah }
    }
}
