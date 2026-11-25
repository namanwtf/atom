package atom.xposed.hook

import android.content.Context
import io.github.libxposed.api.XposedInterface

/**
 * system_server: power key now (key handling is set up before boot completes), the rest once the activity manager
 * has finished booting (system services and settings are ready then).
 */
internal object SystemServerHooks {
    @Volatile
    private var booted = false

    fun install(env: HookEnv, loader: ClassLoader) {
        env.guard("power") { PowerKeyHooks.install(env, loader) }
        env.guard("boot") {
            val ams = Class.forName("com.android.server.am.ActivityManagerService", false, loader)
            val n = env.hookDeclared(ams, "finishBooting", "boot", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    val context = chain.thisObject?.let { Reflect.get(it, "mContext") } as? Context
                    if (context != null) onBooted(env, context)
                    return result
                }
            })
            if (n == 0) env.logOnce("boot-missing", "finishBooting not found; boot-time hooks are off")
        }
    }

    private fun onBooted(env: HookEnv, context: Context) {
        if (booted) return
        booted = true
        env.guard("role") { AssistantRoleKeeper.install(env, context) }
        env.guard("a11y") { AccessibilityGuard.install(env, context) }
        env.guard("gestures") { GestureTriggers.install(env, context) }
    }
}
