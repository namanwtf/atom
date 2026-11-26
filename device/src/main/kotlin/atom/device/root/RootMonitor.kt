package atom.device.root

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import atom.device.Shells
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Keeps [RootAccess.status] (and with it [Shells.tier]) current without the user opening Permissions: one check in
 * the background at app start, one each time Atom comes back to the foreground while root is not granted. Whether a
 * check really runs is [RootProbePolicy]'s call, so a refused prompt is never repeated.
 */
object RootMonitor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var installed = false

    private var started = 0

    fun install(app: Application) {
        if (installed) return
        installed = true
        RootAccess.load(app)
        scope.launch { refresh(app, ProbeTrigger.AppStart) }
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                // Only the step from no visible activity to one counts: moving between Atom's own screens does not.
                if (started++ == 0) scope.launch { refresh(app, ProbeTrigger.Foreground) }
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /** Authorize / Check again: probes now (prompting if needed), then re-picks the shell tier. */
    suspend fun checkAgain(context: Context): RootStatus {
        val result = RootAccess.authorize(context)
        runCatching { Shells.detect(context, probeRoot = false) }
        return result
    }

    private suspend fun refresh(context: Context, trigger: ProbeTrigger) {
        runCatching { RootAccess.refresh(context, trigger) }
        // The tier follows the result (and picks up Shizuku started meanwhile); this never probes root itself.
        runCatching { Shells.detect(context, probeRoot = false) }
    }
}
