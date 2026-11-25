package atom.device.godmode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirewallRulesTest {
    private val tiktok = FirewallRule("com.zhiliaoapp.musically", "TikTok", NetScope.Mobile)

    @Test
    fun mobileRuleCoversEveryCellularInterfaceOnBothStacks() {
        val s = FirewallRules.rebuildScript(listOf(tiktok to 10234))
        for (t in listOf("iptables", "ip6tables")) {
            assertTrue(s.contains("$t -w -N atom_fw"))
            assertTrue(s.contains("$t -w -F atom_fw"))
            assertTrue(s.contains("$t -w -C OUTPUT -j atom_fw 2>/dev/null || $t -w -I OUTPUT -j atom_fw"))
            for (i in FirewallRules.mobileInterfaces) {
                assertTrue(s.contains("$t -w -A atom_fw -o $i -m owner --uid-owner 10234 -j REJECT"), "missing $t $i")
            }
            assertFalse(s.contains("$t -w -A atom_fw -o wlan+"))
        }
        assertTrue(s.trimEnd().endsWith("echo ATOM_FW_OK"))
    }

    @Test
    fun allNetworksRuleHasNoInterface() {
        val s = FirewallRules.rebuildScript(listOf(tiktok.copy(scope = NetScope.All) to 10234))
        assertTrue(s.contains("iptables -w -A atom_fw -m owner --uid-owner 10234 -j REJECT"))
        assertTrue(s.contains("ip6tables -w -A atom_fw -m owner --uid-owner 10234 -j REJECT"))
    }

    @Test
    fun wifiRule() {
        val s = FirewallRules.rebuildScript(listOf(tiktok.copy(scope = NetScope.Wifi) to 10234))
        assertTrue(s.contains("iptables -w -A atom_fw -o wlan+ -m owner --uid-owner 10234 -j REJECT"))
        assertFalse(s.contains("rmnet"))
    }

    @Test
    fun backgroundRulesAreNotKernelRules() {
        val s = FirewallRules.rebuildScript(listOf(tiktok.copy(scope = NetScope.Background) to 10234))
        assertFalse(s.contains("--uid-owner"))
        assertTrue(s.contains("iptables -w -X atom_fw"), "no chained rules left means the chain is removed")
        assertEquals(
            listOf("cmd netpolicy add restrict-background-denylist 10234", "cmd netpolicy add restrict-background-blacklist 10234"),
            FirewallRules.backgroundCommands(10234, block = true),
        )
        assertTrue(FirewallRules.backgroundCommands(10234, block = false).all { it.contains(" remove ") })
    }

    @Test
    fun emptyRulesDetachAndDeleteTheChain() {
        val s = FirewallRules.rebuildScript(emptyList())
        for (t in listOf("iptables", "ip6tables")) {
            assertTrue(s.contains("$t -w -D OUTPUT -j atom_fw 2>/dev/null"))
            assertTrue(s.contains("$t -w -X atom_fw 2>/dev/null"))
        }
        assertFalse(s.contains("-A atom_fw"))
    }

    @Test
    fun rebuildFlushesBeforeAddingSoRulesNeverDuplicate() {
        val s = FirewallRules.rebuildScript(listOf(tiktok to 10234, FirewallRule("com.x", "X", NetScope.All) to 10500)).lines()
        val flush = s.indexOf("iptables -w -F atom_fw")
        val firstAdd = s.indexOfFirst { it.startsWith("iptables -w -A atom_fw") }
        assertTrue(flush in 0 until firstAdd)
        assertEquals(1, s.count { it == "iptables -w -A atom_fw -m owner --uid-owner 10500 -j REJECT" })
    }

    @Test
    fun parsesBlockedUidsFromListing() {
        val listing = """
            -N atom_fw
            -A atom_fw -o rmnet+ -m owner --uid-owner 10234 -j REJECT --reject-with icmp-port-unreachable
            -A atom_fw -m owner --uid-owner 10500 -j REJECT
        """.trimIndent()
        assertEquals(setOf(10234, 10500), FirewallRules.blockedUids(listing))
    }
}
