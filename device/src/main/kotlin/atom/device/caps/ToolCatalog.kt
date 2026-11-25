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

    /**
     * Set by the app: why a card's tools do not exist right now because their feature is off in its own settings
     * page (for example web search switched off), or null when the feature is on. The Tool list shows them off.
     */
    @Volatile
    var featureOff: (cardId: String) -> String? = { null }

    fun register(vararg g: ToolGroupInfo) = synchronized(groups) { g.forEach { groups[it.id] = it } }

    val all: List<ToolGroupInfo> get() = synchronized(groups) { groups.values.sortedBy { it.order } }

    /**
     * Where the cards disagree with the gates tools really check ([realGates], tool name to switches): a gated tool
     * with no card, or a card whose switches differ from the tool's gate. Empty when consistent.
     */
    fun mismatches(cards: List<ToolGroupInfo>, realGates: Map<String, Set<ToolSwitch>>): List<String> {
        val cardOf = HashMap<String, ToolGroupInfo>()
        cards.sortedBy { it.order }.forEach { g -> g.tools.forEach { t -> cardOf.putIfAbsent(t, g) } }
        return realGates.toSortedMap().mapNotNull { (tool, gate) ->
            val card = cardOf[tool]
            when {
                card == null -> "$tool has no card"
                card.switches != gate -> "$tool: card ${card.id} says ${card.switches}, gate is $gate"
                else -> null
            }
        }
    }

    /** A card for gated tools that no card lists yet (newer tools), so each still gets its own switch. */
    fun uncardedGroup(cards: List<ToolGroupInfo>, realGates: Map<String, Set<ToolSwitch>>): ToolGroupInfo? {
        val listed = cards.flatMap { it.tools }.toSet()
        val extra = realGates.keys.filter { it !in listed }.sorted()
        if (extra.isEmpty()) return null
        return ToolGroupInfo("more_device", "More device tools", "Newer device tools.", extra, Needs.Nothing, emptySet(), 99)
    }

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
                "system_ops", "System operations", "Freeze, unfreeze and force-stop apps; write protected settings keys.",
                listOf("freeze_app", "unfreeze_app", "force_stop_app", "write_setting"), Needs.ShizukuOrRoot,
                setOf(ToolSwitch.DeviceDirect, ToolSwitch.SensitiveOps), 50,
            ),
            ToolGroupInfo(
                "logs", "System logs and settings", "Read logcat filtered by process, tag, level, time and text; read protected settings keys.",
                listOf("read_system_logs", "read_setting"), Needs.ShizukuOrRoot, setOf(ToolSwitch.DeviceDirect, ToolSwitch.SensitiveRead), 55,
            ),
            ToolGroupInfo(
                "battery", "Battery usage", "What drained the battery, per app.",
                listOf("battery_drain"), Needs.ShizukuOrRoot, setOf(ToolSwitch.DeviceDirect), 58,
            ),
            ToolGroupInfo(
                "power", "Power tools",
                "Per-app firewall (root), background-data restriction, refresh-rate lock, battery charge limit (root and a supported kernel), " +
                    "and GPS spoofing (Atom as mock location app).",
                listOf("app_firewall", "refresh_rate", "charge_limit", "spoof_location"), Needs.ShizukuOrRoot,
                setOf(ToolSwitch.SensitiveOps), 60,
            ),
            ToolGroupInfo(
                "shell", "Terminal", "Shell commands as the app user, Shizuku or root.", listOf("shell"), Needs.Nothing,
                setOf(ToolSwitch.TerminalFiles), 90,
            ),
            // Tools from modules that do not depend on this one; listed by name so each gets its own switch.
            ToolGroupInfo(
                "background", "Background display",
                "Run a task in another app on a hidden screen, look and act there, and present the result on the main screen.",
                listOf("background_open_app", "background_look", "background_act", "background_close", "present_to_main_screen"),
                Needs.Root, setOf(ToolSwitch.DeviceDirect), 15,
            ),
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
