package atom.automation

/**
 * Which on-demand group each tool belongs to (see `ToolLoadout`). Tools in no group are the core set, sent with
 * every request: reading and operating the screen, opening apps and links, web search and reading, memory and
 * skill reading, history search. Everything else is listed by group in the loader and sent once the model loads
 * the group.
 *
 * Unknown tool names (new tools, renamed ones) stay in the core set, so nothing silently disappears. MCP tools are
 * grouped per server. The per-tool switches and capability filters run before this and stay authoritative.
 */
object ToolGroups {
    /** Always sent. */
    val CORE: Set<String> = setOf(
        "read_screen", "tap", "type_text", "scroll", "swipe", "global_action", "wait",
        "open_app", "list_apps", "open_url",
        "web_search", "read_page",
        "memory_read", "memory_append", "skill_read",
        "search_history", "history_search", "release_phone_control",
        "story_memory_update",
    )

    private val BY_NAME: Map<String, Set<String>> = buildMap {
        fun group(id: String, vararg names: String) = names.forEach { put(it, setOf(id)) }
        group("phone", "long_press", "paste_text", "press_key", "open_panel", "get_clipboard", "set_clipboard", "device_status", "media_control")
        group(
            "settings", "get_device_controls", "set_toggle", "set_brightness", "set_screen_timeout", "set_dark_mode", "set_volume",
            "set_sound_mode", "set_do_not_disturb", "list_changes", "undo_last_change", "undo_change", "undo_changes_today",
        )
        group(
            "system", "inspect_app", "freeze_app", "unfreeze_app", "force_stop_app", "read_setting", "write_setting",
            "read_system_logs", "app_firewall", "refresh_rate", "charge_limit", "battery_drain", "spoof_location",
        )
        group("clock", "set_alarm", "set_timer", "alarms_and_timers", "change_alarm")
        group("calendar", "calendar_list_calendars", "calendar_search", "calendar_read", "calendar_create", "calendar_update", "calendar_delete")
        group(
            "messages", "sms_search", "sms_verification_code", "call_log_search", "contacts_search", "notifications_current",
            "notification_history", "messenger_triage",
        )
        group(
            "personal", "time_and_location", "files_search", "app_usage", "health_summary", "memory_ranking", "storage_ranking",
            "wifi_saved_passwords", "clipboard_history", "app_private_data",
        )
        // Full text of a hit from either kind of search.
        put("read_item", setOf("messages", "personal"))
        group(
            "browser", "browser_open", "browser_extract", "browser_find", "browser_click", "browser_type", "browser_submit",
            "browser_scroll", "browser_screenshot", "browser_navigate", "browser_wait", "browser_close",
        )
        group("images", "generate_image", "edit_image")
        group("sites", "site_create", "site_write", "site_read", "site_list", "site_delete_file", "site_preview")
        group("background", "background_open_app", "background_look", "background_act", "present_to_main_screen", "background_close")
        group("memory", "memory_update", "memory_clear")
        group("skills", "skill_write", "skill_install_github")
        group("subagents", "spawn_subagent", "spawn_subagents_parallel", "subagent_save", "subagent_delete")
        group("characters", "list_characters")
    }

    /** One line per group for the loader's catalog. */
    val SUMMARIES: Map<String, String> = mapOf(
        "phone" to "long press, paste, keys, quick panels, clipboard, battery/network status, media playback",
        "settings" to "toggles (Wi-Fi, Bluetooth, torch...), brightness, volume, sound and do-not-disturb, dark mode; list and undo changes",
        "system" to "inspect, freeze or stop apps, protected settings, logs, firewall, refresh rate, charging, location",
        "clock" to "alarms and timers",
        "calendar" to "read, search and change calendar events",
        "messages" to "SMS, one-time codes, calls, contacts, notifications, messenger triage",
        "personal" to "location and time, files on the phone, app usage, health, storage and memory use",
        "terminal" to "Linux terminal and Android shell: run commands, sessions, background jobs",
        "files" to "read, write, edit and search files; look at local images",
        "browser" to "a real off-screen browser for pages needing JavaScript, sign-in, clicks or forms",
        "images" to "generate or edit images",
        "sites" to "make websites, web apps and games (HTML, CSS, JS) with a live preview on the phone",
        "background" to "run another app on a hidden display while the user keeps the screen",
        "memory" to "rewrite or clear long-term memory",
        "skills" to "write or install skills",
        "subagents" to "hand self-contained tasks to helpers with a fresh context, up to 4 in parallel",
        "automation" to "scheduled tasks and \"when X happens, do Y\" automations",
        "characters" to "list characters",
    )

    /**
     * Groups of one tool: empty for the core set. [description] is only read for MCP tools ("[MCP · server] ...").
     */
    fun groupsOf(name: String, description: String = ""): Set<String> {
        if (name in CORE) return emptySet()
        BY_NAME[name]?.let { return it }
        if (description.startsWith(MCP_MARK)) return setOf(mcpGroup(description))
        return when {
            name == "shell" || name.startsWith("terminal_") || name.startsWith("linux_") -> setOf("terminal")
            name.startsWith("file_") || name == "view_image" -> setOf("files")
            name.startsWith("scheduled_task_") || name.startsWith("automation_") -> setOf("automation")
            name.startsWith("calendar_") -> setOf("calendar")
            name.startsWith("browser_") -> setOf("browser")
            name.startsWith("site_") -> setOf("sites")
            else -> emptySet()
        }
    }

    /** "mcp_<server>" from "[MCP · server] ...", lower case, letters, digits and underscores only. */
    fun mcpGroup(description: String): String {
        val server = description.removePrefix(MCP_MARK).substringBefore(']').trim().removePrefix("·").trim()
        val clean = server.lowercase().map { if (it.isLetterOrDigit() && it.code < 128) it else '_' }.joinToString("").trim('_')
        return "mcp_" + clean.ifEmpty { "server" }.replace(Regex("_+"), "_")
    }

    /** Catalog line for an MCP server's group. */
    fun summary(group: String): String? = SUMMARIES[group] ?: if (group.startsWith("mcp_")) "tools of the ${group.removePrefix("mcp_")} MCP server" else null

    private const val MCP_MARK = "[MCP"
}
