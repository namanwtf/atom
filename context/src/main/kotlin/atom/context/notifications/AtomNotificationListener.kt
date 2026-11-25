package atom.context.notifications

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * "Atom notification history". Does work only when the system posts a notification: one row per new or changed
 * notification, written on a worker thread that exits when idle. Nothing is uploaded or synced.
 */
class AtomNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_GROUP_SUMMARY) != 0) return
        val record = toRecord(this, sbn) ?: return
        val app = applicationContext
        writer.execute { runCatching { Notifications.history(app).record(record) } }
        postedHook?.let { runCatching { it.onPosted(record) } }
    }

    /** Another consumer of new notifications (automation triggers). Called on the main thread; must return fast. */
    fun interface PostedHook {
        fun onPosted(record: NotificationRecord)
    }

    companion object {
        @Volatile
        var instance: AtomNotificationListener? = null
            private set

        /** Set once by the app. */
        @Volatile
        var postedHook: PostedHook? = null

        fun component(context: Context) = ComponentName(context, AtomNotificationListener::class.java)

        private val labels = HashMap<String, String>()

        private val writer = ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS, LinkedBlockingQueue())

        fun label(context: Context, pkg: String): String = synchronized(labels) {
            labels.getOrPut(pkg) {
                runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }
                    .getOrDefault(pkg)
            }
        }

        fun toRecord(context: Context, sbn: StatusBarNotification): NotificationRecord? {
            val n = sbn.notification ?: return null
            val e = n.extras ?: return null
            val title = (e.getCharSequence(Notification.EXTRA_TITLE_BIG) ?: e.getCharSequence(Notification.EXTRA_TITLE))?.toString().orEmpty()
            val messages = messages(e)
            val body = when {
                messages.isNotEmpty() -> messages.takeLast(3).joinToString("\n")
                else -> (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
            }
            val lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n") { it.toString() }
            val text = if (body.isBlank() && lines != null) lines else body
            if (title.isBlank() && text.isBlank()) return null
            return NotificationRecord(
                postedAt = sbn.postTime,
                packageName = sbn.packageName,
                appLabel = label(context, sbn.packageName),
                key = sbn.key,
                title = title.take(300),
                text = text.take(2_000),
                conversation = e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString(),
                category = n.category,
            )
        }

        private fun messages(e: android.os.Bundle): List<String> {
            val arr: Array<out Parcelable>? = if (Build.VERSION.SDK_INT >= 33) {
                e.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java)
            } else {
                @Suppress("DEPRECATION")
                e.getParcelableArray(Notification.EXTRA_MESSAGES)
            }
            return arr.orEmpty().mapNotNull { p ->
                val b = p as? android.os.Bundle ?: return@mapNotNull null
                val text = b.getCharSequence("text")?.toString() ?: return@mapNotNull null
                val sender = b.getCharSequence("sender")?.toString()
                if (sender.isNullOrBlank()) text else "$sender: $text"
            }
        }
    }
}

object Notifications {
    @Volatile
    private var history: NotificationHistory? = null

    fun history(context: Context): NotificationHistory = history ?: synchronized(this) {
        history ?: NotificationHistory(SqliteNotificationStore(context)).also { history = it }
    }

    fun accessGranted(context: Context): Boolean {
        val nm = context.getSystemService(android.app.NotificationManager::class.java) ?: return false
        return runCatching { nm.isNotificationListenerAccessGranted(AtomNotificationListener.component(context)) }.getOrDefault(false)
    }
}
