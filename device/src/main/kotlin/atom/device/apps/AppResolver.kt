package atom.device.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import atom.device.control.ControlError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Turns what the user calls an app into one installed package, or a clear error naming the candidates. */
object AppResolver {
    data class Resolved(val label: String, val packageName: String, val info: ApplicationInfo)

    /** Packages that must never be frozen or force-stopped: the phone would stop working. */
    private val protectedPackages = setOf(
        "android", "com.android.systemui", "com.android.phone", "com.android.settings", "com.android.providers.settings",
        "com.android.shell", "com.google.android.gms", "com.android.vending", "com.android.permissioncontroller",
        "com.google.android.permissioncontroller", "com.android.packageinstaller", "com.google.android.packageinstaller",
    )

    suspend fun resolve(context: Context, query: String): Resolved = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val q = query.trim()
        runCatching { pm.getApplicationInfo(q, PackageManager.MATCH_DISABLED_COMPONENTS) }.getOrNull()?.let {
            return@withContext Resolved(pm.getApplicationLabel(it).toString(), it.packageName, it)
        }
        val launchable = Apps.launchable(context).map { it.entry }
        val ranked = AppMatcher.rank(q, launchable)
        val best = ranked.firstOrNull()
        if (best != null) {
            val tied = ranked.drop(1).takeWhile { it.score == best.score }.take(4)
            if (tied.isNotEmpty() && best.score < 100) {
                throw ControlError("\"$q\" matches several apps: " + (listOf(best) + tied).joinToString("; ") { "${it.app.label}=${it.app.packageName}" } + ". Use the exact package name.")
            }
            val info = pm.getApplicationInfo(best.app.packageName, PackageManager.MATCH_DISABLED_COMPONENTS)
            return@withContext Resolved(best.app.label, best.app.packageName, info)
        }
        // Frozen apps have no launcher entry; search every installed package by label.
        val all = pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)
        val entries = all.map { AppEntry(pm.getApplicationLabel(it).toString(), it.packageName) }
        val hit = AppMatcher.best(q, entries) ?: throw ControlError("No installed app matches \"$q\".")
        Resolved(hit.app.label, hit.app.packageName, all.first { it.packageName == hit.app.packageName })
    }

    fun requireSafeToStop(context: Context, pkg: String) {
        if (pkg == context.packageName) throw ControlError("Atom will not freeze or stop itself.")
        if (pkg in protectedPackages) throw ControlError("$pkg is a core system component; freezing or stopping it would break the phone.")
        val home = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName
        if (pkg == home) throw ControlError("$pkg is the current home screen app; freezing or stopping it would leave no launcher.")
    }
}
