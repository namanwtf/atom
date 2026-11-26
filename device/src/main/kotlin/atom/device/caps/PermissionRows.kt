package atom.device.caps

import atom.device.root.RootStatus

/** How a status label is coloured on the Permissions page. */
enum class StatusTone { Good, Warning, Bad, Muted }

/**
 * The Permissions page layout. Two groups, so the page reads as a short list of what Atom needs plus a list of
 * what it may ask for later: "Needed for core features" (accessibility and the floating window) and
 * "Optional — asked only when used" (everything else, each requested at the moment a feature first needs it).
 */
object PermissionRows {
    /** Stands for the "Root and system enhancements" row, which opens that page instead of a system setting. */
    const val ROOT = "root"

    /** The rows Atom's core features need: it operates the phone and shows its floating window with these. */
    val CORE: List<Pair<String, String>> = listOf(
        "accessibility" to "Accessibility permissions",
        "overlay" to "Floating window permissions",
    )

    /** Optional rows with their titles, in page order; any other registered row follows them. */
    val OPTIONAL: List<Pair<String, String>> = listOf(
        "notifications_post" to "Background task notifications",
        "microphone" to "Microphone",
        "location" to "Location permissions",
        "calendar" to "Calendar access",
        "notification_listener" to "Notification access",
        "usage_access" to "Usage access",
        "battery" to "Background running permission",
        "exact_alarms" to "Exact alarms",
        "nearby" to "Local network access",
        "app_list" to "Application list reading",
        "dnd_access" to "Do Not Disturb access",
        ROOT to "Root and system enhancements",
    )

    private val coreIds: Set<String> = CORE.map { it.first }.toSet()
    private val listedIds: Set<String> = coreIds + OPTIONAL.map { it.first }

    fun isCore(id: String): Boolean = id in coreIds

    /** The core rows that exist here ([present] capability ids). */
    fun core(present: Set<String>): List<Pair<String, String>> = CORE.filter { (id, _) -> id in present }

    /** The titled optional rows that exist here; the root row is always there. */
    fun optional(present: Set<String>): List<Pair<String, String>> = OPTIONAL.filter { (id, _) -> id == ROOT || id in present }

    /** Optional rows without a fixed place above (contacts, SMS, all files...), in registration order. */
    fun others(ids: List<String>): List<String> = ids.filterNot { it in listedIds }

    /** [optional] rows (root, mock location...) are grey rather than red while they are not set up. */
    fun tone(status: CapStatus, optional: Boolean = false): StatusTone = when (status) {
        CapStatus.Available -> StatusTone.Good
        CapStatus.Missing, CapStatus.NotEnabled -> if (optional) StatusTone.Muted else StatusTone.Bad
        CapStatus.Issue, CapStatus.Checking -> StatusTone.Warning
        CapStatus.Unsupported -> StatusTone.Muted
    }

    /** The status text: an optional row that is not set up reads "When needed" rather than "Missing". */
    fun label(status: CapStatus, optional: Boolean = false): String =
        if (optional && (status == CapStatus.Missing || status == CapStatus.NotEnabled)) WHEN_NEEDED else status.label

    const val WHEN_NEEDED = "When needed"

    /** The root row: available once root is granted, otherwise not enabled (it is optional). */
    fun rootStatus(root: RootStatus): CapStatus = when (root) {
        RootStatus.Granted -> CapStatus.Available
        RootStatus.Checking -> CapStatus.Checking
        else -> CapStatus.NotEnabled
    }
}
