package atom.device.godmode

import android.content.Context
import android.content.pm.PackageManager
import atom.device.ShellExecutor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Which traffic a firewall rule blocks. [Background] uses Android's own per-app background-data restriction. */
enum class NetScope(val label: String) {
    All("all networks"),
    Mobile("mobile data"),
    Wifi("Wi-Fi"),
    Background("background data on metered networks"),
}

@Serializable
data class FirewallRule(val packageName: String, val label: String, val scope: NetScope)

/**
 * Builds the commands for Atom's per-app firewall. The kernel enforces owner-UID rules in Atom's own chain, so once
 * applied it costs nothing at runtime; the chain is rebuilt from the saved rules on every change and after boot.
 */
object FirewallRules {
    const val CHAIN = "atom_fw"

    val mobileInterfaces = listOf(
        "rmnet+", "rmnet_data+", "r_rmnet_data+", "rev_rmnet+", "ccmni+", "pdp+", "wwan+", "seth_lte+", "v4-rmnet+",
        "v4-rmnet_data+", "v4-ccmni+", "clat4+",
    )
    val wifiInterfaces = listOf("wlan+", "v4-wlan+")

    private val tables = listOf("iptables", "ip6tables")

    /** Full rebuild for the iptables-based rules ([NetScope.Background] rules are not part of the chain). */
    fun rebuildScript(rules: List<Pair<FirewallRule, Int>>): String {
        val chained = rules.filter { it.first.scope != NetScope.Background }
        val lines = mutableListOf<String>()
        for (t in tables) {
            if (chained.isEmpty()) {
                lines += "$t -w -D OUTPUT -j $CHAIN 2>/dev/null"
                lines += "$t -w -F $CHAIN 2>/dev/null"
                lines += "$t -w -X $CHAIN 2>/dev/null"
                continue
            }
            lines += "$t -w -N $CHAIN 2>/dev/null"
            lines += "$t -w -F $CHAIN"
            lines += "$t -w -C OUTPUT -j $CHAIN 2>/dev/null || $t -w -I OUTPUT -j $CHAIN"
            for ((rule, uid) in chained) {
                val ifaces = when (rule.scope) {
                    NetScope.Mobile -> mobileInterfaces
                    NetScope.Wifi -> wifiInterfaces
                    else -> listOf(null)
                }
                for (iface in ifaces) {
                    lines += buildString {
                        append(t).append(" -w -A ").append(CHAIN)
                        if (iface != null) append(" -o ").append(iface)
                        append(" -m owner --uid-owner ").append(uid).append(" -j REJECT")
                    }
                }
            }
        }
        lines += "echo ATOM_FW_OK"
        return lines.joinToString("\n")
    }

    /** Alternatives, newest name first; Android renamed the list in later releases. */
    fun backgroundCommands(uid: Int, block: Boolean): List<String> {
        val verb = if (block) "add" else "remove"
        return listOf(
            "cmd netpolicy $verb restrict-background-denylist $uid",
            "cmd netpolicy $verb restrict-background-blacklist $uid",
        )
    }

    fun listCommand(): String = "iptables -w -S $CHAIN 2>/dev/null; ip6tables -w -S $CHAIN 2>/dev/null"

    /** UIDs that have a REJECT rule in an `iptables -S` listing. */
    fun blockedUids(listing: String): Set<Int> =
        Regex("--uid-owner (\\d+)").findAll(listing).map { it.groupValues[1].toInt() }.toSet()
}

/** Saved rules plus applying them through the root shell. */
class Firewall(context: Context) {
    private val app = context.applicationContext
    private val file = File(app.filesDir, "atom4_firewall_rules.json")
    private val json = Json { ignoreUnknownKeys = true }

    fun rules(): List<FirewallRule> = try {
        if (file.exists()) json.decodeFromString<List<FirewallRule>>(file.readText()) else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(rules: List<FirewallRule>) = file.writeText(json.encodeToString(rules))

    fun uidOf(pkg: String): Int? = runCatching {
        app.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS).uid
    }.getOrNull()

    /** Adds or removes one rule and re-applies everything. Returns an error message or null. */
    suspend fun change(sh: ShellExecutor, rule: FirewallRule, block: Boolean): String? {
        val current = rules().filterNot { it.packageName == rule.packageName && it.scope == rule.scope }
        val next = if (block) current + rule else current
        if (rule.scope == NetScope.Background) {
            val uid = uidOf(rule.packageName) ?: return "${rule.label} is not installed."
            val ok = FirewallRules.backgroundCommands(uid, block).any { sh.run(it, 10_000).ok }
            if (!ok) return "Android's background-data restriction command is not available on this phone."
        } else {
            apply(sh, next)?.let { return it }
        }
        save(next)
        BootReapplyReceiver.sync(app)
        return null
    }

    /** Rebuilds the kernel rules from [rules]; also used after boot. */
    suspend fun apply(sh: ShellExecutor, rules: List<FirewallRule> = rules()): String? {
        val resolved = rules.mapNotNull { r -> uidOf(r.packageName)?.let { r to it } }
        val r = sh.run(FirewallRules.rebuildScript(resolved), 30_000)
        // Expected failures are silenced in the script, so anything on stderr means a rule did not apply.
        return if (r.stdout.contains("ATOM_FW_OK") && r.stderr.isBlank()) null
        else "iptables failed: ${(r.stderr.ifBlank { r.stdout }).take(300)}"
    }

    /** UIDs the kernel currently blocks, for verification. */
    suspend fun liveBlocked(sh: ShellExecutor): Set<Int> = FirewallRules.blockedUids(sh.run(FirewallRules.listCommand(), 10_000).stdout)

    suspend fun backgroundRestricted(sh: ShellExecutor, uid: Int): Boolean? {
        for (list in listOf("restrict-background-denylist", "restrict-background-blacklist")) {
            val r = sh.run("cmd netpolicy list $list", 10_000)
            if (r.ok && !r.stdout.contains("Unknown", ignoreCase = true)) return Regex("\\b$uid\\b").containsMatchIn(r.stdout)
        }
        return null
    }
}
