package atom.xposed.hook

import android.content.Intent
import io.github.libxposed.api.XposedInterface

/**
 * HyperOS / ColorOS / MagicOS: when Circle to Search is chosen for a gesture, the ROM's own screen search (Super
 * XiaoAI, Breeno screen recognition, YOYO) must not open on top of it. In the launcher and SystemUI processes this
 * drops activity and service starts aimed at those apps when the call comes from a navigation-gesture or
 * multi-finger class. The Circle to Search launch itself comes from [GestureTriggers] in system_server.
 *
 * Cost: a package-name comparison per activity/service start in these two processes; the stack is only inspected
 * when the package matches.
 */
internal object OemScreenSearch {
    private val screenSearchPackages = setOf(
        "com.miui.voiceassist",
        "com.xiaomi.aiasst.vision",
        "com.coloros.colordirectservice",
        "com.heytap.speechassist",
        "com.hihonor.magicvoice",
        "com.hihonor.yoyo",
    )

    private val gestureFrame = Regex("NavStub|GestureStub|NavigationHandle|NavHandle|HomeHandle|GestureLine|TwoFinger|MultiFinger|DoubleFinger", RegexOption.IGNORE_CASE)

    fun install(env: HookEnv, loader: ClassLoader) {
        var n = 0
        env.guard("oem-instrumentation") {
            n += env.hookDeclared(Class.forName("android.app.Instrumentation", false, loader), "execStartActivity", "oem-start", Suppressor(env))
        }
        env.guard("oem-context") {
            val ctx = Class.forName("android.app.ContextImpl", false, loader)
            n += env.hookDeclared(ctx, "startService", "oem-service", Suppressor(env))
            n += env.hookDeclared(ctx, "startForegroundService", "oem-fgs", Suppressor(env))
        }
        env.logOnce("oem-installed", "OEM screen-search suppression hooks: $n")
    }

    private class Suppressor(private val env: HookEnv) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val intent = chain.args.firstOrNull { it is Intent } as? Intent ?: return chain.proceed()
            val pkg = intent.`package` ?: intent.component?.packageName ?: return chain.proceed()
            if (pkg !in screenSearchPackages) return chain.proceed()
            val cfg = env.config
            if (!cfg.anyCircleToSearch) return chain.proceed()
            val fromGesture = Throwable().stackTrace.any { gestureFrame.containsMatchIn(it.className) }
            if (!fromGesture) return chain.proceed()
            env.logOnce("oem-suppressed-$pkg", "Suppressed $pkg screen search in favour of Circle to Search")
            return null
        }
    }
}
