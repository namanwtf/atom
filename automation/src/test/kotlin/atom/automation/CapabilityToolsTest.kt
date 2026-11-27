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
        assertEquals(ToolFamily.Automation, family(all[14]))
        assertEquals(ToolFamily.Automation, family(T("automation_create", ToolOrigin.Other, Risk.Sensitive)))
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
        assertEquals(setOf("set_toggle", "memory_read", "search_history"), names(setOf(Capability.DeviceControl)))
    }

    @Test
    fun everyCapabilityAllowsEverythingButTaskCreation() {
        assertEquals(all.map { it.name }.toSet() - "scheduled_task_create", names(Capability.entries.toSet()))
        assertTrue(CapabilityTools.allows(setOf(Capability.Web), ToolFamily.Assistant))
        // A limited run can never create a task with wider capabilities than its own.
        assertFalse(CapabilityTools.allows(Capability.entries.toSet(), ToolFamily.Automation))
    }

    @Test
    fun hostedSearchNeedsTheWeb() {
        assertTrue(CapabilityTools.allowsHostedSearch(null))
        assertTrue(CapabilityTools.allowsHostedSearch(setOf(Capability.Web)))
        assertFalse(CapabilityTools.allowsHostedSearch(emptySet()))
        assertFalse(CapabilityTools.allowsHostedSearch(setOf(Capability.DeviceRead, Capability.Terminal)))
    }

    @Test
    fun hostedSearchNeedsTheCapabilityTheSwitchAndTheWebSetting() {
        assertTrue(CapabilityTools.hostedSearch(null, webSearchToolOn = true, webEnabled = true))
        assertTrue(CapabilityTools.hostedSearch(setOf(Capability.Web), webSearchToolOn = true, webEnabled = true))
        // "Chat only" and runs without the web.
        assertFalse(CapabilityTools.hostedSearch(emptySet(), webSearchToolOn = true, webEnabled = true))
        assertFalse(CapabilityTools.hostedSearch(setOf(Capability.Terminal), webSearchToolOn = true, webEnabled = true))
        // web_search switched off on the Tool list.
        assertFalse(CapabilityTools.hostedSearch(null, webSearchToolOn = false, webEnabled = true))
        // "Web search and reading" turned off in Settings.
        assertFalse(CapabilityTools.hostedSearch(null, webSearchToolOn = true, webEnabled = false))
        assertFalse(CapabilityTools.hostedSearch(setOf(Capability.Web), webSearchToolOn = true, webEnabled = false))
    }

    @Test
    fun aWebOnlyRunCannotWriteOrDeleteSiteFiles() {
        val site = listOf(
            T("site_create", ToolOrigin.Web, Risk.Act),
            T("site_write", ToolOrigin.Web, Risk.Act),
            T("site_delete_file", ToolOrigin.Web, Risk.Act),
            T("site_read", ToolOrigin.Web),
            T("site_list", ToolOrigin.Web),
            T("site_preview", ToolOrigin.Web),
        )
        // Writing, deleting and creating change files on the phone: the Terminal (files) family.
        for (name in listOf("site_create", "site_write", "site_delete_file")) {
            assertEquals(ToolFamily.Terminal, family(site.first { it.name == name }), name)
        }
        val web = CapabilityTools.filter(site, setOf(Capability.Web), ::family).map { it.name }.toSet()
        assertEquals(setOf("site_read", "site_list", "site_preview"), web)
        val files = CapabilityTools.filter(site, setOf(Capability.Terminal), ::family).map { it.name }.toSet()
        assertEquals(setOf("site_create", "site_write", "site_delete_file"), files)
        // A scheduled task with the default capabilities (web and device reading) cannot change a site either.
        assertTrue(CapabilityTools.filter(site, Capability.DEFAULT, ::family).none { it.name in setOf("site_create", "site_write", "site_delete_file") })
    }
}
