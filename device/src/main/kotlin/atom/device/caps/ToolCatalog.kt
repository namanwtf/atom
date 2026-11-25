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
        )
    }
}
