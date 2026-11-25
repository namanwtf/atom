package atom.automation.system

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import atom.automation.R

/** The two user channels (results, alerts) plus the quiet one for the listening service. */
internal class Notifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_RESULTS, context.getString(R.string.automation_channel_results), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = context.getString(R.string.automation_channel_results_desc) },
                NotificationChannel(CH_ALERTS, context.getString(R.string.automation_channel_alerts), NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = context.getString(R.string.automation_channel_alerts_desc) },
                NotificationChannel(CH_LISTENING, context.getString(R.string.automation_channel_listening), NotificationManager.IMPORTANCE_MIN)
                    .apply {
                        description = context.getString(R.string.automation_channel_listening_desc)
                        setShowBadge(false)
                    },
            ),
        )
    }

    fun result(key: String, title: String, text: String, conversationId: String?) =
        post(key.hashCode(), base(CH_RESULTS, title, text, conversationId).build())

    fun alert(key: String, title: String, text: String, conversationId: String? = null) =
        post(("alert:$key").hashCode(), base(CH_ALERTS, title, text, conversationId).setCategory(NotificationCompat.CATEGORY_ERROR).build())

    fun readyToRun(taskId: String, name: String) {
        val run = PendingIntent.getBroadcast(
            context,
            ("run:$taskId").hashCode(),
            Intent(context, ActionReceiver::class.java).setAction(ActionReceiver.ACTION_RUN_TASK).putExtra(ActionReceiver.EXTRA_ID, taskId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = base(CH_ALERTS, "$name is ready to run", "It needs the screen. Unlock, then tap Run now.", null)
            .addAction(0, "Run now", run)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        post(("ready:$taskId").hashCode(), n)
    }

    fun cancelReady(taskId: String) = manager.cancel(("ready:$taskId").hashCode())

    fun listening(count: Int): Notification = NotificationCompat.Builder(context, CH_LISTENING)
        .setSmallIcon(R.drawable.ic_stat_automation)
        .setContentTitle(if (count == 1) "1 automation is listening" else "$count automations are listening")
        .setContentText("Waiting for phone events. Uses no battery while idle.")
        .setContentIntent(openApp(null))
        .setOngoing(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .build()

    private fun base(channel: String, title: String, text: String, conversationId: String?) =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_automation)
            .setContentTitle(title)
            .setContentText(text.lineSequence().firstOrNull { it.isNotBlank() }?.take(200) ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(1500)))
            .setContentIntent(openApp(conversationId))
            .setAutoCancel(true)

    private fun openApp(conversationId: String?): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (conversationId != null) launch.putExtra(EXTRA_CONVERSATION_ID, conversationId)
        return PendingIntent.getActivity(
            context,
            (conversationId ?: "open").hashCode(),
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun post(id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Health.granted(context, Manifest.permission.POST_NOTIFICATIONS)) return
        runCatching { manager.notify(id, n) }
    }

    companion object {
        const val CH_RESULTS = "automation_results"
        const val CH_ALERTS = "automation_alerts"
        const val CH_LISTENING = "automation_listening"
        const val LISTENING_ID = 0x41544f4d

        /** Set on the launch intent when a notification should open a conversation. */
        const val EXTRA_CONVERSATION_ID = "atom.automation.extra.CONVERSATION_ID"
    }
}
