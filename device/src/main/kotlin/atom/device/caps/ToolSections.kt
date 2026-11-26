package atom.device.caps

/** The Tool list groups, in the order the page shows them. */
enum class ToolSection(val title: String) {
    Screen("Screens and controls"),
    Text("Text and clipboard"),
    Web("Web tools"),
    Apps("Applications and Systems"),
    BuiltInApps("System and built-in apps"),
    Personal("Personal context"),
    Vision("Document vision"),
    Memory("Memory"),
    Terminal("Terminal and files"),
}

/** One tool row: its name and the catalog card it comes from (for the description and what it needs). */
data class ToolRow(val tool: String, val group: ToolGroupInfo) {
    /** "read_screen" → "Read screen". */
    val title: String get() = tool.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

data class ToolSectionRows(val section: ToolSection, val rows: List<ToolRow>, val enabled: Int) {
    val total: Int get() = rows.size
}

/**
 * Groups the catalog cards into the Tool list sections: one switch per tool, sections in [ToolSection] order, cards
 * inside a section by their catalog order. Cards newer than the original list join the closest section.
 */
object ToolSections {
    private val byGroup: Map<String, ToolSection> = mapOf(
        "screen" to ToolSection.Screen,
        "text" to ToolSection.Text,
        "web" to ToolSection.Web,
        "browser" to ToolSection.Web,
        "image" to ToolSection.Web,
        "sites" to ToolSection.Web,
        "apps" to ToolSection.Apps,
        "background" to ToolSection.Screen,
        "battery" to ToolSection.Apps,
        "more_device" to ToolSection.Apps,
        "toggles" to ToolSection.Apps,
        "undo" to ToolSection.Apps,
        "system_ops" to ToolSection.Apps,
        "logs" to ToolSection.Apps,
        "power" to ToolSection.Apps,
        "apps_ops" to ToolSection.BuiltInApps,
        "context" to ToolSection.Personal,
        "context_sensitive" to ToolSection.Personal,
        "context_root" to ToolSection.Personal,
        "vision" to ToolSection.Vision,
        "memory" to ToolSection.Memory,
        "shell" to ToolSection.Terminal,
        "terminal" to ToolSection.Terminal,
        "files" to ToolSection.Terminal,
    )

    /** Needs only root or Shizuku can meet (hidden in the "Current device" view while unmet). */
    fun needsShell(needs: Needs): Boolean = needs == Needs.Root || needs == Needs.ShizukuOrRoot

    fun sectionOf(group: ToolGroupInfo): ToolSection = byGroup[group.id] ?: ToolSection.Apps

    /**
     * The sections for [groups] that pass [include] (for example "works on this phone"); a tool listed by two cards
     * shows once, under the first. Empty sections are left out.
     */
    fun build(groups: List<ToolGroupInfo>, isOn: (String) -> Boolean, include: (ToolGroupInfo) -> Boolean = { true }): List<ToolSectionRows> {
        val seen = HashSet<String>()
        val rowsBySection = groups.sortedBy { it.order }
            .filter(include)
            .flatMap { g -> g.tools.filter { seen.add(it) }.map { ToolRow(it, g) } }
            .groupBy { sectionOf(it.group) }
        return ToolSection.entries.mapNotNull { section ->
            val rows = rowsBySection[section].orEmpty()
            if (rows.isEmpty()) null else ToolSectionRows(section, rows, rows.count { isOn(it.tool) })
        }
    }

    /** Only [chip]'s section, or all of them when [chip] is null ("All"). */
    fun filter(sections: List<ToolSectionRows>, chip: ToolSection?): List<ToolSectionRows> =
        if (chip == null) sections else sections.filter { it.section == chip }

    /** "All n/m": enabled and total tools across [sections]. */
    fun totals(sections: List<ToolSectionRows>): Pair<Int, Int> = sections.sumOf { it.enabled } to sections.sumOf { it.total }
}
