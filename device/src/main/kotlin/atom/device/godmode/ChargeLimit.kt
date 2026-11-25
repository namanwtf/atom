package atom.device.godmode

import android.content.Context
import atom.device.ShellExecutor
import atom.device.control.shQuote

/**
 * A kernel node that controls charging. [Threshold] nodes take a percentage and the kernel stops charging there
 * (zero runtime cost). [Pause] nodes only switch charging off or on.
 */
data class ChargeNode(
    val path: String,
    val kind: Kind,
    val vendor: String,
    val pauseValue: String = "",
    val resumeValue: String = "",
    val startPath: String? = null,
) {
    enum class Kind { Threshold, Pause }
}

data class ChargeSupport(val threshold: ChargeNode?, val pause: ChargeNode?) {
    val supported: Boolean get() = threshold != null || pause != null
}

object ChargeNodes {
    val known: List<ChargeNode> = listOf(
        ChargeNode(
            "/sys/devices/platform/google,charger/charge_stop_level", ChargeNode.Kind.Threshold, "Pixel",
            startPath = "/sys/devices/platform/google,charger/charge_start_level",
        ),
        ChargeNode(
            "/sys/devices/platform/soc/soc:google,charger/charge_stop_level", ChargeNode.Kind.Threshold, "Pixel",
            startPath = "/sys/devices/platform/soc/soc:google,charger/charge_start_level",
        ),
        ChargeNode(
            "/sys/class/power_supply/battery/charge_control_end_threshold", ChargeNode.Kind.Threshold, "kernel standard",
            startPath = "/sys/class/power_supply/battery/charge_control_start_threshold",
        ),
        ChargeNode("/sys/class/power_supply/battery/charge_stop_level", ChargeNode.Kind.Threshold, "generic"),
        ChargeNode("/sys/class/power_supply/battery/input_suspend", ChargeNode.Kind.Pause, "Qualcomm", "1", "0"),
        ChargeNode("/sys/class/qcom-battery/input_suspend", ChargeNode.Kind.Pause, "Qualcomm", "1", "0"),
        ChargeNode("/sys/class/power_supply/battery/charging_enabled", ChargeNode.Kind.Pause, "Qualcomm", "0", "1"),
        ChargeNode("/sys/class/power_supply/battery/battery_charging_enabled", ChargeNode.Kind.Pause, "Qualcomm", "0", "1"),
        ChargeNode("/sys/class/oplus_chg/battery/mmi_charging_enable", ChargeNode.Kind.Pause, "OPPO/OnePlus", "0", "1"),
        ChargeNode("/sys/class/power_supply/battery/mmi_charging_enable", ChargeNode.Kind.Pause, "OPPO/OnePlus", "0", "1"),
        ChargeNode("/sys/class/power_supply/battery/batt_slate_mode", ChargeNode.Kind.Pause, "Samsung", "1", "0"),
        ChargeNode("/proc/mtk_battery_cmd/current_cmd", ChargeNode.Kind.Pause, "MediaTek", "0 1", "0 0"),
    )

    /** Picks the best node of each kind from the paths that exist, in [known]'s priority order. */
    fun detect(existing: Set<String>): ChargeSupport = ChargeSupport(
        threshold = known.firstOrNull { it.kind == ChargeNode.Kind.Threshold && it.path in existing }
            ?.let { n -> if (n.startPath != null && n.startPath !in existing) n.copy(startPath = null) else n },
        pause = known.firstOrNull { it.kind == ChargeNode.Kind.Pause && it.path in existing },
    )

    fun probeScript(): String =
        (known.flatMap { listOfNotNull(it.path, it.startPath) }).distinct()
            .joinToString("\n") { "[ -e ${shQuote(it)} ] && echo ${shQuote(it)}" } + "\necho ATOM_PROBE_DONE"

    /** Commands that set a stop level (and a start level 5 points lower, when supported). */
    fun limitCommands(node: ChargeNode, percent: Int): List<String> {
        val p = percent.coerceIn(20, 100)
        val start = if (p >= 100) 0 else (p - 5).coerceAtLeast(15)
        return buildList {
            // The start level must stay below the stop level, so order the writes accordingly.
            if (node.startPath != null && p < 100) add("echo $start > ${shQuote(node.startPath)}")
            add("echo $p > ${shQuote(node.path)}")
            if (node.startPath != null && p >= 100) add("echo 0 > ${shQuote(node.startPath)}")
        }
    }
}

/** Remembers the user's limit so it can be re-applied after a reboot (sysfs resets). */
class ChargeLimitStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("atom4_charge_limit", Context.MODE_PRIVATE)

    var limit: Int?
        get() = prefs.getInt("limit", -1).takeIf { it in 20..99 }
        set(v) {
            prefs.edit().putInt("limit", v ?: -1).commit()
            BootReapplyReceiver.sync(app)
        }

    suspend fun detect(sh: ShellExecutor): ChargeSupport {
        val out = sh.run(ChargeNodes.probeScript(), 10_000).stdout
        return ChargeNodes.detect(out.lines().map { it.trim() }.filter { it.startsWith("/") }.toSet())
    }

    suspend fun readLevel(sh: ShellExecutor, node: ChargeNode): Int? =
        sh.run("cat ${shQuote(node.path)}", 5_000).stdout.trim().toIntOrNull()
}
