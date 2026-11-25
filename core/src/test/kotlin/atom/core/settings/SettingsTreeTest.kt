package atom.core.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsTreeTest {
    private val allEntries = listOf(
        "device/tools" to "Tools",
        "device/permissions" to "Tools",
        "device/changes" to "System",
        "device/power" to "System",
        "context/notifications" to "Context",
        "extend/memory" to "Context",
        "extend/skills" to "Context",
        "extend/mcp" to "Context",
        "extend/subagents" to "Context",
        "characters/library" to "Context",
        "linux/terminal" to "System",
        "linux/home" to "System",
        "linux/workspace" to "System",
        "vdisplay/system" to "System",
        "general/appearance" to "General",
        "general/language" to "General",
        "general/backup" to "General",
        "general/about" to "General",
        "voice/settings" to "General",
        "voice/chat" to "General",
        "overlay/settings" to "General",
        "automation/tasks" to "Context",
        "automation/rules" to "Context",
        "web/search" to "Tools",
        "web/images" to "Tools",
        "web/gallery" to "Tools",
        "web/browser" to "Tools",
    )

    @Test
    fun groupsFollowTheClassicOrder() {
        val groups = SettingsTree.place(allEntries).map { it.group }
        assertEquals(
            listOf(
                SettingsGroup.Llm, SettingsGroup.Context, SettingsGroup.Tools, SettingsGroup.System, SettingsGroup.Assistant,
                SettingsGroup.CircleToSearch, SettingsGroup.General, SettingsGroup.Permissions, SettingsGroup.About,
            ),
            groups,
        )
    }

    @Test
    fun classicRowsComeFirstInTheirOrder() {
        val placed = SettingsTree.place(allEntries).associate { it.group to it.keys }
        assertEquals(
            listOf(
                SettingsRows.AUTO_COMPACTION, SettingsRows.AUTO_TITLES, "extend/memory", "extend/subagents", "extend/skills",
                "extend/mcp", "automation/tasks", "characters/library",
            ),
            placed.getValue(SettingsGroup.Context).take(8),
        )
        assertEquals(
            listOf("device/tools", "web/search", "web/images", SettingsRows.BACKGROUND_DISPLAY, "linux/home"),
            placed.getValue(SettingsGroup.Tools).take(5),
        )
        assertEquals(
            listOf(SettingsRows.KEEP_SCREEN_ON, "general/appearance", "voice/settings", SettingsRows.LANGUAGE, "general/backup"),
            placed.getValue(SettingsGroup.General).take(5),
        )
        assertEquals(listOf(SettingsRows.DIGITAL_ASSISTANT, "overlay/settings"), placed.getValue(SettingsGroup.Assistant).take(2))
    }

    @Test
    fun everyFeatureEntryStaysReachable() {
        val shown = SettingsTree.place(allEntries).flatMap { it.keys }.toSet()
        for ((route, _) in allEntries) {
            if (route in SettingsTree.replacedRoutes) assertFalse(route in shown, route) else assertTrue(route in shown, route)
        }
    }

    @Test
    fun unknownEntriesJoinTheirSectionGroup() {
        val placed = SettingsTree.place(allEntries + ("newthing/screen" to "Context") + ("other/screen" to "Models"))
            .associate { it.group to it.keys }
        assertEquals("newthing/screen", placed.getValue(SettingsGroup.Context).last())
        assertEquals("other/screen", placed.getValue(SettingsGroup.Llm).last())
    }

    @Test
    fun missingRoutesAndHiddenRowsAreDropped() {
        val placed = SettingsTree.place(emptyList()) { it !in SettingsRows.NEEDS_FRAMEWORK }
        val groups = placed.associate { it.group to it.keys }
        assertFalse(SettingsGroup.CircleToSearch in groups)
        assertEquals(listOf(SettingsRows.DIGITAL_ASSISTANT), groups.getValue(SettingsGroup.Assistant))
        assertEquals(listOf(SettingsRows.OVERLAY_PERMISSION, SettingsRows.ACCESSIBILITY, SettingsRows.SETUP), groups.getValue(SettingsGroup.Permissions))
        assertTrue(placed.flatMap { it.keys }.none { !SettingsRows.isRow(it) })
    }

    @Test
    fun noKeyIsListedTwice() {
        val keys = SettingsTree.layout.flatMap { it.second }
        assertEquals(keys.size, keys.toSet().size)
    }
}
