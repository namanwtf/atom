package atom.app.ui

import android.content.Context
import atom.device.caps.CapStatus
import atom.device.caps.Capabilities
import atom.device.caps.PermissionRows

/**
 * The sidebar's "Permissions need attention" hint and badge. It only watches the rows Atom's core features need
 * (accessibility and the floating window); everything else is asked when a feature first uses it, so a missing
 * optional permission is never a nag.
 */
object PermissionAttention {
    /** Capability ids on the Permissions page. */
    val WATCHED = PermissionRows.CORE.map { it.first }

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
