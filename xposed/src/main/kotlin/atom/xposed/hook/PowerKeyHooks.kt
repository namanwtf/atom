package atom.xposed.hook

import android.content.Context
import atom.xposed.PowerKeyTarget
import io.github.libxposed.api.XposedInterface

/**
 * Power-key long-press target (system_server). With a target other than System default, the long press opens that
 * target instead of what the ROM would do, and is marked handled so the key-up is not treated as a short press.
 *
 *  - AOSP and most ROMs: `PhoneWindowManager.powerLongPress` (and OEM subclasses that override it).
 *  - ROMs that route the press straight to the assistant: `launchAssistAction` with the power-button invocation type.
 *  - ColorOS / HyperOS subclasses are looked up by their known class names; anything missing is skipped silently.
 *
 * With System default the hookers just proceed, so the hook is inert.
 */
internal object PowerKeyHooks {
    private val policyClasses = listOf(
        "com.android.server.policy.PhoneWindowManager",
        // HyperOS / MIUI
        "com.android.server.policy.BaseMiuiPhoneWindowManager",
        "com.android.server.policy.MiuiPhoneWindowManager",
        // ColorOS / OxygenOS / realme UI
        "com.android.server.policy.OplusPhoneWindowManager",
        "com.android.server.policy.PhoneWindowManagerExtImpl",
        // MagicOS
        "com.android.server.policy.HwPhoneWindowManager",
        "com.android.server.policy.HnPhoneWindowManager",
    )

    fun install(env: HookEnv, loader: ClassLoader) {
        val powerButtonType = Reflect.staticInt("com.android.internal.app.AssistUtils", "INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS", loader)
        var hooked = 0
        for (name in policyClasses) {
            val cls = env.loadClass(loader, name) ?: continue
            hooked += env.hookDeclared(cls, "powerLongPress", "power", LongPressHooker(env))
            if (powerButtonType != null) hooked += env.hookDeclared(cls, "launchAssistAction", "assist", AssistHooker(env, powerButtonType))
        }
        env.logOnce("power-installed", "Power-key hooks installed: $hooked")
    }

    /** Consumes the long press when a target is chosen; returns true if it did. */
    private fun takeOver(env: HookEnv, policy: Any?): Boolean {
        val target = env.config.powerKeyTarget
        if (target == PowerKeyTarget.SystemDefault || policy == null) return false
        val context = Reflect.get(policy, "mContext") as? Context ?: return false
        if (!Launch.target(env, context, target)) return false
        Reflect.set(policy, "mPowerKeyHandled", true)
        env.logOnce("power-took-over", "Power long press opened $target")
        return true
    }

    private class LongPressHooker(private val env: HookEnv) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? =
            if (takeOver(env, chain.thisObject)) null else chain.proceed()
    }

    /** Only the power-button invocation is redirected; the other assistant entry points are left alone. */
    private class AssistHooker(private val env: HookEnv, private val powerButtonType: Int) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val args = chain.args
            val type = args.lastOrNull { it is Int } as? Int
            if (type == powerButtonType && takeOver(env, chain.thisObject)) return null
            return chain.proceed()
        }
    }
}
