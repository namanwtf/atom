package atom.device.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Launchable apps, via the launcher-intent `<queries>` entry in the manifest (no QUERY_ALL_PACKAGES).
 * Labels are cached briefly: loading ~200 labels costs real time and the list rarely changes mid-task.
 */
internal object Apps {
    private const val CACHE_MS = 5 * 60_000L

    data class Launchable(val entry: AppEntry, val activity: String)

    @Volatile
    private var cache: Pair<Long, List<Launchable>>? = null

    fun launchable(context: Context, fresh: Boolean = false): List<Launchable> {
        val now = SystemClock.elapsedRealtime()
        cache?.let { (at, list) -> if (!fresh && now - at < CACHE_MS) return list }
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map { ri ->
                val label = ri.loadLabel(pm).toString().trim().ifEmpty { ri.activityInfo.packageName }
                Launchable(AppEntry(label, ri.activityInfo.packageName), ri.activityInfo.name)
            }
            .distinctBy { it.entry.packageName to it.activity }
            .sortedBy { it.entry.label.lowercase() }
        cache = now to list
        return list
    }

    fun launchIntent(app: Launchable): Intent =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(app.entry.packageName, app.activity))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}
