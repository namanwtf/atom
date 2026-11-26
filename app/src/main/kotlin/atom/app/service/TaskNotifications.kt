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
import atom.app.agent.LiveRun
import atom.app.agent.PendingApproval
import atom.app.agent.RunOutcome
import atom.app.ui.MainActivity
import atom.app.ui.chat.ChatLinkActivity
import atom.app.ui.common.humanizeTool
import atom.core.llm.Role
import atom.core.runtime.LiveUpdate
import atom.ui.feature.RunPhase

class TaskNotifications(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    /** Settings › "Live Update notification": promote the single-run notification on Android 16+. */
    @Volatile
    var liveUpdates: Boolean = true

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

    /**
     * The foreground notification. With one run it is that run (Open, Pause/Resume, Stop); with several it is the
     * "Running tasks" summary and every run gets its own child notification with Open and Stop.
     */
    fun foreground(active: List<LiveRun>): Notification {
        if (active.size <= 1) {
            val promote = LiveUpdate.promote(Build.VERSION.SDK_INT, liveUpdates, singleRun = true)
            return runNotification(active.firstOrNull(), asGroupChild = false, promote = promote)
        }
        val inbox = NotificationCompat.InboxStyle()
        active.forEach { inbox.addLine(titleOf(it) + " · " + statusOf(it)) }
        return base(CH_TASK)
            .setContentTitle(context.getString(R.string.notif_running_tasks))
            .setContentText(context.resources.getQuantityString(R.plurals.notif_running_count, active.size, active.size))
            .setStyle(inbox)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setGroup(GROUP_RUNS)
            .setGroupSummary(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp())
            .addAction(0, context.getString(R.string.notif_open), openApp())
            .addAction(0, context.getString(R.string.action_stop), broadcast(TaskActionReceiver.ACTION_STOP_ALL, null, REQ_STOP_ALL))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                base(CH_TASK)
                    .setContentTitle(context.getString(R.string.notif_running_tasks))
                    .setContentText(context.resources.getQuantityString(R.plurals.notif_running_count, active.size, active.size))
                    .setOngoing(true)
                    .setSilent(true)
                    .setGroup(GROUP_RUNS)
                    .setGroupSummary(true)
                    .build(),
            )
            .build()
    }

    /** Child notifications, one per run, only while more than one runs. */
    fun syncChildren(active: List<LiveRun>, shown: MutableSet<String>) {
        val wanted = if (active.size > 1) active.associateBy { it.conversation.id } else emptyMap()
        (shown - wanted.keys).forEach { manager.cancel(TAG_RUN, childId(it)) }
        shown.retainAll(wanted.keys)
        if (!canPost()) return
        wanted.forEach { (id, run) ->
            notifySafely(TAG_RUN, childId(id), runNotification(run, asGroupChild = true))
            shown += id
        }
    }

    private fun runNotification(run: LiveRun?, asGroupChild: Boolean, promote: Boolean = false): Notification {
        val builder = base(CH_TASK)
            .setContentTitle(run?.let(::titleOf) ?: context.getString(R.string.notif_working_title))
            .setContentText(run?.let(::statusOf) ?: context.getString(R.string.notif_starting))
            .setSubText(run?.engineLabel)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (run?.phase != RunPhase.Paused) builder.setProgress(0, 0, true)
        if (asGroupChild) builder.setGroup(GROUP_RUNS)
        val id = run?.conversation?.id
        builder.setContentIntent(if (id != null) openConversation(id) else openApp())
        if (id != null) {
            builder.addAction(0, context.getString(R.string.notif_open), openConversation(id))
            if (!asGroupChild) {
                if (run.phase == RunPhase.Paused) {
                    builder.addAction(0, context.getString(R.string.run_resume), broadcast(TaskActionReceiver.ACTION_RESUME, id, REQ_RESUME))
                } else {
                    builder.addAction(0, context.getString(R.string.run_pause), broadcast(TaskActionReceiver.ACTION_PAUSE, id, REQ_PAUSE))
                }
            }
            builder.addAction(0, context.getString(R.string.action_stop), broadcast(TaskActionReceiver.ACTION_STOP, id, REQ_STOP + childId(id)))
        }
        if (promote) promoteAsLiveUpdate(builder, run)
        builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicRunNotification(run, asGroupChild, promote))
        return builder.build()
    }

    /**
     * What the lock screen shows of a running task when notifications hide sensitive content: "Atom is running"
     * and a status word, never the conversation title, the tool or any answer text. It stays promotable so the
     * status-bar chip keeps its word.
     */
    private fun publicRunNotification(run: LiveRun?, asGroupChild: Boolean, promote: Boolean): Notification {
        val word = context.getString(chipText(run))
        val builder = base(CH_TASK)
            .setContentTitle(context.getString(R.string.notif_working_title))
            .setContentText(word)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (asGroupChild) builder.setGroup(GROUP_RUNS)
        if (promote && Build.VERSION.SDK_INT >= LiveUpdate.MIN_SDK) {
            builder.setColorized(false).setRequestPromotedOngoing(true).setShortCriticalText(word)
        }
        return builder.build()
    }

    private fun chipText(run: LiveRun?): Int = when (
        LiveUpdate.chip(
            paused = run?.phase == RunPhase.Paused,
            waitingForUser = run?.pending != null,
            usingTool = run?.currentTool != null,
            streamingAnswer = run != null && run.stream.text.isNotEmpty(),
        )
    ) {
        LiveUpdate.Chip.Working -> R.string.live_working
        LiveUpdate.Chip.Answering -> R.string.run_answering
        LiveUpdate.Chip.Paused -> R.string.run_paused
        LiveUpdate.Chip.Waiting -> R.string.live_waiting
    }

    /**
     * Android 16+: asks for a Live Update (status-bar chip, top of the shade). The chip reads "Working" while Atom
     * acts and "Answering" while the final answer streams. The system only promotes ongoing, non-colorized
     * notifications with a title, which this one is; older Androids keep the plain ongoing notification.
     */
    private fun promoteAsLiveUpdate(builder: NotificationCompat.Builder, run: LiveRun?) {
        if (Build.VERSION.SDK_INT < LiveUpdate.MIN_SDK) return
        builder.setColorized(false)
            .setRequestPromotedOngoing(true)
            .setShortCriticalText(context.getString(chipText(run)))
    }

    private fun titleOf(run: LiveRun): String = run.conversation.title.ifBlank { context.getString(R.string.notif_working_title) }

    private fun statusOf(run: LiveRun): String = when {
        run.pending != null -> context.getString(R.string.notif_waiting_text)
        run.phase == RunPhase.Paused -> context.getString(R.string.run_paused)
        atom.ui.feature.ScreenWait.waiting(run.runId) -> context.getString(R.string.run_waiting_unlock)
        run.phase == RunPhase.WaitingForControl -> context.getString(R.string.run_waiting_control)
        run.retry != null -> context.getString(R.string.run_retrying_short)
        run.compacting -> context.getString(R.string.run_compacting)
        run.currentTool != null -> humanizeTool(run.currentTool)
        run.hostedTool != null -> context.getString(R.string.hosted_running, context.getString(atom.app.ui.chat.hostedToolRes(run.hostedTool)))
        run.phase == RunPhase.Starting -> context.getString(R.string.notif_starting)
        run.stream.text.isNotEmpty() -> context.getString(R.string.run_answering)
        else -> context.getString(R.string.notif_thinking)
    }

    /**
     * Heads-up so the user can answer while Atom is driving another app. When lock-screen notifications hide
     * sensitive content, only "Atom needs your approval" shows there (no action text, no buttons). Either way Allow
     * asks for the device credential first on Android 12+, and on Android 11 [TaskActionReceiver] ignores an Allow
     * tapped while the device is locked.
     */
    fun showApproval(pending: PendingApproval) {
        if (!canPost()) return
        val publicVersion = base(CH_APPROVAL)
            .setContentTitle(context.getString(R.string.notif_waiting_title))
            .setContentText(context.getString(R.string.notif_unlock_to_answer))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openApp())
            .build()
        val allow = NotificationCompat.Action.Builder(0, context.getString(R.string.confirm_allow), approvalAction(pending.id, approve = true))
            .setAuthenticationRequired(true)
            .build()
        // Allow from the shade only when the whole request fits in the few lines a notification shows. A long or
        // multi-line command (an innocent first line, blank lines, then something else) or one that could grant the
        // whole task is answered in the app's dialog, where all of it is visible: tapping opens it.
        val answerInApp = ApprovalText.needsFullView(pending.action, pending.taskGrantable)
        val notification = base(CH_APPROVAL)
            .setContentTitle(context.getString(R.string.confirm_title))
            .setContentText(pending.action)
            .setStyle(NotificationCompat.BigTextStyle().bigText(pending.action))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setTimeoutAfter((pending.expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(1_000))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .addAction(0, context.getString(R.string.confirm_deny), approvalAction(pending.id, approve = false))
            .apply { if (answerInApp) addAction(0, context.getString(R.string.notif_open_to_review), openApp()) else addAction(allow) }
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()
        notifySafely(null, ID_APPROVAL, notification)
    }

    fun cancelApproval() = manager.cancel(ID_APPROVAL)

    fun showResult(run: LiveRun) {
        if (!canPost()) return
        val (title, text) = when (run.outcome) {
            RunOutcome.Failed -> context.getString(R.string.notif_failed) to (run.error ?: "")
            RunOutcome.Cancelled, null -> return
            RunOutcome.StepLimit -> context.getString(R.string.notif_step_limit) to (run.finalText ?: "")
            else -> context.getString(R.string.notif_done) to lastAnswer(run)
        }
        val notification = base(CH_RESULT)
            .setContentTitle(run.conversation.title.ifBlank { title })
            .setContentText(text.ifBlank { title })
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.ifBlank { title }))
            .setAutoCancel(true)
            .setContentIntent(openConversation(run.conversation.id))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                // The lock screen gets only "Task done" (or failed): no conversation title, no answer text.
                base(CH_RESULT)
                    .setContentTitle(title)
                    .setContentText(context.getString(R.string.notif_unlock_to_see))
                    .setAutoCancel(true)
                    .build(),
            )
            .build()
        notifySafely(TAG_RESULT, childId(run.conversation.id), notification)
    }

    fun notifyForeground(active: List<LiveRun>) {
        if (canPost()) notifySafely(null, ID_TASK, foreground(active))
    }

    private fun lastAnswer(run: LiveRun): String =
        run.conversation.messages.lastOrNull { it.message.role == Role.Assistant && it.message.text.isNotBlank() }
            ?.message?.text?.trim()?.take(300).orEmpty()

    private fun base(channel: String) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(atom.ui.R.drawable.ic_stat_atom)
        .setColor(ACCENT)

    private fun canPost(): Boolean =
        manager.areNotificationsEnabled() &&
            (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                )

    @Suppress("MissingPermission")
    private fun notifySafely(tag: String?, id: Int, notification: Notification) {
        try {
            manager.notify(tag, id, notification)
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

    private fun openConversation(conversationId: String): PendingIntent = PendingIntent.getActivity(
        context,
        REQ_OPEN_CONVERSATION + childId(conversationId),
        ChatLinkActivity.intent(context, conversationId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun broadcast(action: String, conversationId: String?, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, TaskActionReceiver::class.java).setAction(action).apply {
            if (conversationId != null) putExtra(TaskActionReceiver.EXTRA_CONVERSATION_ID, conversationId)
        },
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

    private fun childId(conversationId: String): Int = (conversationId.hashCode() and 0x0FFFFFFF) or 0x10000000

    companion object {
        const val CH_TASK = "task"
        const val CH_APPROVAL = "approval"
        const val CH_RESULT = "result"
        const val ID_TASK = 1001
        const val ID_APPROVAL = 1002
        private const val TAG_RUN = "run"
        private const val TAG_RESULT = "result"
        private const val GROUP_RUNS = "atom.runs"
        private const val REQ_OPEN = 10
        private const val REQ_STOP = 11
        private const val REQ_APPROVE = 12
        private const val REQ_DENY = 13
        private const val REQ_STOP_ALL = 14
        private const val REQ_PAUSE = 15
        private const val REQ_RESUME = 16
        private const val REQ_OPEN_CONVERSATION = 17

        /** Black, so phones draw the emblem on a black disc like the launcher icon. */
        private const val ACCENT = 0xFF000000.toInt()
    }
}

/** When an approval must be read in full (in the app) instead of allowed from a notification. Pure, unit tested. */
internal object ApprovalText {
    const val MAX_LINES = 3
    const val MAX_CHARS = 200

    fun needsFullView(action: String, taskGrantable: Boolean): Boolean =
        taskGrantable || action.length > MAX_CHARS || action.lines().size > MAX_LINES
}
