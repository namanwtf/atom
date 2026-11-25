package atom.device.caps

import atom.device.root.RootStatus

/** How a status label is coloured on the Permissions page. */
enum class StatusTone { Good, Warning, Bad, Muted }

/**
 * The Permissions page layout: the twelve classic rows in their order, then everything else the newer app can use
 * (microphone, contacts, SMS, Shizuku and so on) below them.
 */
object PermissionRows {
    /** Stands for the "Root and system enhancements" row, which opens that page instead of a system setting. */
    const val ROOT = "root"

    /** Capability id to row title, in page order. */
    val CLASSIC: List<Pair<String, String>> = listOf(
        "battery" to "Background running permission",
        "overlay" to "Floating window permissions",
        "app_list" to "Application list reading",
        "nearby" to "Local network access",
        "calendar" to "Calendar access",
        "dnd_access" to "Do Not Disturb access",
        "location" to "Location permissions",
        "notification_listener" to "Notification access",
        "usage_access" to "Usage access",
        "accessibility" to "Accessibility permissions",
        "notifications_post" to "Background task notifications",
        ROOT to "Root and system enhancements",
    )

    private val classicIds: Set<String> = CLASSIC.map { it.first }.toSet()

    fun isClassic(id: String): Boolean = id in classicIds

    /** The classic rows that exist here ([present] capability ids; the root row is always there). */
    fun classic(present: Set<String>): List<Pair<String, String>> = CLASSIC.filter { (id, _) -> id == ROOT || id in present }

    /** Capabilities that are not one of the classic rows, in registration order. */
    fun others(ids: List<String>): List<String> = ids.filterNot(::isClassic)

    /** [optional] rows (root, mock location...) are grey rather than red while they are not set up. */
    fun tone(status: CapStatus, optional: Boolean = false): StatusTone = when (status) {
        CapStatus.Available -> StatusTone.Good
        CapStatus.Missing, CapStatus.NotEnabled -> if (optional) StatusTone.Muted else StatusTone.Bad
        CapStatus.Issue, CapStatus.Checking -> StatusTone.Warning
        CapStatus.Unsupported -> StatusTone.Muted
    }

    /** The root row: available once root is granted, otherwise not enabled (it is optional). */
    fun rootStatus(root: RootStatus): CapStatus = when (root) {
        RootStatus.Granted -> CapStatus.Available
        RootStatus.Checking -> CapStatus.Checking
        else -> CapStatus.NotEnabled
    }
}
