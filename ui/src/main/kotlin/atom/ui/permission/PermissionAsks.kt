package atom.ui.permission

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * One just-in-time request: a feature or tool needs [permissions] (runtime) or a special access page ([settings])
 * right now. The app shows it once, in context, with [reason] as the single explaining sentence.
 */
data class PermissionAsk(
    /** Stable id ("contacts", "notifications"...); each is asked at most once. */
    val key: String,
    /** What the user is asked to allow, e.g. "Contacts". */
    val name: String,
    /** One short sentence: why Atom needs it now. */
    val reason: String,
    val permissions: List<String> = emptyList(),
    /** Opened instead of a system prompt when [permissions] is empty (special access). */
    val settings: Intent? = null,
    val raisedAt: Long = System.currentTimeMillis(),
)

/**
 * The single place that raises permission prompts. Nothing asks at first launch or on the setup screen: a tool or
 * feature calls [request] when it first needs something, the app's host shows one in-context prompt, and the answer
 * is remembered so the same permission is never asked again from here (the Permissions page can still grant it).
 */
object PermissionAsks {
    private const val PREFS = "atom4_permission_asks"

    /** Asks older than this are dropped instead of shown (the user moved on). */
    const val STALE_MS = 10 * 60_000L

    private val queue = MutableStateFlow<List<PermissionAsk>>(emptyList())

    /** Asks waiting for the app to show them, oldest first. */
    val pending: StateFlow<List<PermissionAsk>> = queue.asStateFlow()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun granted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Whether the user already answered the prompt for [key] (allowed, denied or "Not now"). */
    fun wasAsked(context: Context, key: String): Boolean = runCatching { prefs(context).getBoolean(key, false) }.getOrDefault(false)

    /**
     * Queues [ask] unless it was answered before. Returns true when a prompt is (or already was) waiting, false
     * when the user answered earlier and must not be asked again.
     */
    fun request(context: Context, ask: PermissionAsk): Boolean {
        if (ask.permissions.isNotEmpty()) {
            val missing = ask.permissions.filterNot { granted(context, it) }
            if (missing.isEmpty()) return true
            // Each runtime permission is prompted for at most once from here, whichever feature asks.
            if (missing.all { wasAsked(context, it) }) return false
        } else if (wasAsked(context, ask.key)) {
            return false
        }
        queue.update { list -> if (list.any { it.key == ask.key }) list else list + ask }
        return true
    }

    /** The prompt for [ask] was answered or dismissed: remember it and take it off the queue. */
    fun answered(context: Context, ask: PermissionAsk) {
        runCatching {
            val edit = prefs(context).edit()
            edit.putBoolean(ask.key, true)
            ask.permissions.forEach { edit.putBoolean(it, true) }
            edit.apply()
        }
        queue.update { list -> list.filterNot { it.key == ask.key } }
    }

    /** Drops [ask] without remembering an answer (it went stale before it could be shown). */
    fun drop(ask: PermissionAsk) = queue.update { list -> list.filterNot { it.key == ask.key } }

    /**
     * What a tool returns when it lacks a permission: "needs X — ask the user" while a prompt is up, or a plain
     * explanation (no new prompt) when the user already said no.
     */
    fun toolMessage(context: Context, ask: PermissionAsk, what: String): String =
        if (request(context, ask)) {
            "$what needs the ${ask.name} permission. Atom is showing the user a one-time prompt (\"${ask.reason}\"). " +
                "Ask the user to allow it, then try again."
        } else {
            "$what needs the ${ask.name} permission, which the user has not allowed. Tell the user this part is unavailable " +
                "and that they can allow it on Atom's Permissions page; do not ask for it again in this task."
        }

    /**
     * Notifications, asked the first time something will post them from the background (a running task, a
     * scheduled task, a download). No-op below Android 13 or when already allowed.
     */
    fun notifications(context: Context, reason: String) {
        if (Build.VERSION.SDK_INT < 33) return
        val p = "android.permission.POST_NOTIFICATIONS"
        if (granted(context, p)) return
        request(context, PermissionAsk("notifications", "Notifications", reason, listOf(p)))
    }
}
