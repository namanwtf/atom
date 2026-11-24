package atom.voice

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Tier 1, zero cost: Atom as the system digital assistant (long-press power / corner swipe). Uses no microphone
 * and no background work; the system binds Atom only when the gesture fires.
 */
object AssistantRole {
    /** Boolean extra set on the activity launched by the assist gesture. */
    const val EXTRA_FROM_ASSIST = "atom.voice.EXTRA_FROM_ASSIST"

    fun isHeld(context: Context): Boolean {
        val rm = context.getSystemService(RoleManager::class.java) ?: return false
        return rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)
    }

    /**
     * An intent that lets the user make Atom the digital assistant, or null when it already is (or the device has
     * no assistant role).
     *
     * ROLE_ASSISTANT is declared `requestable="false"` by PermissionController, so
     * `RoleManager.createRequestRoleIntent(ROLE_ASSISTANT)` is auto-denied. The only legitimate path is the
     * system "Digital assistant app" picker, which this returns.
     */
    fun requestIntent(context: Context): Intent? {
        val rm = context.getSystemService(RoleManager::class.java) ?: return null
        if (!rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) || rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)) return null
        val pm = context.packageManager
        return listOf(Settings.ACTION_VOICE_INPUT_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            .map { Intent(it) }
            .firstOrNull { it.resolveActivity(pm) != null || pm.queryIntentActivities(it, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty() }
    }
}
