package atom.core.settings

/** The groups of the Settings page, in the order the page shows them. */
enum class SettingsGroup { Llm, Context, Tools, System, Assistant, CircleToSearch, General, Permissions, About }

/** One group as the page draws it: its rows in order. Keys are [SettingsRows] ids or feature routes. */
data class PlacedGroup(val group: SettingsGroup, val keys: List<String>)

/** Rows the Settings page draws itself (switches, dropdowns, status rows). Feature screens are routes instead. */
object SettingsRows {
    const val MODEL_PROVIDERS = "row:model_providers"
    const val REASONING_DEFAULT = "row:reasoning_default"
    const val DEFAULT_EFFORT = "row:default_effort"
    const val ON_DEVICE = "row:on_device"
    const val HARDWARE = "row:hardware"
    const val CLOUD_FALLBACK = "row:cloud_fallback"
    const val AUTO_COMPACTION = "row:auto_compaction"
    const val AUTO_TITLES = "row:auto_titles"
    const val BACKGROUND_DISPLAY = "row:background_display"
    const val CONFIRM_ACT = "row:confirm_act"
    const val SHELL_ACCESS = "row:shell_access"
    const val DIGITAL_ASSISTANT = "row:digital_assistant"
    const val POWER_KEY = "row:power_key"
    const val AUTO_ASSISTANT = "row:auto_assistant"
    const val CTS_GESTURE_BAR = "row:cts_gesture_bar"
    const val CTS_TWO_FINGER = "row:cts_two_finger"
    const val KEEP_SCREEN_ON = "row:keep_screen_on"
    const val LIVE_UPDATES = "row:live_updates"
    const val LANGUAGE = "row:language"
    const val OVERLAY_PERMISSION = "row:overlay_permission"
    const val ACCESSIBILITY = "row:accessibility"
    const val ENFORCE_ACCESSIBILITY = "row:enforce_accessibility"
    const val SETUP = "row:setup"
    const val VERSION = "row:version"
    const val CHECK_UPDATES = "row:check_updates"
    const val FEEDBACK = "row:feedback"
    const val SOURCE_CODE = "row:source_code"
    const val LICENSES = "row:licenses"

    fun isRow(key: String): Boolean = key.startsWith("row:")

    /**
     * Rows that only make sense once the Xposed framework has been seen on this phone. Automatically setting the
     * default assistant also works with root alone, so the screen shows it then too.
     */
    val NEEDS_FRAMEWORK: Set<String> = setOf(POWER_KEY, AUTO_ASSISTANT, CTS_GESTURE_BAR, CTS_TWO_FINGER, ENFORCE_ACCESSIBILITY)
}

/**
 * Where every Settings row lives. The order of the groups and of the classic rows follows the original Atom page;
 * rows for features it never had sit at the end of the closest group, so nothing the app can do loses its entry.
 * A feature entry that is not listed here still shows, at the end of the group its section maps to.
 */
object SettingsTree {
    val layout: List<Pair<SettingsGroup, List<String>>> = listOf(
        SettingsGroup.Llm to listOf(
            SettingsRows.MODEL_PROVIDERS,
            SettingsRows.REASONING_DEFAULT,
            SettingsRows.DEFAULT_EFFORT,
            // Newer: on-device models and what runs when they pause.
            SettingsRows.ON_DEVICE,
            SettingsRows.HARDWARE,
            SettingsRows.CLOUD_FALLBACK,
        ),
        SettingsGroup.Context to listOf(
            SettingsRows.AUTO_COMPACTION,
            SettingsRows.AUTO_TITLES,
            "extend/memory",
            "extend/subagents",
            "extend/skills",
            "extend/mcp",
            "automation/tasks",
            "characters/library",
            // Newer: notification history and event-triggered automations.
            "context/notifications",
            "automation/rules",
        ),
        SettingsGroup.Tools to listOf(
            "device/tools",
            "web/search",
            "web/images",
            SettingsRows.BACKGROUND_DISPLAY,
            "linux/home",
            // Newer tools.
            SettingsRows.CONFIRM_ACT,
            "web/gallery",
            "web/browser",
            "linux/workspace",
            "linux/terminal",
        ),
        SettingsGroup.System to listOf(
            "vdisplay/system",
            // Newer: shell tier, the undo journal and power tools.
            SettingsRows.SHELL_ACCESS,
            "device/changes",
            "device/power",
        ),
        SettingsGroup.Assistant to listOf(
            SettingsRows.DIGITAL_ASSISTANT,
            "overlay/settings",
            SettingsRows.POWER_KEY,
            SettingsRows.AUTO_ASSISTANT,
        ),
        SettingsGroup.CircleToSearch to listOf(
            SettingsRows.CTS_GESTURE_BAR,
            SettingsRows.CTS_TWO_FINGER,
        ),
        SettingsGroup.General to listOf(
            SettingsRows.KEEP_SCREEN_ON,
            "general/appearance",
            "voice/settings",
            SettingsRows.LANGUAGE,
            "general/backup",
            // Newer: audio chat and the Live Update status-bar chip.
            "voice/chat",
            SettingsRows.LIVE_UPDATES,
        ),
        SettingsGroup.Permissions to listOf(
            SettingsRows.OVERLAY_PERMISSION,
            SettingsRows.ACCESSIBILITY,
            SettingsRows.ENFORCE_ACCESSIBILITY,
            // The full Permissions page (also in the side pane) and the setup guide.
            "device/permissions",
            SettingsRows.SETUP,
        ),
        SettingsGroup.About to listOf(
            SettingsRows.VERSION,
            SettingsRows.CHECK_UPDATES,
            SettingsRows.FEEDBACK,
            SettingsRows.SOURCE_CODE,
            // Newer: the open-source notices.
            SettingsRows.LICENSES,
        ),
    )

    /** Feature entries the page replaces with its own rows (the screens stay reachable by route). */
    val replacedRoutes: Set<String> = setOf("general/about", "general/language")

    /** The group a feature entry joins when [layout] does not name it, by its section name. */
    fun fallbackGroup(section: String): SettingsGroup = when (section) {
        "Models" -> SettingsGroup.Llm
        "Context" -> SettingsGroup.Context
        "Tools" -> SettingsGroup.Tools
        "System" -> SettingsGroup.System
        else -> SettingsGroup.General
    }

    /**
     * The page's groups for the feature entries present ([entries]: route to section name, in feature order).
     * [showRow] hides built-in rows that do not apply (for example the framework rows before it was ever seen).
     * Groups that end up empty are left out.
     */
    fun place(entries: List<Pair<String, String>>, showRow: (String) -> Boolean = { true }): List<PlacedGroup> {
        val present = entries.map { it.first }.toSet()
        val listed = layout.flatMap { it.second }.toSet()
        val extra = entries
            .filter { (route, _) -> route !in listed && route !in replacedRoutes }
            .groupBy({ fallbackGroup(it.second) }, { it.first })
        return layout.mapNotNull { (group, keys) ->
            val rows = keys.filter { key -> if (SettingsRows.isRow(key)) showRow(key) else key in present } +
                extra[group].orEmpty().distinct()
            if (rows.isEmpty()) null else PlacedGroup(group, rows)
        }
    }
}
