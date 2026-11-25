package atom.device.caps

import android.content.Context
import atom.device.ShellTier
import atom.device.Shells
import atom.device.root.RootAccess
import atom.device.switches.ToolSwitch

/** What a group of tools needs, evaluated without prompting. */
enum class Needs(val label: String) {
    Nothing("Works with standard permissions"),
    Accessibility("Needs the accessibility service (or Shizuku/root for the UI-dump fallback)"),
    ShizukuOrRoot("Needs Shizuku or root"),
    Root("Needs root"),
    Permission("Needs the permissions listed on the Permissions page"),
    ;

    fun met(context: Context): Boolean = when (this) {
        Nothing, Permission -> true
        Accessibility -> atom.device.Accessibility.connected.value || Shells.tier.value.let { it == ShellTier.Root || it == ShellTier.Shizuku }
        ShizukuOrRoot -> RootAccess.granted || Shells.tier.value == ShellTier.Shizuku
        Root -> RootAccess.granted
    }
}

/** A card on the Tool list screen. */
data class ToolGroupInfo(
    val id: String,
    val title: String,
    val description: String,
    val tools: List<String>,
    val needs: Needs,
    val switches: Set<ToolSwitch>,
    val order: Int,
)

/** Tool cards from every module, shown in the Tool list's "This device" and "All capabilities" views. */
object ToolCatalog {
    private val groups = LinkedHashMap<String, ToolGroupInfo>()

    fun register(vararg g: ToolGroupInfo) = synchronized(groups) { g.forEach { groups[it.id] = it } }

    val all: List<ToolGroupInfo> get() = synchronized(groups) { groups.values.sortedBy { it.order } }

    init {
        register(
            ToolGroupInfo(
                "screen", "Screens and controls",
                "Read the screen, tap, long-press, swipe, scroll, type, keys, panels and waiting. Every action is checked by observing the screen again.",
                listOf("read_screen", "tap", "long_press", "swipe", "scroll", "type_text", "global_action", "press_key", "open_panel", "wait"),
                Needs.Accessibility, setOf(ToolSwitch.DeviceDirect), 10,
            ),
            ToolGroupInfo(
                "text", "Text and clipboard", "Paste text, set and read the clipboard.",
                listOf("paste_text", "set_clipboard", "get_clipboard"), Needs.Nothing, setOf(ToolSwitch.DeviceDirect), 20,
            ),
            ToolGroupInfo(
                "apps", "Applications and system",
                "Search, open and inspect apps; open links; device, network and environment status.",
                listOf("open_app", "list_apps", "open_url", "inspect_app", "device_status"), Needs.Nothing, setOf(ToolSwitch.DeviceDirect), 30,
            ),
            ToolGroupInfo(
                "toggles", "System toggles",
                "Wi-Fi, Bluetooth, mobile data, airplane, location, NFC, battery saver, hotspot, flashlight, brightness, " +
                    "auto-rotate, timeout, dark mode, night light, volumes, sound mode and Do Not Disturb, each reported before → after. " +
                    "Without Shizuku or root some open the right system panel instead.",
                listOf("get_device_controls", "set_toggle", "set_brightness", "set_screen_timeout", "set_dark_mode", "set_volume", "set_sound_mode", "set_do_not_disturb"),
                Needs.Nothing, setOf(ToolSwitch.DeviceDirect), 40,
            ),
            ToolGroupInfo(
                "undo", "Change history", "Every change any tool makes is journaled with its before-state; undo one, the last, or everything from today.",
                listOf("list_changes", "undo_last_change", "undo_change", "undo_changes_today"), Needs.Nothing, setOf(ToolSwitch.DeviceDirect), 45,
            ),
            ToolGroupInfo(
                "system_ops", "System operations", "Freeze, unfreeze and force-stop apps; read and write protected settings keys.",
                listOf("freeze_app", "unfreeze_app", "force_stop_app", "read_setting", "write_setting"), Needs.ShizukuOrRoot,
                setOf(ToolSwitch.DeviceDirect, ToolSwitch.SensitiveOps), 50,
            ),
            ToolGroupInfo(
                "logs", "System logs", "Read logcat filtered by process, tag, level, time and text.",
                listOf("read_system_logs"), Needs.ShizukuOrRoot, setOf(ToolSwitch.DeviceDirect, ToolSwitch.SensitiveRead), 55,
            ),
            ToolGroupInfo(
                "power", "Power tools",
                "Per-app firewall (root), background-data restriction, refresh-rate lock, battery charge limit (root and a supported kernel), " +
                    "what drained the battery, and GPS spoofing (Atom as mock location app).",
                listOf("app_firewall", "refresh_rate", "charge_limit", "battery_drain", "spoof_location"), Needs.ShizukuOrRoot,
                setOf(ToolSwitch.SensitiveOps), 60,
            ),
            ToolGroupInfo(
                "shell", "Terminal", "Shell commands as the app user, Shizuku or root.", listOf("shell"), Needs.Nothing,
                setOf(ToolSwitch.TerminalFiles), 90,
            ),
            // Tools from modules that do not depend on this one; listed by name so each gets its own switch.
            ToolGroupInfo(
                "web", "Web search and reading", "Search the web and read pages with the service set in Web search and reading.",
                listOf("web_search", "read_page"), Needs.Nothing, emptySet(), 25,
            ),
            ToolGroupInfo(
                "browser", "Agent browser", "Open, read and operate web pages in Atom's own browser; you can take over any time.",
                listOf(
                    "browser_open", "browser_navigate", "browser_extract", "browser_find", "browser_click", "browser_type",
                    "browser_submit", "browser_scroll", "browser_screenshot", "browser_wait",
                ),
                Needs.Nothing, emptySet(), 26,
            ),
            ToolGroupInfo(
                "image", "AI image generation", "Generate and edit images with the service set in AI image generation.",
                listOf("generate_image", "edit_image"), Needs.Nothing, emptySet(), 27,
            ),
            ToolGroupInfo(
                "vision", "Document vision", "Look at local images, screenshots and scanned documents.",
                listOf("view_image"), Needs.Nothing, emptySet(), 80,
            ),
            ToolGroupInfo(
                "memory", "Memory", "Read and update Atom's long-term memory (MEMORY.md).",
                listOf("memory_read", "memory_append", "memory_update", "memory_clear"), Needs.Nothing, emptySet(), 85,
            ),
            ToolGroupInfo(
                "terminal", "Linux terminal", "Run commands and background jobs in the Linux environment.",
                listOf(
                    "terminal_run", "terminal_session", "terminal_session_read", "terminal_job_start", "terminal_job_info",
                    "terminal_job_stop", "terminal_root_check",
                ),
                Needs.Nothing, emptySet(), 92,
            ),
            ToolGroupInfo(
                "files", "Files", "List, read, write, edit and search files in the workspace and shared storage.",
                listOf("file_list", "file_read", "file_write", "file_edit", "file_find", "file_grep", "file_info"),
                Needs.Nothing, emptySet(), 94,
            ),
        )
    }
}
