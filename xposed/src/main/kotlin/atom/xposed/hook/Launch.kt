package atom.xposed.hook

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.UserHandle
import atom.xposed.HookConfig
import atom.xposed.PowerKeyTarget

/** Starting things from inside system_server or SystemUI on behalf of the current user. */
internal object Launch {
    const val GEMINI_APP = "com.google.android.apps.bard"

    /** Opens the power-key [target]. Returns false when nothing could be started (the caller then lets the ROM act). */
    fun target(env: HookEnv, context: Context, target: PowerKeyTarget): Boolean = when (target) {
        PowerKeyTarget.SystemDefault -> false
        PowerKeyTarget.Atom -> firstStarted(
            env, context,
            Intent(Intent.ACTION_ASSIST).setPackage(env.modulePackage),
            context.packageManager.getLaunchIntentForPackage(env.modulePackage),
        )
        PowerKeyTarget.Gemini -> firstStarted(
            env, context,
            Intent(Intent.ACTION_ASSIST).setPackage(HookConfig.GOOGLE_APP),
            Intent(Intent.ACTION_VOICE_COMMAND).setPackage(HookConfig.GOOGLE_APP),
            context.packageManager.getLaunchIntentForPackage(GEMINI_APP),
        )
    }

    private fun firstStarted(env: HookEnv, context: Context, vararg intents: Intent?): Boolean {
        for (intent in intents) {
            intent ?: continue
            val resolved = try {
                context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
            } catch (t: Throwable) {
                false
            }
            if (!resolved) continue
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            if (startAsCurrentUser(env, context, intent)) return true
        }
        env.logOnce("launch-none", "No activity found for the power-key target")
        return false
    }

    fun startAsCurrentUser(env: HookEnv, context: Context, intent: Intent): Boolean {
        val token = Binder.clearCallingIdentity()
        try {
            val current = try {
                UserHandle::class.java.getField("CURRENT").get(null) as UserHandle
            } catch (t: Throwable) {
                null
            }
            if (current != null) {
                try {
                    Context::class.java.getMethod("startActivityAsUser", Intent::class.java, Bundle::class.java, UserHandle::class.java)
                        .invoke(context, intent, null, current)
                    return true
                } catch (t: Throwable) {
                    env.logOnce("startAsUser", "startActivityAsUser unavailable, using startActivity", t)
                }
            }
            context.startActivity(intent)
            return true
        } catch (t: Throwable) {
            env.logOnce("start-${intent.`package`}", "Could not start ${intent.action}", t)
            return false
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }
}
