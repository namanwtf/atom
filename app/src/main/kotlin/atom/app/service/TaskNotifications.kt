package atom.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import atom.app.R
import atom.app.agent.LiveTask
import atom.app.agent.PendingApproval
import atom.app.agent.TaskPhase
import atom.app.ui.MainActivity
import atom.app.ui.common.humanizeTool

class TaskNotifications(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_TASK, context.getString(R.string.channel_task), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.channel_task_desc)
                    setShowBadge(false)
                },
                NotificationChannel(CH_APPROVAL, context.getString(R.string.channel_approval), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.channel_approval_desc)
                },
                NotificationChannel(CH_RESULT, context.getString(R.string.channel_result), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.channel_result_desc)
                },
            ),
        )
    }

    /** The ongoing foreground notification: current step and a Stop action. */
    fun progress(live: LiveTask?): Notification {
        val waiting = live?.pending != null
        val title = context.getString(if (waiting) R.string.notif_waiting_title else R.string.notif_working_title)
        val text = when {
            live == null -> context.getString(R.string.notif_starting)
            waiting -> context.getString(R.string.notif_waiting_text)
            live.currentTool != null -> humanizeTool(live.currentTool)
            live.phase == TaskPhase.Preparing -> context.getString(R.string.notif_starting)
            else -> context.getString(R.string.notif_thinking)
        }
        return NotificationCompat.Builder(context, CH_TASK)
            .setSmallIcon(R.drawable.ic_stat_atom)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(live?.engineLabel)
            .setColor(ACCENT)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp())
            .addAction(0, context.getString(R.string.action_stop), action(TaskActionReceiver.ACTION_STOP, REQ_STOP))
            .build()
    }

    /** Heads-up so the user can answer while Atom is driving another app. */
    fun showApproval(pending: PendingApproval) {
        if (!canPost()) return
        val notification = NotificationCompat.Builder(context, CH_APPROVAL)
            .setSmallIcon(R.drawable.ic_stat_atom)
            .setContentTitle(context.getString(R.string.confirm_title))
            .setContentText(pending.action)
            .setStyle(NotificationCompat.BigTextStyle().bigText(pending.action))
            .setColor(ACCENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setTimeoutAfter((pending.expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(1_000))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .addAction(0, context.getString(R.string.confirm_deny), approvalAction(pending.id, approve = false))
            .addAction(0, context.getString(R.string.confirm_allow), approvalAction(pending.id, approve = true))
            .build()
        notifySafely(ID_APPROVAL, notification)
    }

    fun cancelApproval() = manager.cancel(ID_APPROVAL)

    fun showResult(live: LiveTask) {
        if (!canPost()) return
        val (title, text) = when (live.phase) {
            TaskPhase.Failed -> context.getString(R.string.notif_failed) to (live.error ?: "")
            TaskPhase.Cancelled -> return
            TaskPhase.StepLimit -> context.getString(R.string.notif_step_limit) to (live.finalText ?: "")
            else -> context.getString(R.string.notif_done) to lastAssistantText(live)
        }
        val notification = NotificationCompat.Builder(context, CH_RESULT)
            .setSmallIcon(R.drawable.ic_stat_atom)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setColor(ACCENT)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        notifySafely(ID_RESULT, notification)
    }

    fun notifyProgress(live: LiveTask?) {
        if (canPost()) notifySafely(ID_TASK, progress(live))
    }

    private fun lastAssistantText(live: LiveTask): String =
        live.conversation.messages.lastOrNull { it.message.role == atom.core.llm.Role.Assistant && it.message.text.isNotBlank() }
            ?.message?.text?.trim()?.take(300).orEmpty()

    private fun canPost(): Boolean =
        manager.areNotificationsEnabled() &&
            (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                )

    @Suppress("MissingPermission")
    private fun notifySafely(id: Int, notification: Notification) {
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        REQ_OPEN,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(action: String, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, TaskActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun approvalAction(id: Long, approve: Boolean): PendingIntent = PendingIntent.getBroadcast(
        context,
        if (approve) REQ_APPROVE else REQ_DENY,
        Intent(context, TaskActionReceiver::class.java)
            .setAction(if (approve) TaskActionReceiver.ACTION_APPROVE else TaskActionReceiver.ACTION_DENY)
            .putExtra(TaskActionReceiver.EXTRA_APPROVAL_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CH_TASK = "task"
        const val CH_APPROVAL = "approval"
        const val CH_RESULT = "result"
        const val ID_TASK = 1001
        const val ID_APPROVAL = 1002
        const val ID_RESULT = 1003
        private const val REQ_OPEN = 10
        private const val REQ_STOP = 11
        private const val REQ_APPROVE = 12
        private const val REQ_DENY = 13
        private const val ACCENT = 0xFF4F8BFF.toInt()
    }
}
