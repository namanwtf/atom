package atom.xposed

import atom.xposed.hook.GoogleEligibility
import atom.xposed.hook.HookEnv
import atom.xposed.hook.OemScreenSearch
import atom.xposed.hook.SystemServerHooks
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * Atom's LSPosed entry (modern libxposed API, declared in META-INF/xposed/java_init.list). It does nothing unless the
 * user enabled the module and a scope in LSPosed, and every hook is passive: it reacts to calls the system makes
 * anyway and reads the user's settings from the framework's remote preferences.
 */
class AtomXposedModule : XposedModule() {
    private var process = "?"

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        process = param.processName
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        val env = HookEnv(this, "system_server")
        env.guard("system_server") { SystemServerHooks.install(env, param.classLoader) }
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (!param.isFirstPackage) return
        val env = HookEnv(this, process)
        when (param.packageName) {
            HookConfig.GOOGLE_APP -> env.guard("gsa") { GoogleEligibility.install(env, param.classLoader) }
            in OEM_GESTURE_HOSTS -> env.guard("oem") { OemScreenSearch.install(env, param.classLoader) }
        }
    }

    private companion object {
        /** Processes that host the navigation gesture handle on the ROMs with their own screen search. */
        val OEM_GESTURE_HOSTS = setOf("com.android.systemui", "com.miui.home", "com.android.launcher", "com.hihonor.android.launcher")
    }
}
