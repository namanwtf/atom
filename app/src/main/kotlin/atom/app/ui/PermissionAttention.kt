package atom.app.ui

import android.content.Context
import atom.device.caps.CapStatus
import atom.device.caps.Capabilities

/**
 * The sidebar's "Permissions need attention" hint and badge. Like the old app it only watches the four items
 * the agent cannot do without: running in the background, the floating window, accessibility and notifications.
 */
object PermissionAttention {
    /** Capability ids on the Permissions page. */
    val WATCHED = listOf("battery", "overlay", "accessibility", "notifications_post")

    fun needed(context: Context): Boolean = runCatching {
        val rows = Capabilities.all.associateBy { it.id }
        WATCHED.any { id ->
            val cap = rows[id] ?: return@any false
            if (!cap.applicable(context)) return@any false
            val status = cap.state(context).status
            status != CapStatus.Available && status != CapStatus.Unsupported
        }
    }.getOrDefault(false)
}
