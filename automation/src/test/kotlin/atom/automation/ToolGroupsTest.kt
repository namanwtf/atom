package atom.automation

import atom.core.agent.RequestSize
import atom.core.llm.ToolSpec
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolContext
import atom.core.tool.ToolLoadout
import atom.core.tool.ToolResult
import atom.core.tool.schema
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolGroupsTest {
    private class Fake(name: String, description: String = "x".repeat(380)) : Tool {
        override val spec = ToolSpec(name, description, schema { string("arg", "y".repeat(80)) })
        override val risk = Risk.Read
        override suspend fun run(args: JsonObject, ctx: ToolContext) = ToolResult.ok("ok")
    }

    /** Every tool a phone with root and every feature on can offer (MCP aside). */
    private val everyTool = listOf(
        "read_screen", "tap", "long_press", "type_text", "paste_text", "scroll", "swipe", "global_action",
        "press_key", "open_panel", "wait", "open_app", "list_apps", "open_url", "shell", "device_status", "set_clipboard", "get_clipboard",
        "get_device_controls", "set_toggle", "set_brightness", "set_screen_timeout", "set_dark_mode", "set_volume", "set_sound_mode",
        "set_do_not_disturb", "list_changes", "undo_last_change", "undo_change", "undo_changes_today", "inspect_app", "spoof_location",
        "freeze_app", "unfreeze_app", "force_stop_app", "read_setting", "write_setting", "read_system_logs", "app_firewall",
        "refresh_rate", "charge_limit", "battery_drain", "background_open_app", "background_look", "background_act",
        "present_to_main_screen", "background_close", "time_and_location", "contacts_search", "files_search", "app_usage",
        "sms_verification_code", "sms_search", "call_log_search", "calendar_list_calendars", "calendar_search", "calendar_read",
        "calendar_create", "calendar_update", "calendar_delete", "set_alarm", "set_timer", "alarms_and_timers", "change_alarm",
        "memory_ranking", "storage_ranking", "media_control", "notifications_current", "notification_history", "read_item",
        "health_summary", "messenger_triage", "wifi_saved_passwords", "clipboard_history", "app_private_data",
        "terminal_run", "terminal_session", "terminal_session_read", "terminal_job_start", "terminal_job_info", "terminal_job_stop",
        "terminal_root_check", "file_read", "file_write", "file_edit", "file_info", "file_list", "file_find", "file_grep", "view_image",
        "web_search", "read_page", "browser_open", "browser_extract", "browser_find", "browser_click", "browser_type", "browser_submit",
        "browser_scroll", "browser_screenshot", "browser_navigate", "browser_wait", "browser_close", "generate_image", "edit_image",
        "scheduled_task_create", "scheduled_task_list", "scheduled_task_manage", "scheduled_task_delete", "automation_create",
        "automation_list", "automation_set_enabled", "automation_delete", "memory_read", "memory_append", "memory_update",
        "memory_clear", "skill_read", "skill_install_github", "skill_write", "spawn_subagent", "spawn_subagents_parallel",
        "subagent_save", "subagent_delete", "search_history", "release_phone_control",
        "site_create", "site_write", "site_read", "site_list", "site_delete_file", "site_preview",
    )

    @Test
    fun coreSetIsTheScreenTheWebAndReading() {
        for (name in listOf("read_screen", "tap", "type_text", "open_app", "web_search", "read_page", "memory_read", "skill_read", "search_history")) {
            assertEquals(emptySet(), ToolGroups.groupsOf(name), name)
        }
        assertEquals(setOf("browser"), ToolGroups.groupsOf("browser_open"))
        assertEquals(setOf("terminal"), ToolGroups.groupsOf("shell"))
        assertEquals(setOf("terminal"), ToolGroups.groupsOf("terminal_job_start"))
        assertEquals(setOf("files"), ToolGroups.groupsOf("file_grep"))
        assertEquals(setOf("sites"), ToolGroups.groupsOf("site_write"))
        assertEquals(setOf("automation"), ToolGroups.groupsOf("scheduled_task_create"))
        assertEquals(setOf("messages", "personal"), ToolGroups.groupsOf("read_item"))
    }

    @Test
    fun unknownToolsStayInTheCoreSet() {
        assertEquals(emptySet(), ToolGroups.groupsOf("brand_new_tool"))
    }

    @Test
    fun mcpToolsAreGroupedPerServer() {
        assertEquals(setOf("mcp_github"), ToolGroups.groupsOf("github__create_issue", "[MCP · github] Create an issue"))
        assertEquals("mcp_my_notes", ToolGroups.mcpGroup("[MCP · My Notes!] Search"))
        assertTrue(ToolGroups.summary("mcp_github")!!.contains("github"))
    }

    @Test
    fun everyGroupHasACatalogLine() {
        val groups = everyTool.flatMap { ToolGroups.groupsOf(it) }.toSet()
        groups.forEach { assertTrue(ToolGroups.summary(it) != null, it) }
    }

    @Test
    fun theFirstRequestCarriesAFractionOfTheToolSchemas() {
        val tools = everyTool.map { Fake(it) }
        val loadout = ToolLoadout(tools, { ToolGroups.groupsOf(it.spec.name, it.spec.description) }, ToolGroups.SUMMARIES)
        val first = loadout.specs()
        val all = tools.map { it.spec }
        val firstChars = first.sumOf { RequestSize.chars(it) }
        val allChars = all.sumOf { RequestSize.chars(it) }
        assertTrue(first.size <= 22, "core set has ${first.size} tools")
        assertTrue(firstChars * 100 / allChars <= 30, "first request carries $firstChars of $allChars tool chars")
        // Every tool can still be reached through some group.
        val reachable = loadout.groups.flatMap { g -> loadout.load(listOf(g)); loadout.specs().map { it.name } }.toSet()
        assertEquals(all.map { it.name }.toSet() + ToolLoadout.LOADER, reachable + first.map { it.name })
    }
}
