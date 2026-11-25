package atom.automation

import atom.core.tool.Risk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapabilityToolsTest {
    private data class T(val name: String, val origin: ToolOrigin, val risk: Risk = Risk.Read, val ui: Boolean = false, val desc: String = "")

    private fun family(t: T) = CapabilityTools.classify(t.name, t.desc, t.origin, t.risk, t.ui)

    private val all = listOf(
        T("read_screen", ToolOrigin.Device, ui = true),
        T("tap", ToolOrigin.Device, Risk.Act, ui = true),
        T("device_status", ToolOrigin.Device),
        T("set_toggle", ToolOrigin.Device, Risk.Sensitive),
        T("calendar_events", ToolOrigin.Device),
        T("shell", ToolOrigin.Device, Risk.Sensitive),
        T("terminal_run", ToolOrigin.Linux, Risk.Sensitive),
        T("file_read", ToolOrigin.Web),
        T("web_search", ToolOrigin.Web),
        T("browser_open", ToolOrigin.Web, Risk.Act),
        T("memory_read", ToolOrigin.Extend),
        T("skill_install_github", ToolOrigin.Extend, Risk.Sensitive),
        T("github__create_issue", ToolOrigin.Extend, Risk.Sensitive, desc = "[MCP · github] Create an issue"),
        T("search_history", ToolOrigin.Other),
        T("scheduled_task_create", ToolOrigin.Other, Risk.Act),
    )

    private fun names(caps: Set<Capability>) = CapabilityTools.filter(all, caps, ::family).map { it.name }.toSet()

    @Test
    fun chatOnlyHasNoTools() {
        assertEquals(emptySet(), names(emptySet()))
        assertEquals(emptySet(), CapabilityTools.families(emptySet()))
        ToolFamily.entries.forEach { assertFalse(CapabilityTools.allows(emptySet(), it)) }
    }

    @Test
    fun classification() {
        assertEquals(ToolFamily.AppUi, family(all[0]))
        assertEquals(ToolFamily.AppUi, family(all[1]))
        assertEquals(ToolFamily.DeviceRead, family(all[2]))
        assertEquals(ToolFamily.DeviceControl, family(all[3]))
        assertEquals(ToolFamily.Terminal, family(all[5]))
        assertEquals(ToolFamily.Terminal, family(all[6]))
        assertEquals(ToolFamily.Terminal, family(all[7]))
        assertEquals(ToolFamily.Web, family(all[8]))
        assertEquals(ToolFamily.Assistant, family(all[10]))
        assertEquals(ToolFamily.Web, family(all[11]))
        assertEquals(ToolFamily.Mcp, family(all[12]))
        assertEquals(ToolFamily.Assistant, family(all[13]))
        assertEquals(ToolFamily.DeviceControl, family(all[14]))
    }

    @Test
    fun defaultIsWebAndDeviceReading() {
        val got = names(Capability.DEFAULT)
        assertEquals(setOf("device_status", "calendar_events", "web_search", "browser_open", "memory_read", "skill_install_github", "search_history"), got)
    }

    @Test
    fun eachCapabilityOpensOnlyItsFamily() {
        assertEquals(setOf("read_screen", "tap", "memory_read", "search_history"), names(setOf(Capability.AppUi)))
        assertEquals(setOf("shell", "terminal_run", "file_read", "memory_read", "search_history"), names(setOf(Capability.Terminal)))
        assertEquals(setOf("github__create_issue", "memory_read", "search_history"), names(setOf(Capability.Mcp)))
        assertEquals(setOf("set_toggle", "scheduled_task_create", "memory_read", "search_history"), names(setOf(Capability.DeviceControl)))
    }

    @Test
    fun everythingAllowsEverything() {
        assertEquals(all.map { it.name }.toSet(), names(Capability.entries.toSet()))
        assertTrue(CapabilityTools.allows(setOf(Capability.Web), ToolFamily.Assistant))
    }
}
