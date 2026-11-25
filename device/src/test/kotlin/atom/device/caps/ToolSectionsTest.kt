package atom.device.caps

import atom.device.switches.ToolSwitch
import atom.device.switches.ToolSwitches
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolSectionsTest {
    private fun group(id: String, order: Int, vararg tools: String, needs: Needs = Needs.Nothing, switches: Set<ToolSwitch> = emptySet()) =
        ToolGroupInfo(id, id, "about $id", tools.toList(), needs, switches, order)

    @Test
    fun sectionsFollowTheClassicOrder() {
        // This module's own cards (personal context and built-in apps are registered by the context module).
        val sections = ToolSections.build(ToolCatalog.all, isOn = { true }).map { it.section }
        assertEquals(
            listOf(
                ToolSection.Screen, ToolSection.Text, ToolSection.Web, ToolSection.Apps,
                ToolSection.Vision, ToolSection.Memory, ToolSection.Terminal,
            ),
            sections,
        )
        assertEquals(
            listOf(
                "Screens and controls", "Text and clipboard", "Web tools", "Applications and Systems", "System and built-in apps",
                "Personal context", "Document vision", "Memory", "Terminal and files",
            ),
            ToolSection.entries.map { it.title },
        )
    }

    @Test
    fun newerCardsJoinTheClosestSection() {
        val groups = listOf(group("apps", 30, "open_app"), group("power", 60, "charge_limit"), group("context_root", 74, "wifi_saved_passwords"))
        val bySection = ToolSections.build(groups, isOn = { true }).associate { it.section to it.rows.map { r -> r.tool } }
        assertEquals(listOf("open_app", "charge_limit"), bySection[ToolSection.Apps])
        assertEquals(listOf("wifi_saved_passwords"), bySection[ToolSection.Personal])
        assertEquals(ToolSection.Apps, ToolSections.sectionOf(group("something_new", 1, "x")))
    }

    @Test
    fun countsAndFilters() {
        val groups = listOf(group("screen", 10, "tap", "swipe", "scroll"), group("text", 20, "paste_text", "get_clipboard"))
        val off = setOf("swipe", "get_clipboard")
        val sections = ToolSections.build(groups, isOn = { it !in off })
        assertEquals(listOf(2, 1), sections.map { it.enabled })
        assertEquals(3 to 5, ToolSections.totals(sections))
        assertEquals(listOf(ToolSection.Text), ToolSections.filter(sections, ToolSection.Text).map { it.section })
        assertEquals(sections, ToolSections.filter(sections, null))
    }

    @Test
    fun includeHidesCardsAndDuplicatesShowOnce() {
        val groups = listOf(group("screen", 10, "tap", "wait"), group("context_root", 74, "app_private_data", needs = Needs.Root), group("text", 20, "wait"))
        val sections = ToolSections.build(groups, isOn = { true }) { it.needs != Needs.Root }
        assertEquals(listOf("tap", "wait"), sections.flatMap { s -> s.rows.map { it.tool } })
    }

    @Test
    fun onlyShellNeedsHideFromTheCurrentDeviceView() {
        assertTrue(ToolSections.needsShell(Needs.Root))
        assertTrue(ToolSections.needsShell(Needs.ShizukuOrRoot))
        assertTrue(!ToolSections.needsShell(Needs.Accessibility))
        assertTrue(!ToolSections.needsShell(Needs.Permission))
    }

    @Test
    fun rowTitlesReadAsWords() {
        assertEquals("Read screen", ToolRow("read_screen", group("screen", 1, "read_screen")).title)
    }

    @Test
    fun offMastersFoldIntoTheirTools() {
        val groups = listOf(
            group("screen", 10, "tap", switches = setOf(ToolSwitch.DeviceDirect)),
            group("logs", 55, "read_system_logs", switches = setOf(ToolSwitch.DeviceDirect, ToolSwitch.SensitiveRead)),
            group("shell", 90, "shell", switches = setOf(ToolSwitch.TerminalFiles)),
            group("web", 25, "web_search", "read_page"),
            group("memory", 85, "memory_read"),
            group("terminal", 92, "terminal_run"),
        )
        // Real gates win: a tool built here whose gate names the switch is folded even without a card.
        val gates = mapOf("tap" to setOf(ToolSwitch.DeviceDirect), "hotspot" to setOf(ToolSwitch.DeviceDirect))
        assertEquals(setOf("read_system_logs"), ToolSwitches.foldedTools(groups, gates, listOf(ToolSwitch.SensitiveRead)))
        assertEquals(setOf("tap", "hotspot", "read_system_logs"), ToolSwitches.foldedTools(groups, gates, listOf(ToolSwitch.DeviceDirect)))
        // Switches that never gated anything keep what they said they covered.
        assertEquals(setOf("web_search", "read_page"), ToolSwitches.foldedTools(groups, gates, listOf(ToolSwitch.Web)))
        assertEquals(setOf("memory_read"), ToolSwitches.foldedTools(groups, gates, listOf(ToolSwitch.Memory)))
        assertEquals(setOf("shell", "terminal_run"), ToolSwitches.foldedTools(groups, gates, listOf(ToolSwitch.TerminalFiles)))
        assertTrue(ToolSwitches.foldedTools(groups, gates, emptyList()).isEmpty())
    }

    /**
     * The gates this module's tools are built with (DeviceTools, DeviceFeature and the background display), written
     * out: every card listing one of them must name the same switches.
     */
    private val deviceGates: Map<String, Set<ToolSwitch>> = buildMap {
        val dd = setOf(ToolSwitch.DeviceDirect)
        listOf(
            "read_screen", "tap", "long_press", "type_text", "paste_text", "scroll", "swipe", "global_action",
            "press_key", "open_panel", "wait", "open_app", "list_apps", "open_url", "device_status", "set_clipboard",
            "get_clipboard", "inspect_app", "battery_drain",
            "background_open_app", "background_look", "background_act", "background_close", "present_to_main_screen",
        ).forEach { put(it, dd) }
        put("shell", setOf(ToolSwitch.TerminalFiles))
        listOf("read_setting", "read_system_logs").forEach { put(it, setOf(ToolSwitch.DeviceDirect, ToolSwitch.SensitiveRead)) }
        listOf("freeze_app", "unfreeze_app", "force_stop_app", "write_setting").forEach {
            put(it, setOf(ToolSwitch.DeviceDirect, ToolSwitch.SensitiveOps))
        }
        listOf("app_firewall", "refresh_rate", "charge_limit", "spoof_location").forEach { put(it, setOf(ToolSwitch.SensitiveOps)) }
    }

    @Test
    fun cardsMatchTheRealGates() {
        assertEquals(emptyList<String>(), ToolCatalog.mismatches(ToolCatalog.all, deviceGates))
    }

    @Test
    fun mismatchesAreReported() {
        val cards = listOf(group("power", 60, "battery_drain", switches = setOf(ToolSwitch.SensitiveOps)))
        val gates = mapOf("battery_drain" to setOf(ToolSwitch.DeviceDirect), "hotspot" to setOf(ToolSwitch.DeviceDirect))
        assertEquals(2, ToolCatalog.mismatches(cards, gates).size)
        val extra = ToolCatalog.uncardedGroup(cards, gates)
        assertEquals(listOf("hotspot"), extra?.tools)
        assertEquals(ToolSection.Apps, ToolSections.sectionOf(extra!!))
        assertEquals(null, ToolCatalog.uncardedGroup(cards, mapOf("battery_drain" to emptySet())))
    }
}
