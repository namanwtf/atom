package atom.device.control

/** How a state change ended. Only [Verified] may be described as success. */
enum class ChangeStatus { Verified, Unchanged, Unconfirmed, Failed }

data class Change(
    val label: String,
    val before: String?,
    val target: String,
    val after: String?,
    val status: ChangeStatus,
    val via: String? = null,
    val note: String? = null,
) {
    val text: String get() = ChangeReport.format(this)
}

/** The one place before → after lines are worded, so every tool reports changes the same honest way. */
object ChangeReport {
    fun format(c: Change): String {
        val before = c.before ?: "unknown"
        val via = c.via?.let { " via $it" }.orEmpty()
        val line = when (c.status) {
            ChangeStatus.Unchanged -> "${c.label}: already ${c.target}; nothing changed"
            ChangeStatus.Verified -> "${c.label}: $before → ${c.after ?: c.target} (verified$via)"
            ChangeStatus.Failed -> "${c.label}: still $before; could not set ${c.target}"
            ChangeStatus.Unconfirmed -> when (c.after) {
                null -> "${c.label}: $before → ${c.target} requested$via, unconfirmed (the new state could not be read back)"
                c.before -> "${c.label}: $before → ${c.target} requested$via, unconfirmed (it still reads $before)"
                else -> "${c.label}: $before → ${c.target} requested$via, unconfirmed (it now reads ${c.after})"
            }
        }
        return if (c.note.isNullOrBlank()) line else "$line. ${c.note}"
    }

    fun status(before: String?, target: String, after: String?): ChangeStatus = when {
        after == target -> ChangeStatus.Verified
        else -> ChangeStatus.Unconfirmed
    }
}
