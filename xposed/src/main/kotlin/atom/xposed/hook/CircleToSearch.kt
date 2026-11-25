package atom.xposed.hook

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock

/**
 * Opens Circle to Search from system_server.
 *
 *  1. Android 15+: the platform contextual-search service (`ContextualSearchManager.startContextualSearch`). It
 *     exists when the ROM configures a contextual-search package (Google-partner ROMs do); system_server holds the
 *     permission it checks.
 *  2. Otherwise: an assistant session with the navigation-handle invocation type, which the Google app turns into
 *     Circle to Search when it is the default assistant and considers the device eligible (see GoogleEligibility).
 */
internal object CircleToSearch {
    private const val CS_MANAGER = "android.app.contextualsearch.ContextualSearchManager"
    private const val NAV_HANDLE_ENTRY_FALLBACK = 1
    private const val NAV_HANDLE_INVOCATION_FALLBACK = 7

    // VoiceInteractionSession.SHOW_WITH_ASSIST | SHOW_WITH_SCREENSHOT | SHOW_SOURCE_ASSIST_GESTURE
    private const val SHOW_FLAGS = 1 or 2 or 4

    fun trigger(env: HookEnv, context: Context): Boolean {
        val token = Binder.clearCallingIdentity()
        try {
            if (viaContextualSearch(env, context)) return true
            if (viaAssistant(env)) return true
            env.logOnce("cts-none", "Circle to Search could not be started on this ROM")
            return false
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun viaContextualSearch(env: HookEnv, context: Context): Boolean = try {
        val cls = Class.forName(CS_MANAGER)
        val manager = context.getSystemService(cls)
        if (manager == null) {
            false
        } else {
            val entry = Reflect.staticInt(CS_MANAGER, "ENTRYPOINT_LONG_PRESS_NAV_HANDLE") ?: NAV_HANDLE_ENTRY_FALLBACK
            cls.getMethod("startContextualSearch", Int::class.javaPrimitiveType).invoke(manager, entry)
            env.logOnce("cts-cs", "Circle to Search via contextual search service")
            true
        }
    } catch (t: Throwable) {
        env.logOnce("cts-cs-fail", "Contextual search service unavailable", t)
        false
    }

    private fun viaAssistant(env: HookEnv): Boolean = try {
        val service = Reflect.service("voiceinteraction") ?: error("no voiceinteraction service")
        val method = service.javaClass.methods.firstOrNull { it.name == "showSessionForActiveService" }
            ?: error("no showSessionForActiveService")
        val type = Reflect.staticInt("com.android.internal.app.AssistUtils", "INVOCATION_TYPE_NAV_HANDLE_LONG_PRESS")
            ?: NAV_HANDLE_INVOCATION_FALLBACK
        val args = Bundle().apply {
            putInt("invocation_type", type)
            putLong("invocation_time_ms", SystemClock.elapsedRealtime())
        }
        // (Bundle args, int sourceFlags, [String attributionTag,] IVoiceInteractionSessionShowCallback, IBinder token)
        val params = method.parameterTypes
        val values = arrayOfNulls<Any>(params.size)
        var intSet = false
        params.forEachIndexed { i, p ->
            values[i] = when {
                p == Bundle::class.java -> args
                p == Int::class.javaPrimitiveType && !intSet -> SHOW_FLAGS.also { intSet = true }
                p == Int::class.javaPrimitiveType -> 0
                p == Boolean::class.javaPrimitiveType -> false
                p == Long::class.javaPrimitiveType -> 0L
                else -> null
            }
        }
        val ok = method.invoke(service, *values)
        env.logOnce("cts-assist", "Circle to Search via assistant session")
        ok != false
    } catch (t: Throwable) {
        env.logOnce("cts-assist-fail", "Assistant session unavailable", t)
        false
    }
}
